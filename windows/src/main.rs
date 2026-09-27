use anyhow::Result;
use tracing::{info, error};

mod capture;
mod encoder;
mod network;
mod signaling;
mod transport;
mod input;
mod network_udp;
mod network_tcp;

#[tokio::main]
async fn main() -> Result<()> {
    unsafe {
        let _ = windows::Win32::System::Threading::SetPriorityClass(
            windows::Win32::System::Threading::GetCurrentProcess(),
            windows::Win32::System::Threading::REALTIME_PRIORITY_CLASS
        );
        windows::Win32::Media::timeBeginPeriod(1);
    }

    tracing_subscriber::fmt::init();
    info!("Starting DirectLink Host (FLAWLESS TCP MODE)...");

    if let Err(e) = network_tcp::start_tcp_server().await {
        error!("Server crashed: {:?}", e);
    }

    Ok(())
}
