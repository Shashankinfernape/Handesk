use anyhow::Result;
use bytes::Bytes;
use kcp::{Kcp, KcpResult};
use std::io::Write;
use std::net::SocketAddr;
use std::sync::{Arc, Mutex};
use tokio::net::UdpSocket;
use tokio::sync::mpsc;
use tracing::{error, info};
use std::time::Instant;

pub const MAGIC_BYTES: [u8; 4] = [b'D', b'L', b'P', b'1'];

pub static SMOOTHED_RTT_MS: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);

lazy_static::lazy_static! {
    pub static ref ACTIVE_CLIENT_ADDR: std::sync::RwLock<Option<SocketAddr>> = std::sync::RwLock::new(None);
    pub static ref ACTIVE_VIDEO_TX: std::sync::RwLock<Option<mpsc::Sender<Bytes>>> = std::sync::RwLock::new(None);
    pub static ref ACTIVE_AUDIO_TX: std::sync::RwLock<Option<mpsc::Sender<Bytes>>> = std::sync::RwLock::new(None);
}

pub fn is_tailscale_ip(addr: &SocketAddr) -> bool {
    match addr.ip() {
        std::net::IpAddr::V4(ipv4) => ipv4.octets()[0] == 100,
        std::net::IpAddr::V6(ipv6) => {
            let segs = ipv6.segments();
            segs[0] == 0xfd7a && segs[1] == 0x115c && segs[2] == 0xa1e0
        }
    }
}

struct UdpOutput {
    socket: Arc<std::net::UdpSocket>,
    addr: SocketAddr,
}

