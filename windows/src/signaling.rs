use anyhow::{Context, Result};
use ed25519_dalek::{SigningKey, Signer};
use futures_util::{SinkExt, StreamExt};
use rand::rngs::OsRng;
use serde::{Deserialize, Serialize};
use std::net::SocketAddr;
use std::sync::Arc;
use tokio::net::TcpStream;
use tokio::sync::{Mutex, oneshot};
use tokio_tungstenite::{connect_async, MaybeTlsStream, WebSocketStream};
use tokio_tungstenite::tungstenite::Message;
use tracing::{info, debug, error};

#[derive(Serialize, Deserialize, Debug)]
#[serde(tag = "type")]
pub enum SignalMessage {
    #[serde(rename = "HOST_REGISTER")]
    HostRegister,
    #[serde(rename = "CHALLENGE")]
    Challenge { challenge: String },
    #[serde(rename = "HOST_AUTH")]
    HostAuth { pubKey: String, signature: String },
    #[serde(rename = "AUTH_SUCCESS")]
    AuthSuccess { remoteId: String },
    #[serde(rename = "ERROR")]
    Error { message: String },
    #[serde(rename = "CLIENT_REQUEST")]
    ClientRequest { sessionId: String, clientIp: String },
    #[serde(rename = "CANDIDATE")]
    Candidate { sessionId: String, candidate: String, isHost: Option<bool>, serverReflexiveIp: Option<String> },
    #[serde(rename = "ALLOCATE_RELAY")]
    AllocateRelay { sessionId: String },
    #[serde(rename = "RELAY_ALLOCATED")]
    RelayAllocated { relayIp: String, relayPort: u16, sessionId: String },
}

pub struct SignalingClient {
    ws_tx: Arc<Mutex<futures_util::stream::SplitSink<WebSocketStream<MaybeTlsStream<TcpStream>>, Message>>>,
    signing_key: SigningKey,
    pub remote_id: Option<String>,
    pub capture_task: Option<tokio::task::JoinHandle<()>>,
}

impl SignalingClient {
    pub async fn connect(url: &str) -> Result<Arc<Mutex<Self>>> {
        let (ws_stream, _) = connect_async(url).await.context("Failed to connect to signaling server")?;
        let (mut write, mut read) = ws_stream.split();

        let mut csprng = OsRng;
        let signing_key = SigningKey::generate(&mut csprng);
        let verifying_key = signing_key.verifying_key();
        let pubkey_hex = hex::encode(verifying_key.as_bytes());

        let register_msg = serde_json::to_string(&SignalMessage::HostRegister)?;
        write.send(Message::Text(register_msg)).await?;

        let ws_tx = Arc::new(Mutex::new(write));
        let client = Arc::new(Mutex::new(SignalingClient {
            ws_tx: ws_tx.clone(),
            signing_key,
            remote_id: None,
            capture_task: None,
        }));

        let client_clone = client.clone();
        
        tokio::spawn(async move {
            while let Some(msg) = read.next().await {
                match msg {
                    Ok(Message::Text(text)) => {
                        if let Ok(signal) = serde_json::from_str::<SignalMessage>(&text) {
                            if let Err(e) = Self::handle_signal(signal, &client_clone, &pubkey_hex).await {
                                error!("Error handling signal: {}", e);
                            }
                        }
                    }
                    Ok(Message::Binary(bin)) => {
                        crate::input::handle_input_payload(&bin);
                    }
                    Err(e) => error!("WebSocket read error: {}", e),
                    _ => {}
                }
            }
        });

        Ok(client)
    }

