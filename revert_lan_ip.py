import os

sig_path = 'windows/src/signaling.rs'
with open(sig_path, 'r', encoding='utf-8') as f:
    content = f.read()

target = """candidate: {
                                  let local_ip = std::net::UdpSocket::bind("0.0.0.0:0")
                                      .and_then(|s| { s.connect("8.8.8.8:53")?; s.local_addr() })
                                      .map(|a| a.ip().to_string())
                                      .unwrap_or_else(|_| "0.0.0.0".to_string());
                                  format!("udp:{}", local_ip)
                              },"""
replacement = 'candidate: "udp:0.0.0.0".to_string(),'

content = content.replace(target, replacement)
with open(sig_path, 'w', encoding='utf-8') as f:
    f.write(content)
print("LAN bypass removed, pure internet mode active!")