impl Write for UdpOutput {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        self.socket.send_to(buf, self.addr)
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

pub fn broadcast_video_frame(nalu: &[u8]) {
    let tx_opt = ACTIVE_VIDEO_TX.read().unwrap().clone();
    if let Some(tx) = tx_opt {
        let mut msg = Vec::with_capacity(5 + nalu.len());
        msg.extend_from_slice(&MAGIC_BYTES);
        msg.push(0x06); // Video
        msg.extend_from_slice(nalu);
        let _ = tx.blocking_send(Bytes::from(msg));
    }
}

pub fn broadcast_audio_frame(opus_data: &[u8]) {
    let tx_opt = ACTIVE_AUDIO_TX.read().unwrap().clone();
    if let Some(tx) = tx_opt {
        let _ = tx.blocking_send(Bytes::from(opus_data.to_vec()));
    }
}

pub async fn start_kcp_server() -> Result<()> {
    let socket = std::net::UdpSocket::bind("0.0.0.0:21118")?;
    socket.set_nonblocking(true)?;
    let socket = Arc::new(socket);
    let mut buf = [0u8; 65536];

    info!("KCP Server listening on 0.0.0.0:21118");

    // RustDesk uses convention 0x11223344 for KCP conv ID
    let mut kcp: Option<Kcp<UdpOutput>> = None;
    let mut client_addr: Option<SocketAddr> = None;
    let mut last_update = Instant::now();
    let mut last_ping = Instant::now();
    let mut last_telemetry_log = Instant::now();
    let start_time = Instant::now();
    let mut rx_stream_buffer = Vec::new();

    let (video_tx, mut video_rx) = mpsc::channel::<Bytes>(1);
    *ACTIVE_VIDEO_TX.write().unwrap() = Some(video_tx);

    let (audio_tx, mut audio_rx) = mpsc::channel::<Bytes>(10);
    *ACTIVE_AUDIO_TX.write().unwrap() = Some(audio_tx.clone());

    std::thread::Builder::new()
        .name("os_capture_thread".into())
        .spawn(move || {
            loop {
                info!("Starting dedicated OS capture thread");
                if let Err(e) = crate::capture::start_capture_loop() {
                    error!("Capture loop exited: {:?}. Restarting in 500ms...", e);
                }
                std::thread::sleep(std::time::Duration::from_millis(500));
            }
        })
        .unwrap();

    let audio_tx_clone = audio_tx.clone();
    tokio::spawn(async move {
        loop {
            info!("Starting audio capture loop");
            let (tx, mut rx) = mpsc::channel(100);
            
            // Forward audio packets to ACTIVE_AUDIO_TX
            let tx_clone = audio_tx_clone.clone();
            tokio::spawn(async move {
                while let Some(audio_bytes) = rx.recv().await {
                    let _ = tx_clone.send(Bytes::from(audio_bytes)).await;
                }
            });

            if let Err(e) = crate::audio::start_audio_loop(tx).await {
                error!("Audio loop exited: {:?}. Restarting in 1000ms...", e);
            }
            tokio::time::sleep(tokio::time::Duration::from_millis(1000)).await;
        }
    });

    let mut last_sent_w = 0u32;
    let mut last_sent_h = 0u32;

    loop {
        let now_ms = start_time.elapsed().as_millis() as u32;

        if let Some(ref mut k) = kcp {
            let _ = k.update(now_ms);
            let wait_snd = k.wait_snd();

            // Dynamic desktop resolution sync: notifies Android whenever Windows resolution or aspect ratio changes (16:9, 16:10, 4:3, etc.)
            let w = crate::capture::DESKTOP_WIDTH.load(std::sync::atomic::Ordering::Relaxed);
            let h = crate::capture::DESKTOP_HEIGHT.load(std::sync::atomic::Ordering::Relaxed);
            if (w != last_sent_w || h != last_sent_h) && w > 0 && h > 0 {
                last_sent_w = w;
                last_sent_h = h;
                info!("Sending Desktop Resolution Handshake to client: {}x{}", w, h);
                let mut res_pkt = Vec::with_capacity(13);
                res_pkt.extend_from_slice(&MAGIC_BYTES);
                res_pkt.push(0x0D); // Resolution packet
                res_pkt.extend_from_slice(&w.to_le_bytes());
                res_pkt.extend_from_slice(&h.to_le_bytes());
                let len_bytes = (res_pkt.len() as u32).to_le_bytes();
                let _ = k.send(&len_bytes);
                let _ = k.send(&res_pkt);
                let _ = k.flush();
            }

            // Periodic 1-second telemetry logging: gives real empirical queue and latency stats
            if last_telemetry_log.elapsed() >= std::time::Duration::from_millis(1000) {
                last_telemetry_log = Instant::now();
                let cur_rtt = SMOOTHED_RTT_MS.load(std::sync::atomic::Ordering::Relaxed);
                let cur_bitrate = crate::capture::TARGET_BITRATE.load(std::sync::atomic::Ordering::Relaxed);
                let cur_fps = crate::capture::TARGET_FPS.load(std::sync::atomic::Ordering::Relaxed);
                let auto = crate::capture::AUTO_BITRATE_ENABLED.load(std::sync::atomic::Ordering::Relaxed);
                let is_ts = if let Some(ref c_addr) = client_addr {
                    is_tailscale_ip(c_addr)
                } else {
                    false
                };
                info!(
                    "[HOST TELEMETRY] Profile: {} | RTT: {}ms | KCP wait_snd: {} pkts | Target Bitrate: {:.2} Mbps | Target FPS: {} | AutoMode: {}",
                    if is_ts { "TAILSCALE (WAN limits)" } else { "LOCAL LAN (Unconstrained)" },
                    cur_rtt,
                    wait_snd,
                    cur_bitrate as f64 / 1_000_000.0,
                    cur_fps,
                    auto
                );
            }

            // Periodic PING for Tailscale RTT and path health monitoring (200ms interval for 5x faster latency tracking)
            if last_ping.elapsed() >= std::time::Duration::from_millis(200) {
                last_ping = Instant::now();
                let now_us = start_time.elapsed().as_micros() as u64;
                let mut ping_pkt = Vec::with_capacity(13);
                ping_pkt.extend_from_slice(&MAGIC_BYTES);
                ping_pkt.push(0x0B); // PING
                ping_pkt.extend_from_slice(&now_us.to_le_bytes());
                let len_bytes = (ping_pkt.len() as u32).to_le_bytes();
                let _ = k.send(&len_bytes);
                let _ = k.send(&ping_pkt);
                let _ = k.flush();
            }
        }

        // Receive UDP packets into KCP
        while let Ok((len, addr)) = socket.recv_from(&mut buf) {
            let payload = &buf[..len];

            // If it's a HELLO packet directly over UDP, initialize KCP
            if len == 5 && &payload[0..4] == &MAGIC_BYTES && payload[4] == 0x01 {
                info!("Received UDP HELLO from {addr}. Initializing KCP session.");
                *ACTIVE_CLIENT_ADDR.write().unwrap() = Some(addr);
                client_addr = Some(addr);
                crate::capture::FORCE_IDR.store(true, std::sync::atomic::Ordering::Relaxed);
                
                let is_ts = is_tailscale_ip(&addr);
                crate::capture::IS_TAILSCALE_CLIENT.store(is_ts, std::sync::atomic::Ordering::Relaxed);

                let output = UdpOutput {
                    socket: socket.clone(),
                    addr,
                };
                
                let mut new_kcp = Kcp::new_stream(0x11223344, output);
                if is_ts {
                    info!("Client {addr} connected via TAILSCALE WAN (100.x.x.x) - applying WAN profile: 1100 MTU, 5ms nodelay, 2048 wnd");
                    new_kcp.set_nodelay(true, 5, 2, true);
                    new_kcp.set_wndsize(2048, 2048);
                    let _ = new_kcp.set_mtu(1100); // 1100 MTU guarantees zero fragmentation over Tailscale/WireGuard
                } else {
                    info!("Client {addr} connected via LOCAL LAN - REMOVING ALL LIMITATIONS: 1472 MTU, 1ms nodelay, 4096 wnd, unconstrained 50 Mbps, up to 144 FPS");
                    new_kcp.set_nodelay(true, 1, 2, true); // 1ms fastest local dispatch
                    new_kcp.set_wndsize(4096, 4096);       // Full 4096 buffer
                    let _ = new_kcp.set_mtu(1472);         // Full Ethernet MTU (1500 - 20 - 8)

                    // Unlock LAN high-fidelity bitrate and up to 144 FPS immediately
                    let manual_bitrate = crate::capture::CONFIGURED_BITRATE.load(std::sync::atomic::Ordering::Relaxed);
                    crate::capture::TARGET_BITRATE.store(manual_bitrate.max(50_000_000), std::sync::atomic::Ordering::Relaxed);
                    crate::capture::TARGET_FPS.store(144, std::sync::atomic::Ordering::Relaxed);
                    crate::capture::TARGET_GOP.store(240, std::sync::atomic::Ordering::Relaxed);
                }

                // Send native desktop resolution to client so Android decodes pixel-perfect
                let w = crate::capture::DESKTOP_WIDTH.load(std::sync::atomic::Ordering::Relaxed);
                let h = crate::capture::DESKTOP_HEIGHT.load(std::sync::atomic::Ordering::Relaxed);
                let mut res_pkt = Vec::with_capacity(13);
                res_pkt.extend_from_slice(&MAGIC_BYTES);
                res_pkt.push(0x0D); // Resolution packet
                res_pkt.extend_from_slice(&w.to_le_bytes());
                res_pkt.extend_from_slice(&h.to_le_bytes());
                let len_bytes = (res_pkt.len() as u32).to_le_bytes();
                let _ = new_kcp.send(&len_bytes);
                let _ = new_kcp.send(&res_pkt);
                let _ = new_kcp.flush();

                kcp = Some(new_kcp);
                continue;
            }

            if let Some(ref mut k) = kcp {
                if client_addr == Some(addr) {
                    let _ = k.input(payload);
                    
                    // After input, try to recv any complete stream data
                    let mut stream_buf = vec![0u8; 512 * 1024];
                    while let Ok(sz) = k.recv(&mut stream_buf) {
                        rx_stream_buffer.extend_from_slice(&stream_buf[..sz]);
                    }

                    while rx_stream_buffer.len() >= 4 {
                        let mut len_bytes = [0u8; 4];
                        len_bytes.copy_from_slice(&rx_stream_buffer[0..4]);
                        let pkt_len = u32::from_le_bytes(len_bytes) as usize;
                        
                        if rx_stream_buffer.len() >= 4 + pkt_len {
                            let payload = &rx_stream_buffer[4..4 + pkt_len];
                            if pkt_len >= 5 && &payload[0..4] == &MAGIC_BYTES {
                                let pkt_type = payload[4];
                                if pkt_type == 0x07 {
                                    crate::input::handle_input_payload(&payload[5..pkt_len]);
                                } else if pkt_type == 0x0A {
                                    crate::capture::FORCE_IDR.store(true, std::sync::atomic::Ordering::Relaxed);
                                    info!("Client requested IDR via KCP");
                                } else if pkt_type == 0x0C && pkt_len >= 13 {
                                    // PONG received from client
                                    let mut ts_bytes = [0u8; 8];
                                    ts_bytes.copy_from_slice(&payload[5..13]);
                                    let sent_us = u64::from_le_bytes(ts_bytes);
                                    let now_us = start_time.elapsed().as_micros() as u64;
                                    if now_us >= sent_us {
                                        let rtt_us = now_us - sent_us;
                                        let rtt_ms = (rtt_us / 1000) as u32;
                                        let old_rtt = SMOOTHED_RTT_MS.load(std::sync::atomic::Ordering::Relaxed);
                                        let new_smoothed = if old_rtt == 0 {
                                            rtt_ms
                                        } else if rtt_ms < old_rtt {
                                            (old_rtt * 2 + rtt_ms * 6) / 8 // Fast recovery when network stabilizes
                                        } else {
                                            (old_rtt * 6 + rtt_ms * 2) / 8 // Filter out momentary jitter spikes
                                        };
                                        SMOOTHED_RTT_MS.store(new_smoothed, std::sync::atomic::Ordering::Relaxed);

                                        // Read client reported throughput (from bytes 13..17 of PONG)
                                        let client_rx_bps = if pkt_len >= 17 {
                                            let mut rx_bytes = [0u8; 4];
                                            rx_bytes.copy_from_slice(&payload[13..17]);
                                            (u32::from_le_bytes(rx_bytes) as u64) * 8
                                        } else {
                                            0
                                        };

                                        let is_ts = if let Some(ref c_addr) = client_addr {
                                            is_tailscale_ip(c_addr)
                                        } else {
                                            false
                                        };

                                        if is_ts {
                                            // Tailscale WAN: path change detection (e.g. direct -> DERP relay)
                                            if old_rtt > 15 && rtt_ms > old_rtt * 3 {
                                                info!("Tailscale path switch detected (RTT: {}ms -> {}ms), triggering IDR", old_rtt, rtt_ms);
                                                crate::capture::FORCE_IDR.store(true, std::sync::atomic::Ordering::Relaxed);
                                            }

                                            // Only adjust bitrate and FPS on WAN if AUTO mode is enabled!
                                            let auto_enabled = crate::capture::AUTO_BITRATE_ENABLED.load(std::sync::atomic::Ordering::Relaxed);
                                            if auto_enabled {
                                                let (mut adaptive_bitrate, adaptive_fps, gop_frames) = if new_smoothed < 40 {
                                                    (12_000_000, 60, 180)
                                                } else if new_smoothed < 80 {
                                                    (8_000_000, 60, 120)
                                                } else if new_smoothed < 130 {
                                                    (4_500_000, 30, 90)
                                                } else {
                                                    (2_500_000, 30, 60)
                                                };

                                                // Never push more data than the client link has demonstrated it can absorb
                                                if client_rx_bps > 0 && client_rx_bps < (adaptive_bitrate as u64) * 8 / 10 {
                                                    adaptive_bitrate = ((client_rx_bps * 9 / 10) as u32).clamp(1_500_000, 12_000_000);
                                                }

                                                crate::capture::TARGET_BITRATE.store(adaptive_bitrate, std::sync::atomic::Ordering::Relaxed);
                                                crate::capture::TARGET_FPS.store(adaptive_fps, std::sync::atomic::Ordering::Relaxed);
                                                crate::capture::TARGET_GOP.store(gop_frames, std::sync::atomic::Ordering::Relaxed);
                                            }

                                            // Dynamic KCP interval tuning to prevent retransmission storms on high RTT WAN
                                            if new_smoothed > 80 {
                                                k.set_nodelay(true, 10, 2, true);
                                            } else {
                                                k.set_nodelay(true, 5, 2, true);
                                            }
                                        } else {
                                            // LOCAL LAN: NO LIMITATIONS! Maintain maximum unconstrained performance
                                            k.set_nodelay(true, 1, 2, true);
                                        }
                                    }
                                }
                            }
                            // Drain the parsed packet
                            rx_stream_buffer.drain(0..4 + pkt_len);
                        } else {
                            break;
                        }
                    }
                }
            }
        }

        // Send Video Frames via KCP (64KB chunks for LAN max speed, smooth 16KB for Tailscale WAN)
        let is_ts = if let Some(ref c_addr) = client_addr {
            is_tailscale_ip(c_addr)
        } else {
            false
        };
        let chunk_size = if is_ts { 16 * 1024 } else { 64 * 1024 };

        if let Ok(video_bytes) = video_rx.try_recv() {
            if let Some(ref mut k) = kcp {
                let len_bytes = (video_bytes.len() as u32).to_le_bytes();
                let _ = k.send(&len_bytes);
                for chunk in video_bytes.chunks(chunk_size) {
                    let _ = k.send(chunk);
                }
                let _ = k.flush();
            }
        }

        // Send Audio Frames directly via bare UDP (Zero KCP queuing, zero head-of-line blocking behind video!)
        if let Ok(audio_bytes) = audio_rx.try_recv() {
            if let Some(target) = client_addr {
                let mut packet = Vec::with_capacity(5 + audio_bytes.len());
                packet.extend_from_slice(&MAGIC_BYTES);
                packet.push(0x08); // Audio Type
                packet.extend_from_slice(&audio_bytes);
                let _ = socket.send_to(&packet, target);
            }
        }

        tokio::time::sleep(std::time::Duration::from_millis(1)).await;
    }
}
