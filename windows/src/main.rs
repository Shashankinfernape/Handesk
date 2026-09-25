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
    tracing_subscriber::fmt::init();
    info!("Starting DirectLink Host (Direct IP Server Mode)...");

    if let Err(e) = network_udp::start_direct_server().await {
        error!("Server crashed: {:?}", e);
    }

    Ok(())
}
