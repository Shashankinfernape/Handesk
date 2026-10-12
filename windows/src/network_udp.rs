use anyhow::Result;
use std::net::SocketAddr;
use std::sync::{Arc, OnceLock, RwLock};
use std::sync::atomic::{AtomicU32, Ordering};
use tokio::net::UdpSocket;
use tokio::sync::mpsc;
use tracing::{error, info};
use std::time::Instant;

pub const MAGIC_BYTES: [u8; 4] = [b'D', b'L', b'P', b'1'];

// Packet Types (Handesk Datagram v2)
pub const PACKET_HELLO: u8 = 0x01;
pub const PACKET_PING_LEGACY: u8 = 0x02;
pub const PACKET_PONG_LEGACY: u8 = 0x03;
pub const PACKET_VIDEO: u8 = 0x06;
pub const PACKET_INPUT: u8 = 0x07;
pub const PACKET_AUDIO: u8 = 0x08;
pub const PACKET_SETTINGS: u8 = 0x09;
pub const PACKET_REQUEST_IDR: u8 = 0x0A;
pub const PACKET_PING: u8 = 0x0B;
pub const PACKET_PONG: u8 = 0x0C;
pub const PACKET_RESOLUTION: u8 = 0x0D;

pub static SMOOTHED_RTT_MS: AtomicU32 = AtomicU32::new(0);
pub static VIDEO_SOCKET: OnceLock<std::net::UdpSocket> = OnceLock::new();
pub static ACTIVE_CLIENT: RwLock<Option<SocketAddr>> = RwLock::new(None);
static VIDEO_SEQUENCE: AtomicU32 = AtomicU32::new(0);

pub fn is_tailscale_ip(addr: &SocketAddr) -> bool {
    match addr.ip() {
        std::net::IpAddr::V4(ipv4) => ipv4.octets()[0] == 100,
        std::net::IpAddr::V6(ipv6) => {
            let segs = ipv6.segments();
            segs[0] == 0xfd7a && segs[1] == 0x115c && segs[2] == 0xa1e0
        }
    }
}

pub fn detect_lan_ip() -> String {
    // 1. Kernel UDP routing table probe: connect dummy socket to public DNS 8.8.8.8
    if let Ok(socket) = std::net::UdpSocket::bind("0.0.0.0:0") {
        if socket.connect("8.8.8.8:80").is_ok() {
            if let Ok(local_addr) = socket.local_addr() {
                let ip = local_addr.ip();
                if !ip.is_loopback() && !ip.is_unspecified() {
                    let ip_str = ip.to_string();
                    if !ip_str.starts_with("100.")
                        && !ip_str.starts_with("192.168.56.")
                        && !ip_str.starts_with("192.168.137.")
                        && !ip_str.starts_with("169.254.") {
                        return ip_str;
                    }
                }
            }
        }
    }
    // 2. Fallback probe to 1.1.1.1
    if let Ok(socket) = std::net::UdpSocket::bind("0.0.0.0:0") {
        if socket.connect("1.1.1.1:80").is_ok() {
            if let Ok(local_addr) = socket.local_addr() {
                let ip = local_addr.ip();
                if !ip.is_loopback() && !ip.is_unspecified() {
                    let ip_str = ip.to_string();
                    if !ip_str.starts_with("100.")
                        && !ip_str.starts_with("192.168.56.")
                        && !ip_str.starts_with("192.168.137.")
                        && !ip_str.starts_with("169.254.") {
                        return ip_str;
                    }
                }
            }
        }
    }
    "Not Connected".to_string()
}

