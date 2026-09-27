use tokio::net::UdpSocket;
use anyhow::{Result, bail};
use std::time::Duration;
use rand::Rng;
use std::net::SocketAddr;

/// Get IPv4 public address (existing logic, forced IPv4 literal STUN server)
pub async fn get_public_address(local_socket: &UdpSocket) -> Result<SocketAddr> {
    let stun_server = "stun.l.google.com:19302";

    let mut req = [0u8; 20];
    req[0] = 0x00; req[1] = 0x01;
    req[4] = 0x21; req[5] = 0x12; req[6] = 0xA4; req[7] = 0x42;
    rand::thread_rng().fill(&mut req[8..20]);

    local_socket.send_to(&req, stun_server).await?;

    let mut buf = [0u8; 1024];
    let (len, _) = tokio::time::timeout(Duration::from_secs(3), local_socket.recv_from(&mut buf)).await??;

    if len < 20 { bail!("Invalid STUN response length"); }
    
    parse_mapped_address(&buf, len)
}

/// Get IPv6 public address by binding a fresh IPv6 socket and querying STUN
pub async fn get_public_ipv6_address() -> Result<SocketAddr> {
    // Bind a fresh IPv6 UDP socket on any port
    let socket = UdpSocket::bind("[::]:0").await?;
    
    // Google STUN server - stun.l.google.com resolves to IPv6 on IPv6 networks
    let stun_server = "2607:f8b0:4004:c07::7f:19302"; // Fallback literal IPv6 STUN
    
    let mut req = [0u8; 20];
    req[0] = 0x00; req[1] = 0x01;
    req[4] = 0x21; req[5] = 0x12; req[6] = 0xA4; req[7] = 0x42;
    rand::thread_rng().fill(&mut req[8..20]);

    // Try sending to known STUN IPv6 servers
    let stun_servers = [
        "2001:4860:4864:5::7f:19302",  // Google STUN IPv6
        "2607:f8b0:4004:c07::7f:19302", // Google STUN IPv6 alternate
    ];
    
    for server in &stun_servers {
        if let Ok(addr) = server.parse::<SocketAddr>() {
            let _ = socket.send_to(&req, addr).await;
        }
    }
    // Also try hostname resolution which may give IPv6
    let _ = socket.send_to(&req, "stun.l.google.com:19302").await;

    let mut buf = [0u8; 1024];
    let result = tokio::time::timeout(Duration::from_secs(3), socket.recv_from(&mut buf)).await;
    
    match result {
        Ok(Ok((len, _))) if len >= 20 => parse_mapped_address(&buf, len),
        _ => bail!("IPv6 STUN timed out or failed"),
    }
}

fn parse_mapped_address(buf: &[u8], len: usize) -> Result<SocketAddr> {
    let mut i = 20;
    while i < len {
        if i + 4 > len { break; }
        let attr_type = u16::from_be_bytes([buf[i], buf[i+1]]);
        let attr_len = u16::from_be_bytes([buf[i+2], buf[i+3]]) as usize;
        i += 4;
        
        if i + attr_len > len { break; }
        
        if attr_type == 0x0020 || attr_type == 0x0001 {
            let family = buf[i+1];
            if family == 0x01 { // IPv4
                let port = u16::from_be_bytes([buf[i+2], buf[i+3]]);
                let ip_bytes = [buf[i+4], buf[i+5], buf[i+6], buf[i+7]];
                
                if attr_type == 0x0020 {
                    let xor_port = port ^ 0x2112;
                    let ip = std::net::Ipv4Addr::new(
                        ip_bytes[0] ^ 0x21, ip_bytes[1] ^ 0x12, ip_bytes[2] ^ 0xA4, ip_bytes[3] ^ 0x42
                    );
                    return Ok(SocketAddr::V4(std::net::SocketAddrV4::new(ip, xor_port)));
                } else {
                    let ip = std::net::Ipv4Addr::new(ip_bytes[0], ip_bytes[1], ip_bytes[2], ip_bytes[3]);
                    return Ok(SocketAddr::V4(std::net::SocketAddrV4::new(ip, port)));
                }
            } else if family == 0x02 { // IPv6
                let port = u16::from_be_bytes([buf[i+2], buf[i+3]]);
                let mut ip_bytes = [0u8; 16];
                ip_bytes.copy_from_slice(&buf[i+4..i+20]);
                
                if attr_type == 0x0020 {
                    let xor_port = port ^ 0x2112;
                    // XOR with magic cookie + transaction ID
                    let xor_mask = [0x21u8, 0x12, 0xA4, 0x42];
                    for j in 0..4 { ip_bytes[j] ^= xor_mask[j]; }
                    for j in 4..16 { ip_bytes[j] ^= buf[8 + j - 4]; }
                    let ip = std::net::Ipv6Addr::from(ip_bytes);
                    return Ok(SocketAddr::V6(std::net::SocketAddrV6::new(ip, xor_port, 0, 0)));
                } else {
                    let ip = std::net::Ipv6Addr::from(ip_bytes);
                    return Ok(SocketAddr::V6(std::net::SocketAddrV6::new(ip, port, 0, 0)));
                }
            }
        }
        i += attr_len;
        // Align to 4 bytes
        if attr_len % 4 != 0 { i += 4 - (attr_len % 4); }
    }
    bail!("Could not find mapped address in STUN response")
}
