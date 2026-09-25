use std::net::SocketAddr;
use std::sync::Arc;
use tokio::net::UdpSocket;

#[derive(Debug)]
pub enum TransportType {
    P2P(SocketAddr),
    Relay(SocketAddr),
}

pub struct Transport {
    pub socket: Arc<UdpSocket>,
    pub target: SocketAddr,
    pub transport_type: TransportType,
    pub session_prefix: Option<Vec<u8>>,
}