    async fn handle_signal(signal: SignalMessage, client: &Arc<Mutex<SignalingClient>>, pubkey_hex: &str) -> Result<()> {
        match signal {
            SignalMessage::Challenge { challenge } => {
                let mut c = client.lock().await;
                let signature = c.signing_key.sign(challenge.as_bytes());
                let sig_hex = hex::encode(signature.to_bytes());
                
                let auth_msg = serde_json::to_string(&SignalMessage::HostAuth {
                    pubKey: pubkey_hex.to_string(),
                    signature: sig_hex,
                })?;
                
                c.ws_tx.lock().await.send(Message::Text(auth_msg)).await?;
                debug!("Sent auth signature");
            }
            SignalMessage::AuthSuccess { remoteId } => {
                let mut c = client.lock().await;
                c.remote_id = Some(remoteId.clone());
                info!("========================================");
                info!("  DirectLink Remote ID: {}", remoteId);
                info!("========================================");
            }
            SignalMessage::ClientRequest { sessionId, clientIp } => {
                info!("Incoming connection request from {} — starting WebSocket video relay", clientIp);
                
                let c = client.clone();
                let session = sessionId.clone();
                
                // Abort any existing capture loop so they don't fight over the websocket!
                {
                    let mut lock = client.lock().await;
                    if let Some(task) = lock.capture_task.take() {
                        info!("Canceling previous capture task...");
                        task.abort();
                    }
                }

                tokio::spawn(async move {
                    // Tell Android "ws_relay:<sessionId>" so it connects via WebSocket relay
                    let candidate_msg = serde_json::to_string(&SignalMessage::Candidate {
                        sessionId: session.clone(),
                        candidate: format!("ws_relay:{}", session),
                        isHost: Some(true),
                        serverReflexiveIp: None,
                    }).unwrap();
                    
                    {
                        let lock = c.lock().await;
                        let mut tx_lock = lock.ws_tx.lock().await;
                        let _ = tx_lock.send(Message::Text(candidate_msg)).await;
                    }

                    info!("========================================");
                    info!("  CLIENT CONNECTED — WebSocket relay");
                    info!("  Starting video stream for session: {}", session);
                    info!("========================================");

                    // Start capture + encoder, stream binary frames over this WebSocket
                    let (tx, mut rx) = tokio::sync::mpsc::channel::<Vec<u8>>(4);
                    let capture_handle = tokio::spawn(async move {
                        if let Err(e) = crate::capture::start_capture_loop(tx).await {
                            error!("Capture loop error: {}", e);
                        }
                    });

                    {
                        let mut lock = c.lock().await;
                        lock.capture_task = Some(capture_handle);
                    }

                    let mut frame_counter: u32 = 0;
                    while let Some(nalu) = rx.recv().await {
                        let frame_id = frame_counter;
                        frame_counter = frame_counter.wrapping_add(1);

                        let chunks = nalu.chunks(1200).collect::<Vec<_>>();
                        let total_chunks = chunks.len() as u16;

                        for (i, chunk) in chunks.iter().enumerate() {
                            let chunk_idx = i as u16;
                            // Binary packet: [2-byte chunk_idx][2-byte total][4-byte frame_id][payload]
                            let mut packet = Vec::with_capacity(8 + chunk.len());
                            packet.extend_from_slice(&chunk_idx.to_le_bytes());
                            packet.extend_from_slice(&total_chunks.to_le_bytes());
                            packet.extend_from_slice(&frame_id.to_le_bytes());
                            packet.extend_from_slice(chunk);

                            let lock = c.lock().await;
                            let mut tx_lock = lock.ws_tx.lock().await;
                            if tx_lock.send(Message::Binary(packet)).await.is_err() {
                                info!("Client disconnected, stopping stream");
                                return;
                            }
                        }

                        if frame_counter % 30 == 0 {
                            info!("Streamed frame {} ({} bytes, {} chunks) via WebSocket", frame_id, nalu.len(), total_chunks);
                        }
                    }
                });
            }
            SignalMessage::Candidate { sessionId, candidate, serverReflexiveIp, .. } => {
                info!("Received candidate from Android for {}: {}", sessionId, candidate);
                if let Some(ip) = serverReflexiveIp {
                    info!("Android public IP: {}", ip);
                }
            }
            SignalMessage::Error { message } => {
                error!("Signaling Error: {}", message);
            }
            SignalMessage::RelayAllocated { relayIp, relayPort, sessionId } => {
                info!("Relay allocated at {}:{} for session {}", relayIp, relayPort, sessionId);
            }
            _ => {}
        }
        Ok(())
    }
}
