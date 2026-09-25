use tokio::net::UdpSocket;
use anyhow::Result;
use bytes::{BufMut, BytesMut};
use std::sync::Arc;
use std::net::SocketAddr;
use tokio::sync::mpsc;
use tracing::{info, error};

pub const MAGIC_BYTES: [u8; 4] = [b'D', b'L', b'P', b'1'];
const MAX_UDP_PAYLOAD: usize = 1200; // Safe below MTU 1500

// Packet Types
const PACKET_HELLO: u8 = 0x01;
const PACKET_VIDEO: u8 = 0x06;
const PACKET_INPUT: u8 = 0x07;

pub async fn start_direct_server() -> Result<()> {
    // Bind to the exact port RustDesk uses for direct IP connections
    let socket = Arc::new(UdpSocket::bind("0.0.0.0:21118").await?);
    info!("========================================");
    info!(" DirectLink Server Listening on UDP 21118");
    info!(" Waiting for Android Client (Local IP)...");
    info!("========================================");

    let mut buf = vec![0u8; 2048];
    let mut current_capture_task: Option<tokio::task::JoinHandle<()>> = None;
    let mut current_client: Option<SocketAddr> = None;

    loop {
        let (len, remote_addr) = match socket.recv_from(&mut buf).await {
            Ok(res) => res,
            Err(e) => {
                error!("UDP recv error: {}", e);
                continue;
            }
        };

        if len < 5 { continue; }
        if &buf[0..4] != &MAGIC_BYTES { continue; }

        let packet_type = buf[4];

        match packet_type {
            PACKET_HELLO => {
                // Ignore duplicate HELLOs from the exact same tablet session to prevent crashing DXGI
                if current_client == Some(remote_addr) {
                    continue;
                }
                
                info!("========================================");
                info!(" CLIENT CONNECTED: {:?}", remote_addr);
                info!(" Initiating Direct P2P Video Stream!");
                info!("========================================");
                
                current_client = Some(remote_addr);

                // Abort old capture task if a DIFFERENT client connects
                if let Some(task) = current_capture_task.take() {
                    task.abort();
                    // Give DXGI a split second to release COM resources
                    tokio::time::sleep(std::time::Duration::from_millis(500)).await;
                }

                // Create channel for capture loop to send us NALUs
                let (video_tx, mut video_rx) = mpsc::channel::<Vec<u8>>(4);

                // Spawn the capture loop
                let capture_handle = tokio::spawn(async move {
                    if let Err(e) = crate::capture::start_capture_loop(video_tx).await {
                        error!("Capture loop failed: {:?}", e);
                    }
                });
                current_capture_task = Some(capture_handle);

                // Spawn the sender loop for THIS specific client
                let socket_clone = socket.clone();
                tokio::spawn(async move {
                    let mut sequence: u32 = 0;
                    while let Some(nalu) = video_rx.recv().await {
                        sequence = sequence.wrapping_add(1);
                        let total_chunks = ((nalu.len() + MAX_UDP_PAYLOAD - 1) / MAX_UDP_PAYLOAD) as u16;
                        
                        for (chunk_index, chunk) in nalu.chunks(MAX_UDP_PAYLOAD).enumerate() {
                            let mut frame = BytesMut::with_capacity(15 + chunk.len());
                            frame.extend_from_slice(&MAGIC_BYTES);
                            frame.put_u8(PACKET_VIDEO);
                            frame.put_u32_le(sequence);
                            frame.put_u16_le(chunk_index as u16);
                            frame.put_u16_le(total_chunks);
                            frame.put_u16_le(chunk.len() as u16);
                            frame.extend_from_slice(chunk);
                            
                            if let Err(e) = socket_clone.send_to(&frame, remote_addr).await {
                                error!("Failed to send UDP chunk: {}", e);
                            }
                            // Removed artificial pacing to prevent Windows 15.6ms Timer Resolution penalty.
                            // Burst at true maximum UDP velocity!
                        }
                    }
                });
            }
            PACKET_INPUT => {
                crate::input::handle_input_payload(&buf[5..len]);
            }
            _ => {}
        }
    }
}
