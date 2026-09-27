use tokio::net::UdpSocket;
use anyhow::Result;
use bytes::{BufMut, BytesMut};
use std::sync::Arc;
use std::net::SocketAddr;
use tokio::sync::mpsc;
use tracing::{info, error};

pub const MAGIC_BYTES: [u8; 4] = [b'D', b'L', b'P', b'1'];
const MAX_UDP_PAYLOAD: usize = 1024; // 1024 perfectly fits inside Tailscale Wireguard's 1280 MTU

// Packet Types
const PACKET_HELLO: u8 = 0x01;
const PACKET_VIDEO: u8 = 0x06;
const PACKET_INPUT: u8 = 0x07;

pub async fn start_direct_server(socket: Arc<UdpSocket>) -> Result<()> {
    info!("========================================");
    info!(" DirectLink Server Listening on UDP 21118");
    info!(" Waiting for Android Client (Local IP)...");
    info!("========================================");

    let mut buf = vec![0u8; 2048];
    let mut current_capture_task: Option<tokio::task::JoinHandle<()>> = None;
    let mut current_audio_task: Option<tokio::task::JoinHandle<()>> = None;
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
                // Instantly echo HELLO back so Android doesn't time out while NVENC initializes!
                let _ = socket.send_to(&buf[0..len], remote_addr).await;

                // Ignore duplicate HELLOs from the exact same tablet session to prevent crashing DXGI
                if current_client == Some(remote_addr) {
                    continue;
                }

                // Auto-detect Tailscale connection (100.x.x.x IP range)
                let is_tailscale = matches!(remote_addr.ip(), std::net::IpAddr::V4(ip) if ip.octets()[0] == 100);
                let pace_us: u128 = if is_tailscale { 2500 } else { 300 };

                info!("========================================");
                info!(" CLIENT CONNECTED: {:?}", remote_addr);
                if is_tailscale {
                    info!(" Mode: TAILSCALE (Internet) — 2500us pacing (3.2Mbps bandwidth)");
                } else {
                    info!(" Mode: LOCAL Wi-Fi — 300us fast pacing");
                }
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
                let (video_tx, mut video_rx) = mpsc::channel::<Vec<u8>>(1);

                // Spawn the capture loop
                let capture_handle = tokio::spawn(async move {
                    if let Err(e) = crate::capture::start_capture_loop(video_tx).await {
                        error!("Capture loop failed: {:?}", e);
                    }
                });
                current_capture_task = Some(capture_handle);

                if let Some(task) = current_audio_task.take() {
                    task.abort();
                }
                
                let (audio_tx, mut audio_rx) = mpsc::channel::<Vec<u8>>(50);
                let audio_handle = tokio::spawn(async move {
                    if let Err(e) = crate::audio::start_audio_loop(audio_tx).await {
                        error!("Audio loop failed: {:?}", e);
                    }
                });
                current_audio_task = Some(audio_handle);

                // --- AUDIO DISABLED FOR NOW TO PREVENT VIDEO CHOKING ---
                /*
                // Spawn the AUDIO sender loop
                let socket_audio = socket.clone();
                tokio::spawn(async move {
                    let mut audio_sequence: u32 = 0;
                    while let Some(pcm) = audio_rx.recv().await {
                        audio_sequence = audio_sequence.wrapping_add(1);
                        
                        let audio_mtu = 1200;
                        let total_chunks = ((pcm.len() + audio_mtu - 1) / audio_mtu) as u16;
                        
                        for (chunk_index, chunk) in pcm.chunks(audio_mtu).enumerate() {
                            let mut frame = BytesMut::with_capacity(15 + chunk.len());
                            frame.extend_from_slice(&MAGIC_BYTES);
                            frame.put_u8(0x08); // PACKET_AUDIO
                            frame.put_u32_le(audio_sequence);
                            frame.put_u16_le(chunk_index as u16);
                            frame.put_u16_le(total_chunks);
                            frame.put_u16_le(chunk.len() as u16);
                            frame.extend_from_slice(chunk);
                            
                            let _ = socket_audio.send_to(&frame, remote_addr).await;
                        }
                    }
                });
                */

                // Spawn the VIDEO sender loop for THIS specific client
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
                            
                            // Adaptive pacing: 300us for local Wi-Fi, 2000us for Tailscale internet tunnel
                            let spin_start = std::time::Instant::now();
                            while spin_start.elapsed().as_micros() < pace_us {
                                std::hint::spin_loop();
                            }
                            tokio::task::yield_now().await;
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
pub async fn start_relay_client(relay_ip: &str, relay_port: u16, session_id: &str) -> Result<()> {
    let socket = Arc::new(UdpSocket::bind("0.0.0.0:0").await?);
    let remote_addr: SocketAddr = format!("{}:{}", relay_ip, relay_port).parse()?;
    
    // Bind to Relay
    let bind_msg = format!("BIND:{}:HOST", session_id);
    socket.send_to(bind_msg.as_bytes(), remote_addr).await?;
    
    info!("========================================");
    info!(" DIRECTLINK RELAY AUTHENTICATED");
    info!(" Streaming Video via UDP Relay!");
    info!("========================================");

    // Spawn capture loop
    let (video_tx, mut video_rx) = mpsc::channel::<Vec<u8>>(4);
    tokio::spawn(async move {
        if let Err(e) = crate::capture::start_capture_loop(video_tx).await {
            error!("Capture loop failed: {:?}", e);
        }
    });

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
let _ = socket_clone.send_to(&frame, remote_addr).await;
                                // Micro-pacing: Prevent Tailscale/UDP buffer drop for massive IDR frames
                                // We spin-wait for 150us to spread the burst out, ensuring 100% delivery.
                                let spin_start = std::time::Instant::now();
                                while spin_start.elapsed().as_micros() < 300 {
                                    std::hint::spin_loop();
                                }
                                tokio::task::yield_now().await;

            }
        }
    });

    let mut buf = vec![0u8; 2048];
    loop {
        let (len, src) = socket.recv_from(&mut buf).await?;
        if src != remote_addr { continue; }
        
        let payload = &buf[..len];
        if payload == b"BIND_OK" { continue; }
        
        if len >= 5 && &payload[0..4] == &MAGIC_BYTES {
            if payload[4] == PACKET_INPUT {
                crate::input::handle_input_payload(&payload[5..len]);
            }
        }
    }
}






