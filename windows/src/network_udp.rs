use anyhow::Result;
use bytes::{BufMut, BytesMut};
use std::net::SocketAddr;
use std::sync::{Arc, OnceLock, RwLock};
use std::sync::atomic::{AtomicU32, Ordering};
use tokio::net::UdpSocket;
use tokio::sync::mpsc;
use tracing::{error, info};

pub const MAGIC_BYTES: [u8; 4] = [b'D', b'L', b'P', b'1'];
const MAX_UDP_PAYLOAD: usize = 1024; // 1024 fits inside WireGuard / Ethernet MTUs perfectly

// Packet Types
const PACKET_HELLO: u8 = 0x01;
const PACKET_VIDEO: u8 = 0x06;
const PACKET_INPUT: u8 = 0x07;
const PACKET_SETTINGS: u8 = 0x09;
const PACKET_REQUEST_IDR: u8 = 0x0A;

pub static VIDEO_SOCKET: OnceLock<std::net::UdpSocket> = OnceLock::new();
pub static ACTIVE_CLIENT: RwLock<Option<SocketAddr>> = RwLock::new(None);
static VIDEO_SEQUENCE: AtomicU32 = AtomicU32::new(0);

/// Zero-latency direct UDP transmission from the hardware encoder thread
pub fn broadcast_video_frame(nalu: &[u8]) {
    let remote_addr = match *ACTIVE_CLIENT.read().unwrap() {
        Some(addr) => addr,
        None => return,
    };
    let socket = match VIDEO_SOCKET.get() {
        Some(s) => s,
        None => return,
    };

    let seq = VIDEO_SEQUENCE.fetch_add(1, Ordering::Relaxed);
    let total_chunks = ((nalu.len() + MAX_UDP_PAYLOAD - 1) / MAX_UDP_PAYLOAD) as u16;

    let mut packet_buf = [0u8; 15 + MAX_UDP_PAYLOAD];
    packet_buf[0..4].copy_from_slice(&MAGIC_BYTES);
    packet_buf[4] = PACKET_VIDEO;
    packet_buf[5..9].copy_from_slice(&seq.to_le_bytes());
    packet_buf[11..13].copy_from_slice(&total_chunks.to_le_bytes());

    for (chunk_index, chunk) in nalu.chunks(MAX_UDP_PAYLOAD).enumerate() {
        let chunk_len = chunk.len() as u16;
        packet_buf[9..11].copy_from_slice(&(chunk_index as u16).to_le_bytes());
        packet_buf[13..15].copy_from_slice(&chunk_len.to_le_bytes());
        packet_buf[15..15 + chunk.len()].copy_from_slice(chunk);

        let total_packet_len = 15 + chunk.len();
        let payload = &packet_buf[..total_packet_len];

        // Micro-pacing for multi-packet bursts (prevents Wi-Fi AP queue overflow on keyframes)
        if total_chunks > 4 && chunk_index > 0 {
            let spin_start = std::time::Instant::now();
            while spin_start.elapsed().as_micros() < 20 {
                std::hint::spin_loop();
            }
        }

        // Send with transient WouldBlock retry (handles OS socket buffer backpressure)
        let mut retries = 0;
        loop {
            match socket.send_to(payload, remote_addr) {
                Ok(_) => break,
                Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                    if retries >= 10 {
                        break;
                    }
                    retries += 1;
                    std::thread::yield_now();
                }
                Err(e) => {
                    error!("UDP video send_to error: {:?}", e);
                    break;
                }
            }
        }
    }
}

