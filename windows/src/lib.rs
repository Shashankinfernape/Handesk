use std::sync::Mutex;
use tracing::{error, info};

pub mod audio;
pub mod capture;
pub mod encoder;
pub mod input;
mod network;
mod network_tcp;
pub mod network_udp;
pub mod network_kcp;
mod signaling;
mod transport;

static RUNTIME: Mutex<Option<tokio::runtime::Runtime>> = Mutex::new(None);

#[no_mangle]
pub extern "C" fn start_handesk_backend() {
    start_directlink_backend();
}

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
        info!("Starting Handesk Host (Pure UDP Mode)...");
        if let Err(e) = network_udp::start_udp_server().await {
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
    capture::CONFIGURED_BITRATE.store(bitrate, std::sync::atomic::Ordering::Relaxed);
    capture::TARGET_BITRATE.store(bitrate, std::sync::atomic::Ordering::Relaxed);
    capture::AUTO_BITRATE_ENABLED.store(false, std::sync::atomic::Ordering::Relaxed);
    network_udp::broadcast_settings(fps, bitrate);
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
pub extern "C" fn set_show_cursor_on_stream(show: bool) {
    capture::SHOW_CURSOR_ON_STREAM.store(show, std::sync::atomic::Ordering::Relaxed);
}

#[no_mangle]
pub extern "C" fn get_show_cursor_on_stream() -> bool {
    capture::SHOW_CURSOR_ON_STREAM.load(std::sync::atomic::Ordering::Relaxed)
}

#[no_mangle]
pub extern "C" fn init_logger() {
    let file = std::fs::File::create("C:\\Users\\user\\Desktop\\Handesk-Windows-Newest\\directlink.log").unwrap();
    let _ = tracing_subscriber::fmt()
        .with_max_level(tracing::Level::INFO)
        .with_writer(std::sync::Arc::new(file))
        .try_init();
}

#[no_mangle]
pub extern "C" fn get_detected_lan_ip(buffer: *mut std::os::raw::c_char, max_len: u32) -> i32 {
    let ip = network_udp::detect_lan_ip();
    copy_string_to_c_buffer(&ip, buffer, max_len)
}

#[no_mangle]
pub extern "C" fn get_detected_tailscale_ip(buffer: *mut std::os::raw::c_char, max_len: u32) -> i32 {
    let ip = network_udp::detect_tailscale_ip();
    copy_string_to_c_buffer(&ip, buffer, max_len)
}

fn copy_string_to_c_buffer(s: &str, buffer: *mut std::os::raw::c_char, max_len: u32) -> i32 {
    if buffer.is_null() || max_len == 0 {
        return -1;
    }
    let bytes = s.as_bytes();
    let to_copy = bytes.len().min((max_len - 1) as usize);
    unsafe {
        std::ptr::copy_nonoverlapping(bytes.as_ptr(), buffer as *mut u8, to_copy);
        *buffer.add(to_copy) = 0;
    }
    to_copy as i32
}


