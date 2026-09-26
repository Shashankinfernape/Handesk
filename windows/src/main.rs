use anyhow::Result;
use tracing::{info, error};

mod capture;
mod encoder;
mod network;
mod signaling;
mod transport;
mod input;
mod network_udp;

#[tokio::main]
async fn main() -> Result<()> {
    // QUICK WIN: Boost Windows process priority to REALTIME so background OS tasks never stall the UDP stream
    unsafe {
        windows::Win32::System::Threading::SetPriorityClass(
            windows::Win32::System::Threading::GetCurrentProcess(),
            windows::Win32::System::Threading::REALTIME_PRIORITY_CLASS
        );
        windows::Win32::Media::timeBeginPeriod(1);
    }

    tracing_subscriber::fmt::init();
    info!("Starting DirectLink Host (Direct IP Server Mode)...");

    let socket = std::sync::Arc::new(tokio::net::UdpSocket::bind("0.0.0.0:21118").await?);
    if let Err(e) = network_udp::start_direct_server(socket).await {
        error!("Server crashed: {:?}", e);
    }

    Ok(())
}


