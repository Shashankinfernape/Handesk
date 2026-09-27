import os

sig_content = """use anyhow::{Context, Result};
use ed25519_dalek::{SigningKey, Signer};
use futures_util::{SinkExt, StreamExt};
use rand::rngs::OsRng;
use serde::{Deserialize, Serialize};
use std::net::SocketAddr;
use std::sync::Arc;
use tokio::net::TcpStream;
use tokio::sync::Mutex;
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
    pub public_ip: Option<String>,
    pub udp_socket: Arc<tokio::net::UdpSocket>,
}

impl SignalingClient {
    pub async fn connect(url: &str, public_ip: Option<String>, udp_socket: Arc<tokio::net::UdpSocket>) -> Result<Arc<Mutex<Self>>> {
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
            public_ip,
            udp_socket,
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
                info!("Incoming connection request from {} - sending UDP candidate", clientIp);
                let c = client.clone();
                let session = sessionId.clone();

                tokio::spawn(async move {
                    let lock = c.lock().await;
                    if let Some(ip) = &lock.public_ip {
                        let candidate_msg = serde_json::to_string(&SignalMessage::Candidate {
                            sessionId: session.clone(),
                            candidate: format!("udp:{}", ip),
                            isHost: Some(true),
                            serverReflexiveIp: None,
                        }).unwrap();
                        
                        let mut tx_lock = lock.ws_tx.lock().await;
                        let _ = tx_lock.send(Message::Text(candidate_msg)).await;
                    } else {
                        error!("No STUN IP available! Cannot send UDP candidate.");
                    }
                });
            }
            SignalMessage::Candidate { sessionId, candidate, serverReflexiveIp, .. } => {
                info!("Received candidate from Android for {}: {}", sessionId, candidate);
                let mut targets = Vec::new();
                if let Some(ip) = serverReflexiveIp {
                    info!("Android public IP: {}", ip);
                    targets.push(ip);
                }
                if candidate.starts_with("udp:") {
                    let local = candidate.trim_start_matches("udp:");
                    targets.push(format!("{}:21118", local));
                }

                let socket = {
                    let c = client.lock().await;
                    c.udp_socket.clone()
                };

                // Blast hole-punch packets to Android's IPs!
                tokio::spawn(async move {
                    let hello_packet = [b'D', b'L', b'P', b'1', 0x01];
                    for _ in 0..10 {
                        for t in &targets {
                            if let Ok(addr) = t.parse::<std::net::SocketAddr>() {
                                let _ = socket.send_to(&hello_packet, addr).await;
                            }
                        }
                        tokio::time::sleep(std::time::Duration::from_millis(50)).await;
                    }
                });
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
"""

with open('windows/src/signaling.rs', 'w', encoding='utf-8') as f:
    f.write(sig_content)

print("Rewritten!")
