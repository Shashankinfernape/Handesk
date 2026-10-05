use anyhow::{Result, Context, anyhow};
use std::time::Duration;
use tracing::{info, error};

use windows::core::Interface;
use windows::Win32::Graphics::Direct3D::D3D_FEATURE_LEVEL_11_0;
use windows::Win32::Graphics::Direct3D11::{
    D3D11CreateDevice, ID3D11Device, ID3D11DeviceContext, ID3D11Texture2D, ID3D11Multithread,
    D3D11_CREATE_DEVICE_BGRA_SUPPORT, D3D11_SDK_VERSION, D3D11_TEXTURE2D_DESC,
    D3D11_USAGE_DEFAULT, D3D11_BIND_RENDER_TARGET, D3D11_BIND_SHADER_RESOURCE,
};
use windows::Win32::Graphics::Dxgi::{
    IDXGIDevice, IDXGIOutput1, IDXGIOutputDuplication,
    DXGI_OUTDUPL_FRAME_INFO, DXGI_ERROR_WAIT_TIMEOUT,
    IDXGIResource
};
use windows::Win32::Graphics::Dxgi::Common::{DXGI_FORMAT_B8G8R8A8_UNORM, DXGI_SAMPLE_DESC};

pub static TARGET_BITRATE: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(5_000_000);
pub static TARGET_FPS: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(90);
pub static FORCE_IDR: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

#[link(name = "user32")]
extern "system" {
    fn OpenDesktopW(lpszDesktop: windows::core::PCWSTR, dwFlags: u32, fInherit: i32, dwDesiredAccess: u32) -> isize;
    fn SetThreadDesktop(hDesktop: isize) -> i32;
}

