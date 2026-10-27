#![windows_subsystem = "windows"]

use anyhow::Result;
use tracing::{error, info};

mod audio;
mod capture;
mod encoder;
mod input;
mod network;
mod network_kcp;
mod network_tcp;
mod network_udp;
mod signaling;
mod transport;

fn main() -> Result<()> {
    if let Ok(file) = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open("C:\\Users\\user\\Desktop\\Handesk-Windows-Newest\\handesk.log")
    {
        let _ = tracing_subscriber::fmt()
            .with_max_level(tracing::Level::INFO)
            .with_writer(std::sync::Arc::new(file))
            .try_init();
    } else {
        let _ = tracing_subscriber::fmt().with_max_level(tracing::Level::INFO).try_init();
    }

    std::panic::set_hook(Box::new(|info| {
        error!("CRITICAL PANIC OCCURRED: {}", info);
    }));

    let args: Vec<String> = std::env::args().collect();
    let is_headless = args.iter().any(|a| a == "--headless" || a == "-h" || a == "--daemon");

    unsafe {
        let _ = windows::Win32::System::Threading::SetPriorityClass(
            windows::Win32::System::Threading::GetCurrentProcess(),
            windows::Win32::System::Threading::HIGH_PRIORITY_CLASS,
        );
        windows::Win32::Media::timeBeginPeriod(1);
    }

    capture::load_saved_settings();

    // If not headless, launch the official Flutter UI (Handesk.exe) if it exists
    if !is_headless {
        if let Ok(current_exe) = std::env::current_exe() {
            if let Some(parent) = current_exe.parent() {
                let flutter_ui = parent.join("Handesk.exe");
                if flutter_ui.exists() {
                    info!("Launching Handesk UI: {:?}", flutter_ui);
                    let _ = std::process::Command::new(flutter_ui).spawn();
                    return Ok(());
                }
            }
        }
    }

    info!("========================================");
    info!(" Handesk Host: Headless Background Mode");
    info!(" 0% CPU & Low RAM Idle Standby Active");
    info!("========================================");

    let rt = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .unwrap();

    rt.block_on(async {
        if let Err(e) = network_udp::start_udp_server().await {
            error!("Server crashed: {:?}", e);
        }
    });

    Ok(())
}