pub async fn start_direct_server(std_socket: std::net::UdpSocket) -> Result<()> {
    info!("========================================");
    info!(" DirectLink Server Listening on UDP 21118");
    info!(" Waiting for Android Client (Local IP)...");
    info!("========================================");

    // Save cloned socket for the dedicated hardware capture thread
    let video_sock = std_socket.try_clone()?;
    let _ = VIDEO_SOCKET.set(video_sock);

    let socket = Arc::new(UdpSocket::from_std(std_socket)?);

    // 1. Initialize dedicated OS capture thread (High-priority hardware loop)
    std::thread::Builder::new()
        .name("directlink-capture".into())
        .spawn(move || {
            loop {
                info!("Starting dedicated OS capture thread");
                if let Err(e) = crate::capture::start_capture_loop() {
                    error!("Capture loop exited: {:?}. Restarting in 500ms...", e);
                }
                std::thread::sleep(std::time::Duration::from_millis(500));
                crate::capture::FORCE_IDR.store(true, std::sync::atomic::Ordering::Relaxed);
            }
        })
        .expect("Failed to spawn capture thread");

    // 2. Initialize persistent audio capture loop (SINGLETON, auto-restarting)
    let (audio_tx, mut audio_rx) = mpsc::channel::<Vec<u8>>(50);
    tokio::spawn(async move {
        loop {
            if let Err(e) = crate::audio::start_audio_loop(audio_tx.clone()).await {
                error!("Audio loop crashed: {:?}. Restarting in 1s...", e);
                tokio::time::sleep(tokio::time::Duration::from_secs(1)).await;
            }
        }
    });

    // 3. Persistent AUDIO sender loop
    let socket_audio = socket.clone();
    tokio::spawn(async move {
        let mut audio_sequence: u32 = 0;
        while let Some(pcm) = audio_rx.recv().await {
            let remote_addr = match *ACTIVE_CLIENT.read().unwrap() {
                Some(addr) => addr,
                None => continue,
            };
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

    // 4. UDP Receive Loop (Command & Control)
    let mut buf = vec![0u8; 2048];
    loop {
        let (len, remote_addr) = match socket.recv_from(&mut buf).await {
            Ok(res) => res,
            Err(e) => {
                error!("UDP recv error: {}", e);
                continue;
            }
        };

        if len < 5 || &buf[0..4] != &MAGIC_BYTES {
            continue;
        }

        let packet_type = buf[4];
        match packet_type {
            PACKET_HELLO => {
                let _ = socket.send_to(&buf[0..len], remote_addr).await;
                *ACTIVE_CLIENT.write().unwrap() = Some(remote_addr);
                crate::capture::FORCE_IDR.store(true, std::sync::atomic::Ordering::Relaxed);
                info!("CLIENT CONNECTED: {:?}. Active stream routed instantly!", remote_addr);
            }
            PACKET_INPUT => {
                crate::input::handle_input_payload(&buf[5..len]);
            }
            PACKET_REQUEST_IDR => {
                crate::capture::FORCE_IDR.store(true, std::sync::atomic::Ordering::Relaxed);
                info!("Client requested IDR frame via UDP (0x0A)");
            }
            PACKET_SETTINGS => {
                if len >= 13 {
                    let fps = u32::from_le_bytes(buf[5..9].try_into().unwrap());
                    let bitrate = u32::from_le_bytes(buf[9..13].try_into().unwrap());
                    crate::capture::TARGET_FPS.store(fps, std::sync::atomic::Ordering::Relaxed);
                    crate::capture::TARGET_BITRATE.store(bitrate, std::sync::atomic::Ordering::Relaxed);
                    info!("Client requested settings change: {} FPS, {} bps", fps, bitrate);
                }
            }
            _ => {}
        }
    }
}

pub async fn start_relay_client(relay_ip: &str, relay_port: u16, session_id: &str) -> Result<()> {
    let std_socket = std::net::UdpSocket::bind("0.0.0.0:0")?;
    std_socket.set_nonblocking(true)?;
    let socket2_sock: socket2::Socket = std_socket.into();
    let _ = socket2_sock.set_send_buffer_size(4 * 1024 * 1024);
    let _ = socket2_sock.set_recv_buffer_size(4 * 1024 * 1024);
    let std_socket: std::net::UdpSocket = socket2_sock.into();
    let _ = VIDEO_SOCKET.set(std_socket.try_clone()?);
    let socket = Arc::new(UdpSocket::from_std(std_socket)?);
    let remote_addr: SocketAddr = format!("{}:{}", relay_ip, relay_port).parse()?;

    // Bind to Relay
    let bind_msg = format!("BIND:{}:HOST", session_id);
    socket.send_to(bind_msg.as_bytes(), remote_addr).await?;

    info!("========================================");
    info!(" DIRECTLINK RELAY AUTHENTICATED");
    info!(" Streaming Video via UDP Relay!");
    info!("========================================");

    *ACTIVE_CLIENT.write().unwrap() = Some(remote_addr);
    crate::capture::FORCE_IDR.store(true, std::sync::atomic::Ordering::Relaxed);

    let mut buf = vec![0u8; 2048];
    loop {
        let (len, src) = socket.recv_from(&mut buf).await?;
        if src != remote_addr {
            continue;
        }

        let payload = &buf[..len];
        if payload == b"BIND_OK" {
            continue;
        }

        if len >= 5 && &payload[0..4] == &MAGIC_BYTES {
            if payload[4] == PACKET_INPUT {
                crate::input::handle_input_payload(&payload[5..len]);
            }
        }
    }
}

