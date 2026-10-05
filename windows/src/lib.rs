use std::sync::Mutex;
use tracing::{error, info};

pub mod audio;
pub mod capture;
pub mod encoder;
pub mod input;
mod network;
mod network_tcp;
pub mod network_udp;
mod signaling;
mod transport;

static RUNTIME: Mutex<Option<tokio::runtime::Runtime>> = Mutex::new(None);

#[no_mangle]
pub extern "C" fn start_directlink_backend() {
    init_logger();
    let mut rt_lock = RUNTIME.lock().unwrap();
    if rt_lock.is_some() {
        info!("Backend already running.");
        return;
    }

    unsafe {
        let _ = windows::Win32::System::Threading::SetPriorityClass(
            windows::Win32::System::Threading::GetCurrentProcess(),
            windows::Win32::System::Threading::REALTIME_PRIORITY_CLASS,
        );
        windows::Win32::Media::timeBeginPeriod(1);
    }

    let rt = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .unwrap();

    rt.spawn(async {
        info!("Starting DirectLink Host (DLL Mode)...");
        use socket2::Socket;
        let std_socket = std::net::UdpSocket::bind("0.0.0.0:21118").unwrap();
        std_socket.set_nonblocking(true).unwrap();

        let socket2_sock: Socket = std_socket.into();
        let _ = socket2_sock.set_send_buffer_size(4 * 1024 * 1024);
        let _ = socket2_sock.set_recv_buffer_size(4 * 1024 * 1024);

        let std_socket: std::net::UdpSocket = socket2_sock.into();
        if let Err(e) = network_udp::start_direct_server(std_socket).await {
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

#[no_mangle]
pub extern "C" fn set_quality_settings(fps: u32, bitrate: u32) {
    capture::TARGET_FPS.store(fps, std::sync::atomic::Ordering::Relaxed);
    capture::TARGET_BITRATE.store(bitrate, std::sync::atomic::Ordering::Relaxed);
}

#[no_mangle]
pub extern "C" fn get_current_fps() -> u32 {
    capture::TARGET_FPS.load(std::sync::atomic::Ordering::Relaxed)
}

#[no_mangle]
pub extern "C" fn get_current_bitrate() -> u32 {
    capture::TARGET_BITRATE.load(std::sync::atomic::Ordering::Relaxed)
}

#[no_mangle]
pub extern "C" fn init_logger() {
    let file = std::fs::File::create("C:\\Users\\user\\Desktop\\Handesk-Windows-Newest\\directlink.log").unwrap();
    let _ = tracing_subscriber::fmt()
        .with_max_level(tracing::Level::INFO)
        .with_writer(std::sync::Arc::new(file))
        .try_init();
}