pub fn start_capture_loop() -> Result<()> {
    info!("Starting DXGI Desktop Duplication capture loop");

    // CRITICAL: Attach this worker thread to the active user's 'Default' desktop.
    // Without this, DuplicateOutput returns E_ACCESSDENIED (0x80070005) when called
    // from background threads, services, or sub-processes!
    unsafe {
        let desk = OpenDesktopW(windows::core::w!("Default"), 0, 0, 0x01FF);
        if desk != 0 {
            let res = SetThreadDesktop(desk);
            info!("Attached capture thread to 'Default' desktop (status: {})", res);
        } else {
            info!("OpenDesktopW returned 0, continuing with process desktop");
        }
    }

    // Get DXGI Factory to enumerate adapters correctly
    let factory: windows::Win32::Graphics::Dxgi::IDXGIFactory1 = unsafe {
        windows::Win32::Graphics::Dxgi::CreateDXGIFactory1().context("Failed to create DXGI factory")?
    };

    let mut best_device: Option<ID3D11Device> = None;
    let mut best_context: Option<ID3D11DeviceContext> = None;
    let mut best_output: Option<IDXGIOutput1> = None;
    let mut best_duplication: Option<IDXGIOutputDuplication> = None;

    'outer: for i in 0..4 {
        if let Ok(adapter) = unsafe { factory.EnumAdapters(i) } {
            let mut dev: Option<ID3D11Device> = None;
            let mut ctx: Option<ID3D11DeviceContext> = None;
            let hr = unsafe {
                D3D11CreateDevice(
                    &adapter,
                    windows::Win32::Graphics::Direct3D::D3D_DRIVER_TYPE_UNKNOWN,
                    None,
                    D3D11_CREATE_DEVICE_BGRA_SUPPORT,
                    Some(&[D3D_FEATURE_LEVEL_11_0]),
                    D3D11_SDK_VERSION,
                    Some(&mut dev),
                    None,
                    Some(&mut ctx),
                )
            };
            if hr.is_err() { continue; }
            let d3d_device = dev.unwrap();
            let d3d_context = ctx.unwrap();

            if let Ok(multithread) = d3d_device.cast::<ID3D11Multithread>() {
                unsafe { let _ = multithread.SetMultithreadProtected(true); }
                info!("Enabled ID3D11Multithread protection on D3D11 device");
            }

            let dxgi_device: IDXGIDevice = match d3d_device.cast() {
                Ok(d) => d,
                Err(_) => continue,
            };

            for j in 0..4 {
                if let Ok(output) = unsafe { adapter.EnumOutputs(j) } {
                    if let Ok(output1) = output.cast::<IDXGIOutput1>() {
                        for attempt in 1..=5 {
                            // Ensure thread is attached to interactive desktop before each attempt
                            unsafe {
                                let desk = OpenDesktopW(windows::core::w!("Default"), 0, 0, 0x01FF);
                                if desk != 0 {
                                    let _ = SetThreadDesktop(desk);
                                }
                            }
                            let res = unsafe { output1.DuplicateOutput(&dxgi_device) };
                            match res {
                                Ok(duplication) => {
                                    info!("SUCCESS! Found GPU Adapter {} Monitor {} on attempt {}!", i, j, attempt);
                                    best_device = Some(d3d_device);
                                    best_context = Some(d3d_context);
                                    best_output = Some(output1);
                                    best_duplication = Some(duplication);
                                    break 'outer;
                                }
                                Err(e) => {
                                    info!("DuplicateOutput Adapter {} Monitor {} attempt {}: {:?}", i, j, attempt, e);
                                    if attempt < 5 {
                                        std::thread::sleep(std::time::Duration::from_millis(300));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if best_duplication.is_none() {
        error!("Failed to duplicate output after retries! Desktop may be locked or busy.");
        return Err(anyhow!("Desktop duplication unavailable"));
    }

    let duplication = best_duplication.unwrap();
    let d3d_device = best_device.unwrap();
    let d3d_context = best_context.unwrap();
    let output1 = best_output.unwrap();

    // Get monitor resolution
    let desc = unsafe { output1.GetDesc()? };
    let width = (desc.DesktopCoordinates.right - desc.DesktopCoordinates.left) as u32;
    let height = (desc.DesktopCoordinates.bottom - desc.DesktopCoordinates.top) as u32;
    info!("DXGI Desktop Duplication initialized: {}x{}", width, height);

    // Create GPU Texture for Zero-Copy pipeline (direct surface binding to MFT)
    let gpu_desc = D3D11_TEXTURE2D_DESC {
        Width: width,
        Height: height,
        MipLevels: 1,
        ArraySize: 1,
        Format: DXGI_FORMAT_B8G8R8A8_UNORM,
        SampleDesc: DXGI_SAMPLE_DESC { Count: 1, Quality: 0 },
        Usage: D3D11_USAGE_DEFAULT,
        BindFlags: (D3D11_BIND_RENDER_TARGET.0 | D3D11_BIND_SHADER_RESOURCE.0) as u32,
        CPUAccessFlags: 0,
        MiscFlags: 0,
    };

    let mut gpu_textures = Vec::new();
    for _ in 0..3 {
        let mut ptr: Option<ID3D11Texture2D> = None;
        unsafe { d3d_device.CreateTexture2D(&gpu_desc, None, Some(&mut ptr))?; }
        gpu_textures.push(ptr.unwrap());
    }
    let mut pool_idx = 0;

    let mut encoder = crate::encoder::MFEncoder::new(width, height, &d3d_device)?;

    let mut current_bitrate = TARGET_BITRATE.load(std::sync::atomic::Ordering::Relaxed);
    encoder.set_bitrate(current_bitrate);

    let mut last_idr = std::time::Instant::now();
    let mut last_sent = std::time::Instant::now();

    loop {

        let new_bitrate = TARGET_BITRATE.load(std::sync::atomic::Ordering::Relaxed);
        if new_bitrate != current_bitrate {
            current_bitrate = new_bitrate;
            info!("Dynamic Bitrate Change: {} bps", current_bitrate);
            encoder.set_bitrate(current_bitrate);
        }

        // Handle IDR request (Throttle to max 4 per second to prevent network storms, but never drop)
        if FORCE_IDR.swap(false, std::sync::atomic::Ordering::Relaxed) {
            if last_idr.elapsed() >= Duration::from_millis(250) {
                info!("Triggering instantaneous IDR keyframe");
                encoder.force_idr();
                last_idr = std::time::Instant::now();
            } else {
                // Re-queue so it triggers on next frame after throttle period
                FORCE_IDR.store(true, std::sync::atomic::Ordering::Relaxed);
            }
        }

        // FPS pacing: User-configurable (default 90 FPS)
        let fps = TARGET_FPS.load(std::sync::atomic::Ordering::Relaxed).clamp(15, 144) as u32;
        let timeout_ms = (1000 / fps).max(1);

        let mut frame_info = DXGI_OUTDUPL_FRAME_INFO::default();
        let mut desktop_resource: Option<IDXGIResource> = None;

        let mut got_new_frame = false;

        let mut current_texture: Option<&ID3D11Texture2D> = None;

        let res = unsafe {
            duplication.AcquireNextFrame(timeout_ms, &mut frame_info, &mut desktop_resource)
        };

        match res {
            Ok(_) => {
                if let Some(resource) = desktop_resource {
                    let frame_texture: ID3D11Texture2D = match resource.cast() {
                        Ok(t) => t,
                        Err(e) => {
                            error!("Failed to cast resource to ID3D11Texture2D: {:?}", e);
                            unsafe { let _ = duplication.ReleaseFrame(); }
                            continue;
                        }
                    };
                    let target_tex = &gpu_textures[pool_idx];
                    unsafe {
                        d3d_context.CopyResource(target_tex, &frame_texture);
                        let _ = duplication.ReleaseFrame();
                    }
                    current_texture = Some(target_tex);
                    pool_idx = (pool_idx + 1) % gpu_textures.len();
                    got_new_frame = true;
                } else {
                    unsafe { let _ = duplication.ReleaseFrame(); }
                }
            }
            Err(e) => {
                if e.code() != DXGI_ERROR_WAIT_TIMEOUT {
                    error!("DXGI AcquireNextFrame failed: {:?} - restarting capture", e);
                    break Err(anyhow!("DXGI Error: {:?}", e));
                }
            }
        }

        // Send keepalive if screen static
        if !got_new_frame && last_sent.elapsed() >= Duration::from_millis(150) {
            got_new_frame = true; // Feed the old texture again
            let keepalive_idx = if pool_idx == 0 { gpu_textures.len() - 1 } else { pool_idx - 1 };
            current_texture = Some(&gpu_textures[keepalive_idx]);
        }

        let texture_to_feed = if got_new_frame {
            last_sent = std::time::Instant::now();
            current_texture
        } else {
            None
        };

        // Encode on GPU (Zero-Copy)
        match encoder.encode_frame_gpu(texture_to_feed) {
            Ok(nalu) => {
                if !nalu.is_empty() {
                    if nalu.len() > 40_000 {
                        info!("Large frame: {} bytes", nalu.len());
                    }
                    crate::network_udp::broadcast_video_frame(&nalu);
                }
            }
            Err(e) => {
                error!("GPU Encoder error: {}", e);
            }
        }
    }
}