pub fn detect_tailscale_ip() -> String {
    let candidates = [
        "tailscale",
        "C:\\Program Files\\Tailscale\\tailscale.exe",
        "C:\\Program Files (x86)\\Tailscale\\tailscale.exe",
    ];
    for cmd in candidates {
        if let Ok(output) = std::process::Command::new(cmd).args(["ip", "-4"]).output() {
            if output.status.success() {
                let ip_str = String::from_utf8_lossy(&output.stdout).trim().to_string();
                if ip_str.starts_with("100.") {
                    return ip_str;
                }
            }
        }
    }
    if let Ok(output) = std::process::Command::new("ipconfig").output() {
        let text = String::from_utf8_lossy(&output.stdout);
        let mut in_ts_block = false;
        for line in text.lines() {
            if line.contains("Tailscale") {
                in_ts_block = true;
            } else if line.contains("adapter ") && in_ts_block {
                in_ts_block = false;
            }
            if in_ts_block && line.contains("IPv4 Address") {
                if let Some(pos) = line.find(':') {
                    let ip = line[pos + 1..].trim();
                    if ip.starts_with("100.") {
                        return ip.to_string();
                    }
                }
            }
        }
    }
    "Not Connected".to_string()
}

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

    let is_ts = crate::capture::IS_TAILSCALE_CLIENT.load(Ordering::Relaxed);
    // 1400 bytes on Ethernet LAN, 1100 bytes on Tailscale/WireGuard WAN (zero IP fragmentation)
    let max_payload: usize = if is_ts { 1100 } else { 1400 };

    let seq = VIDEO_SEQUENCE.fetch_add(1, Ordering::Relaxed);
    let total_chunks = ((nalu.len() + max_payload - 1) / max_payload) as u16;

    let mut packet_buf = vec![0u8; 15 + max_payload];
    packet_buf[0..4].copy_from_slice(&MAGIC_BYTES);
    packet_buf[4] = PACKET_VIDEO;
    packet_buf[5..9].copy_from_slice(&seq.to_le_bytes());
    packet_buf[11..13].copy_from_slice(&total_chunks.to_le_bytes());

    for (chunk_index, chunk) in nalu.chunks(max_payload).enumerate() {
        let chunk_len = chunk.len() as u16;
        packet_buf[9..11].copy_from_slice(&(chunk_index as u16).to_le_bytes());
        packet_buf[13..15].copy_from_slice(&chunk_len.to_le_bytes());
        packet_buf[15..15 + chunk.len()].copy_from_slice(chunk);

        let total_packet_len = 15 + chunk.len();
        let payload = &packet_buf[..total_packet_len];

        // Micro-pacing: spreads multi-packet bursts evenly to avoid router/tunnel buffer overflow
        // 120µs on Tailscale flattens the 223 Mbps instantaneous burst to prevent WireGuard drops
        if total_chunks > 4 && chunk_index > 0 {
            let pace_micros = if is_ts { 120 } else { 15 };
            let spin_start = Instant::now();
            while spin_start.elapsed().as_micros() < pace_micros {
                std::hint::spin_loop();
            }
        }

        // Send with transient WouldBlock retry
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

pub fn broadcast_settings(fps: u32, bitrate: u32) {
    let remote_addr = match *ACTIVE_CLIENT.read().unwrap() {
        Some(addr) => addr,
        None => return,
    };
    let socket = match VIDEO_SOCKET.get() {
        Some(s) => s,
        None => return,
    };
    let mut pkt = [0u8; 13];
    pkt[0..4].copy_from_slice(&MAGIC_BYTES);
    pkt[4] = PACKET_SETTINGS;
    pkt[5..9].copy_from_slice(&fps.to_le_bytes());
    pkt[9..13].copy_from_slice(&bitrate.to_le_bytes());
    let _ = socket.send_to(&pkt, remote_addr);
}

pub async fn start_udp_server() -> Result<()> {
    info!("========================================");
    info!(" Handesk Server Listening on UDP 21118");
    info!(" High-Performance Pure UDP Engine");
    info!("========================================");

    use socket2::Socket;
    let std_socket = std::net::UdpSocket::bind("0.0.0.0:21118")?;
    std_socket.set_nonblocking(true)?;

    let socket2_sock: Socket = std_socket.into();
    let _ = socket2_sock.set_send_buffer_size(4 * 1024 * 1024);
    let _ = socket2_sock.set_recv_buffer_size(4 * 1024 * 1024);
    let _ = socket2_sock.set_tos_v4(0xB8); // Expedited Forwarding QoS

    let std_socket: std::net::UdpSocket = socket2_sock.into();

    #[cfg(windows)]
    {
        use std::os::windows::io::AsRawSocket;
        let raw_sock = std_socket.as_raw_socket() as usize;
        let mut new_behavior: u32 = 0; // FALSE to disable WSAECONNRESET reporting on UDP
        let mut bytes_returned: u32 = 0;
        const SIO_UDP_CONNRESET: u32 = 0x9800000C;
        extern "system" {
            fn WSAIoctl(
                s: usize,
                dwIoControlCode: u32,
                lpvInBuffer: *const std::ffi::c_void,
                cbInBuffer: u32,
                lpvOutBuffer: *mut std::ffi::c_void,
                cbOutBuffer: u32,
                lpcbBytesReturned: *mut u32,
                lpOverlapped: *mut std::ffi::c_void,
                lpCompletionRoutine: *mut std::ffi::c_void,
            ) -> i32;
        }
        unsafe {
            let _ = WSAIoctl(
                raw_sock,
                SIO_UDP_CONNRESET,
                &new_behavior as *const _ as *const _,
                std::mem::size_of::<u32>() as u32,
                std::ptr::null_mut(),
                0,
                &mut bytes_returned,
                std::ptr::null_mut(),
                std::ptr::null_mut(),
            );
        }
    }

    let video_sock = std_socket.try_clone()?;
    let _ = VIDEO_SOCKET.set(video_sock);

    let socket = Arc::new(UdpSocket::from_std(std_socket)?);
    let start_time = Instant::now();

    // 1. Dedicated OS capture thread (High-priority hardware loop)
    std::thread::Builder::new()
        .name("handesk-capture".into())
        .spawn(move || {
            loop {
                info!("Starting dedicated OS capture thread");
                if let Err(e) = crate::capture::start_capture_loop() {
                    error!("Capture loop exited: {:?}. Restarting in 500ms...", e);
                }
                std::thread::sleep(std::time::Duration::from_millis(500));
                crate::capture::FORCE_IDR.store(true, Ordering::Relaxed);
            }
        })
        .expect("Failed to spawn capture thread");

    // 2. Persistent audio capture loop
    let (audio_tx, mut audio_rx) = mpsc::channel::<Vec<u8>>(100);
    tokio::spawn(async move {
        loop {
            if let Err(e) = crate::audio::start_audio_loop(audio_tx.clone()).await {
                error!("Audio loop crashed: {:?}. Restarting in 1s...", e);
                tokio::time::sleep(tokio::time::Duration::from_secs(1)).await;
            }
        }
    });

    // 3. Persistent AUDIO direct UDP sender loop (Zero chunking, 1 UDP datagram per Opus frame)
    let socket_audio = socket.clone();
    tokio::spawn(async move {
        while let Some(opus_data) = audio_rx.recv().await {
            let remote_addr = match *ACTIVE_CLIENT.read().unwrap() {
                Some(addr) => addr,
                None => continue,
            };
            let mut packet = Vec::with_capacity(5 + opus_data.len());
            packet.extend_from_slice(&MAGIC_BYTES);
            packet.push(PACKET_AUDIO);
            packet.extend_from_slice(&opus_data);
            let _ = socket_audio.send_to(&packet, remote_addr).await;
        }
    });

    // 4. Background PING sender (every 200ms for fast WAN RTT tracking)
    let socket_ping = socket.clone();
    tokio::spawn(async move {
        let mut interval = tokio::time::interval(std::time::Duration::from_millis(200));
        let mut ping_buf = [0u8; 13];
        ping_buf[0..4].copy_from_slice(&MAGIC_BYTES);
        ping_buf[4] = PACKET_PING;

        loop {
            interval.tick().await;
            let remote_addr = match *ACTIVE_CLIENT.read().unwrap() {
                Some(addr) => addr,
                None => continue,
            };

            let now_us = start_time.elapsed().as_micros() as u64;
            ping_buf[5..13].copy_from_slice(&now_us.to_le_bytes());
            let _ = socket_ping.send_to(&ping_buf, remote_addr).await;
        }
    });

    // 5. Dynamic Desktop Resolution change detection loop
    let socket_res = socket.clone();
    tokio::spawn(async move {
        let mut interval = tokio::time::interval(std::time::Duration::from_millis(300));
        let mut last_w = 0u32;
        let mut last_h = 0u32;
        loop {
            interval.tick().await;
            let remote_addr = match *ACTIVE_CLIENT.read().unwrap() {
                Some(addr) => addr,
                None => continue,
            };
            let w = crate::capture::DESKTOP_WIDTH.load(Ordering::Relaxed);
            let h = crate::capture::DESKTOP_HEIGHT.load(Ordering::Relaxed);
            if (w != last_w || h != last_h) && w > 0 && h > 0 {
                last_w = w;
                last_h = h;
                info!("Sending Desktop Resolution update to client: {}x{}", w, h);
                let mut res_pkt = Vec::with_capacity(13);
                res_pkt.extend_from_slice(&MAGIC_BYTES);
                res_pkt.push(PACKET_RESOLUTION);
                res_pkt.extend_from_slice(&w.to_le_bytes());
                res_pkt.extend_from_slice(&h.to_le_bytes());
                let _ = socket_res.send_to(&res_pkt, remote_addr).await;
            }
        }
    });

    // 6. Periodic 1-second telemetry logging
    tokio::spawn(async move {
        let mut interval = tokio::time::interval(std::time::Duration::from_millis(1000));
        loop {
            interval.tick().await;
            let client = *ACTIVE_CLIENT.read().unwrap();
            if let Some(c_addr) = client {
                let cur_rtt = SMOOTHED_RTT_MS.load(Ordering::Relaxed);
                let cur_bitrate = crate::capture::TARGET_BITRATE.load(Ordering::Relaxed);
                let cur_fps = crate::capture::TARGET_FPS.load(Ordering::Relaxed);
                let auto = crate::capture::AUTO_BITRATE_ENABLED.load(Ordering::Relaxed);
                let is_ts = is_tailscale_ip(&c_addr);
                info!(
                    "[HOST UDP TELEMETRY] Profile: {} | RTT: {}ms | Target Bitrate: {:.2} Mbps | Target FPS: {} | Auto: {}",
                    if is_ts { "TAILSCALE WAN (1100 MTU)" } else { "LOCAL LAN (1400 MTU unconstrained)" },
                    cur_rtt,
                    cur_bitrate as f64 / 1_000_000.0,
                    cur_fps,
                    auto
                );
            }
        }
    });

    // 7. Main UDP Receive Loop (Command & Control)
    let mut buf = vec![0u8; 4096];
    loop {
        let (len, remote_addr) = match socket.recv_from(&mut buf).await {
            Ok(res) => res,
            Err(e) => {
                if e.raw_os_error() == Some(10054) {
                    tokio::time::sleep(tokio::time::Duration::from_millis(5)).await;
                    continue;
                }
                error!("UDP recv error: {}", e);
                tokio::time::sleep(tokio::time::Duration::from_millis(10)).await;
                continue;
            }
        };

        if len < 5 || &buf[0..4] != &MAGIC_BYTES {
            continue;
        }

        let packet_type = buf[4];
        match packet_type {
            PACKET_HELLO => {
                info!("Received UDP HELLO from {remote_addr}. Initializing Handesk session.");
                let is_ts = is_tailscale_ip(&remote_addr);
                crate::capture::IS_TAILSCALE_CLIENT.store(is_ts, Ordering::Relaxed);

                if is_ts {
                    info!("Client {remote_addr} connected via TAILSCALE WAN - 1100 MTU, 60 FPS cap, 4.8 Mbps sweetspot, 120µs pacing");
                    crate::capture::TARGET_FPS.store(60, Ordering::Relaxed);
                    crate::capture::CONFIGURED_BITRATE.store(4_800_000, Ordering::Relaxed);
                    crate::capture::TARGET_BITRATE.store(4_800_000, Ordering::Relaxed);
                    crate::capture::TARGET_GOP.store(90, Ordering::Relaxed);
                    crate::capture::AUTO_BITRATE_ENABLED.store(true, Ordering::Relaxed);
                } else {
                    info!("Client {remote_addr} connected via LOCAL LAN - 1400 MTU, initial 1080p 60Hz (12 Mbps), unconstrained capability");
                    crate::capture::TARGET_FPS.store(60, Ordering::Relaxed);
                    crate::capture::CONFIGURED_BITRATE.store(12_000_000, Ordering::Relaxed);
                    crate::capture::TARGET_BITRATE.store(12_000_000, Ordering::Relaxed);
                    crate::capture::TARGET_GOP.store(120, Ordering::Relaxed);
                    crate::capture::AUTO_BITRATE_ENABLED.store(false, Ordering::Relaxed);
                }

                *ACTIVE_CLIENT.write().unwrap() = Some(remote_addr);
                crate::capture::FORCE_IDR.store(true, Ordering::Relaxed);

                // Echo HELLO handshake back to client
                let _ = socket.send_to(&buf[0..len], remote_addr).await;

                // Send immediate Quality & FPS settings handshake so Android client UI syncs 1:1
                let cur_fps = crate::capture::TARGET_FPS.load(Ordering::Relaxed);
                let cur_bitrate = crate::capture::TARGET_BITRATE.load(Ordering::Relaxed);
                let mut set_pkt = [0u8; 13];
                set_pkt[0..4].copy_from_slice(&MAGIC_BYTES);
                set_pkt[4] = PACKET_SETTINGS;
                set_pkt[5..9].copy_from_slice(&cur_fps.to_le_bytes());
                set_pkt[9..13].copy_from_slice(&cur_bitrate.to_le_bytes());
                let _ = socket.send_to(&set_pkt, remote_addr).await;

                // Send immediate Desktop Resolution handshake
                let w = crate::capture::DESKTOP_WIDTH.load(Ordering::Relaxed);
                let h = crate::capture::DESKTOP_HEIGHT.load(Ordering::Relaxed);
                if w > 0 && h > 0 {
                    let mut res_pkt = Vec::with_capacity(13);
                    res_pkt.extend_from_slice(&MAGIC_BYTES);
                    res_pkt.push(PACKET_RESOLUTION);
                    res_pkt.extend_from_slice(&w.to_le_bytes());
                    res_pkt.extend_from_slice(&h.to_le_bytes());
                    let _ = socket.send_to(&res_pkt, remote_addr).await;
                }
            }
            PACKET_PONG | PACKET_PONG_LEGACY => {
                if len >= 13 {
                    let mut ts_bytes = [0u8; 8];
                    ts_bytes.copy_from_slice(&buf[5..13]);
                    let sent_us = u64::from_le_bytes(ts_bytes);
                    let now_us = start_time.elapsed().as_micros() as u64;
                    if now_us >= sent_us {
                        let rtt_us = now_us - sent_us;
                        let rtt_ms = (rtt_us / 1000) as u32;
                        let old_rtt = SMOOTHED_RTT_MS.load(Ordering::Relaxed);
                        let new_smoothed = if old_rtt == 0 {
                            rtt_ms
                        } else if rtt_ms < old_rtt {
                            (old_rtt * 2 + rtt_ms * 6) / 8
                        } else {
                            (old_rtt * 6 + rtt_ms * 2) / 8
                        };
                        SMOOTHED_RTT_MS.store(new_smoothed, Ordering::Relaxed);

                        let client_rx_bps = if len >= 17 {
                            let mut rx_bytes = [0u8; 4];
                            rx_bytes.copy_from_slice(&buf[13..17]);
                            (u32::from_le_bytes(rx_bytes) as u64) * 8
                        } else {
                            0
                        };

                        let is_ts = is_tailscale_ip(&remote_addr);
                        if is_ts {
                            let auto_enabled = crate::capture::AUTO_BITRATE_ENABLED.load(Ordering::Relaxed);
                            if auto_enabled {
                                let (mut adaptive_bitrate, adaptive_fps, gop_frames) = if new_smoothed < 35 {
                                    (6_500_000, 60, 120)
                                } else if new_smoothed < 75 {
                                    (4_800_000, 60, 90)
                                } else if new_smoothed < 130 {
                                    (3_600_000, 45, 90)
                                } else {
                                    (2_400_000, 30, 60)
                                };

                                if client_rx_bps > 0 && client_rx_bps < (adaptive_bitrate as u64) * 8 / 10 {
                                    adaptive_bitrate = ((client_rx_bps * 9 / 10) as u32).clamp(1_800_000, 7_500_000);
                                }

                                crate::capture::TARGET_BITRATE.store(adaptive_bitrate, Ordering::Relaxed);
                                crate::capture::TARGET_FPS.store(adaptive_fps, Ordering::Relaxed);
                                crate::capture::TARGET_GOP.store(gop_frames, Ordering::Relaxed);
                            }
                        }
                    }
                }
            }
            PACKET_INPUT => {
                crate::input::handle_input_payload(&buf[5..len]);
                if len >= 7 && (buf[5] == 0x10 || buf[5] == 0x11) {
                    let cur_fps = crate::capture::TARGET_FPS.load(Ordering::Relaxed);
                    let cur_bitrate = crate::capture::TARGET_BITRATE.load(Ordering::Relaxed);
                    broadcast_settings(cur_fps, cur_bitrate);
                }
            }
            PACKET_REQUEST_IDR => {
                crate::capture::FORCE_IDR.store(true, Ordering::Relaxed);
                info!("Client requested IDR keyframe via UDP (0x0A)");
            }
            PACKET_SETTINGS => {
                if len >= 13 {
                    let fps = u32::from_le_bytes(buf[5..9].try_into().unwrap());
                    let bitrate = u32::from_le_bytes(buf[9..13].try_into().unwrap());
                    let is_auto = if len >= 14 {
                        buf[13] != 0
                    } else {
                        (bitrate == 4_500_000 && is_tailscale_ip(&remote_addr)) || (bitrate == 8_000_000 && !is_tailscale_ip(&remote_addr))
                    };

                    if fps > 0 {
                        crate::capture::TARGET_FPS.store(fps, Ordering::Relaxed);
                    }
                    if bitrate > 0 {
                        crate::capture::CONFIGURED_BITRATE.store(bitrate, Ordering::Relaxed);
                        crate::capture::TARGET_BITRATE.store(bitrate, Ordering::Relaxed);
                    }
                    crate::capture::AUTO_BITRATE_ENABLED.store(is_auto, Ordering::Relaxed);

                    info!(
                        "Client settings applied: {} FPS, {} bps | Auto Mode: {}",
                        fps, bitrate, is_auto
                    );
                    let cur_fps = crate::capture::TARGET_FPS.load(Ordering::Relaxed);
                    let cur_bitrate = crate::capture::TARGET_BITRATE.load(Ordering::Relaxed);
                    broadcast_settings(cur_fps, cur_bitrate);
                }
            }
            _ => {}
        }
    }
}
