import os

sig_path = 'windows/src/signaling.rs'
with open(sig_path, 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Update struct
content = content.replace("pub ws_tx: Arc<Mutex<SplitSink<WebSocketStream<MaybeTlsStream<TcpStream>>, Message>>>,", "pub ws_tx: Arc<Mutex<SplitSink<WebSocketStream<MaybeTlsStream<TcpStream>>, Message>>>,\n    pub public_ip: Option<String>,\n    pub udp_socket: std::sync::Arc<tokio::net::UdpSocket>,")

# 2. Update connect signature
content = content.replace("pub async fn connect(url: &str) -> Result<Arc<Mutex<Self>>> {", "pub async fn connect(url: &str, public_ip: Option<String>, udp_socket: std::sync::Arc<tokio::net::UdpSocket>) -> Result<Arc<Mutex<Self>>> {")

# 3. Update connect struct instantiation
content = content.replace("active_session: None,", "active_session: None,\n            public_ip,\n            udp_socket,")

# 4. Update Candidate handler
old_candidate = """            SignalMessage::Candidate { sessionId, candidate, serverReflexiveIp, .. } => {
                info!("Received candidate from Android for {}: {}", sessionId, candidate);
            }"""

new_candidate = """            SignalMessage::Candidate { sessionId, candidate, serverReflexiveIp, .. } => {
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
                    for _ in 0..3 {
                        for t in &targets {
                            if let Ok(addr) = t.parse::<std::net::SocketAddr>() {
                                let _ = socket.send_to(&hello_packet, addr).await;
                            }
                        }
                        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
                    }
                });
            }"""
content = content.replace(old_candidate, new_candidate)

# 5. Remove the capture spawn from ClientRequest! (the reason for this whole mess!)
import re
content = re.sub(r'// Start capture \+ encoder, stream binary frames over this WebSocket.*?while let Some\(nalu\) = rx\.recv\(\)\.await \{.*?\}\n\s*\}\n\s*\}\);', '});', content, flags=re.DOTALL)

with open(sig_path, 'w', encoding='utf-8') as f:
    f.write(content)
print("Restored!")
