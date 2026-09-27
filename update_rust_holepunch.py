import os

file_path = 'windows/src/signaling.rs'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Add socket field to struct
content = content.replace(
    'pub public_ip: Option<String>,\n}',
    'pub public_ip: Option<String>,\n    pub udp_socket: std::sync::Arc<tokio::net::UdpSocket>,\n}'
)

# Update connect signature to take socket
content = content.replace(
    'pub async fn connect(url: &str, public_ip: Option<String>) -> Result<Arc<Mutex<Self>>> {',
    'pub async fn connect(url: &str, public_ip: Option<String>, udp_socket: std::sync::Arc<tokio::net::UdpSocket>) -> Result<Arc<Mutex<Self>>> {'
)

content = content.replace(
    '''            capture_task: None,
            public_ip,
        }));''',
    '''            capture_task: None,
            public_ip,
            udp_socket,
        }));'''
)

# Blast packet back in handle_signal
target = '''            SignalMessage::Candidate { sessionId, candidate, serverReflexiveIp, .. } => {
                info!("Received candidate from Android for {}: {}", sessionId, candidate);
                if let Some(ip) = serverReflexiveIp {
                    info!("Android public IP: {}", ip);
                }
            }'''

replacement = '''            SignalMessage::Candidate { sessionId, candidate, serverReflexiveIp, .. } => {
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
            }'''

content = content.replace(target, replacement)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)


# Update main.rs to pass the socket
main_path = 'windows/src/main.rs'
with open(main_path, 'r', encoding='utf-8') as f:
    main_content = f.read()

main_content = main_content.replace(
    'let _signaling_client = signaling::SignalingClient::connect("ws://localhost:3000", public_ip).await?;',
    'let _signaling_client = signaling::SignalingClient::connect("ws://localhost:3000", public_ip, udp_socket.clone()).await?;'
)

with open(main_path, 'w', encoding='utf-8') as f:
    f.write(main_content)

print('Done!')
