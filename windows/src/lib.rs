use tracing::{info, error};
use std::sync::Mutex;

mod capture;
mod encoder;
mod network;
mod signaling;
mod transport;
mod input;
mod network_udp;
mod network_tcp;
mod audio;

static RUNTIME: Mutex<Option<tokio::runtime::Runtime>> = Mutex::new(None);

#[no_mangle]
pub extern "C" fn start_directlink_backend() {
    let mut rt_lock = RUNTIME.lock().unwrap();
    if rt_lock.is_some() {
        info!("Backend already running.");
        return;
    }

    unsafe {
        let _ = windows::Win32::System::Threading::SetPriorityClass(
            windows::Win32::System::Threading::GetCurrentProcess(),
            windows::Win32::System::Threading::REALTIME_PRIORITY_CLASS
        );
        windows::Win32::Media::timeBeginPeriod(1);
    }

    let rt = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .unwrap();

    rt.spawn(async {
        info!("Starting DirectLink Host (DLL Mode)...");
        let socket = std::sync::Arc::new(tokio::net::UdpSocket::bind("0.0.0.0:21118").await.unwrap());
        if let Err(e) = network_udp::start_direct_server(socket).await {
            error!("Server crashed: {:?}", e);
        }
    });

    *rt_lock = Some(rt);
}

#[no_mangle]
pub extern "C" fn stop_directlink_backend() {
    let mut rt_lock = RUNTIME.lock().unwrap();
    if let Some(rt) = rt_lock.take() {
        info!("Stopping DirectLink Host...");
        rt.shutdown_background();
        info!("Backend stopped successfully.");
    }
}
