use anyhow::{Result, Context, anyhow};
use std::time::Duration;
use tokio::sync::mpsc;
use tracing::{info, debug, error};

use windows::core::Interface;
use windows::Win32::Graphics::Direct3D::{D3D_DRIVER_TYPE_HARDWARE, D3D_FEATURE_LEVEL_11_0};
use windows::Win32::Graphics::Direct3D11::{
    D3D11CreateDevice, ID3D11Device, ID3D11DeviceContext, ID3D11Texture2D,
    D3D11_CREATE_DEVICE_BGRA_SUPPORT, D3D11_SDK_VERSION, D3D11_TEXTURE2D_DESC,
    D3D11_USAGE_STAGING, D3D11_CPU_ACCESS_READ, D3D11_MAP_READ, D3D11_MAPPED_SUBRESOURCE,
    D3D11_RESOURCE_MISC_FLAG, D3D11_BIND_FLAG
};
use windows::Win32::Graphics::Dxgi::{
    IDXGIDevice, IDXGIAdapter, IDXGIOutput, IDXGIOutput1, IDXGIOutputDuplication,
    DXGI_OUTDUPL_FRAME_INFO, DXGI_ERROR_WAIT_TIMEOUT,
    IDXGIResource
};
use windows::Win32::Graphics::Dxgi::Common::{DXGI_FORMAT_B8G8R8A8_UNORM, DXGI_SAMPLE_DESC};

pub static TARGET_BITRATE: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(2_000_000);
pub static TARGET_FPS: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(60);

pub async fn start_capture_loop(tx: mpsc::Sender<Vec<u8>>) -> Result<()> {
    info!("Starting DXGI Desktop Duplication capture loop");

    // Get DXGI Factory to enumerate adapters correctly for laptops
    let factory: windows::Win32::Graphics::Dxgi::IDXGIFactory1 = unsafe { windows::Win32::Graphics::Dxgi::CreateDXGIFactory1().context("Failed to create DXGI factory")? };
    
    let mut best_device: Option<ID3D11Device> = None;
    let mut best_context: Option<ID3D11DeviceContext> = None;
    let mut best_output: Option<windows::Win32::Graphics::Dxgi::IDXGIOutput1> = None;
    let mut best_duplication: Option<windows::Win32::Graphics::Dxgi::IDXGIOutputDuplication> = None;
    
    // Bruteforce search: DXGI Desktop Duplication on Laptops (Optimus) returns E_ACCESSDENIED 
    // if you try to capture the desktop using the dGPU instead of the iGPU.
    // We must try EVERY GPU and EVERY monitor until one successfully returns DuplicateOutput.
    'outer: for i in 0..10 {
        if let Ok(adapter) = unsafe { factory.EnumAdapters(i) } {
            for j in 0..5 {
                if let Ok(output) = unsafe { adapter.EnumOutputs(j) } {
                    if let Ok(output1) = output.cast::<windows::Win32::Graphics::Dxgi::IDXGIOutput1>() {
                        
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
                        
                        if hr.is_ok() {
                            let d3d_device = dev.unwrap();
                            let d3d_context = ctx.unwrap();
                            if let Ok(dxgi_device) = d3d_device.cast::<IDXGIDevice>() {
                                // THE ULTIMATE TEST: Does it let us duplicate?
                                if let Ok(duplication) = unsafe { output1.DuplicateOutput(&dxgi_device) } {
                                    info!("SUCCESS! Found correct GPU (Adapter {}) and Monitor ({}) for Desktop Duplication!", i, j);
                                    best_device = Some(d3d_device);
                                    best_context = Some(d3d_context);
                                    best_output = Some(output1);
                                    best_duplication = Some(duplication);
                                    break 'outer;
                                } else {
                                    info!("GPU {} Monitor {} exists, but DuplicateOutput returned Access Denied/Unsupported.", i, j);
                                }
                            }
                        }
                    }
                } else {
                    break; // No more outputs on this adapter
                }
            }
        } else {
            break; // No more adapters
        }
    }
    
    if best_duplication.is_none() {
        error!("Failed to duplicate output! Error: E_ACCESSDENIED. Falling back to test pattern generator.");
        let width = 600;
        let height = 900;
        let mut encoder = crate::encoder::MFEncoder::new(width, height)?;
        let mut bgra_buffer = vec![255u8; (width * height * 4) as usize];
        
        let mut frame_count: u32 = 0;
        loop {
            let color = (frame_count % 255) as u8;
            for i in (0..bgra_buffer.len()).step_by(4) {
                bgra_buffer[i] = color;      // B
                bgra_buffer[i+1] = color;    // G
                bgra_buffer[i+2] = color;    // R
                bgra_buffer[i+3] = 255;      // A
            }
            
            match encoder.encode_frame(&bgra_buffer) {
                Ok(nalu) => {
                    if !nalu.is_empty() {
                        if tx.send(nalu).await.is_err() {
                            break;
                        }
                    }
                }
                Err(e) => error!("Test pattern error: {}", e),
            }
            frame_count += 1;
            tokio::time::sleep(std::time::Duration::from_millis(33)).await;
        }
        return Ok(());
    }
    
    let duplication = best_duplication.unwrap();
    let d3d_device = best_device.unwrap();
    let d3d_context = best_context.unwrap();
    let output1 = best_output.unwrap();

    // Get monitor resolution
    let desc = unsafe { output1.GetDesc()? };
    let width = (desc.DesktopCoordinates.right - desc.DesktopCoordinates.left) as u32;
    let height = (desc.DesktopCoordinates.bottom - desc.DesktopCoordinates.top) as u32;
    info!("DXGI Capture Target: {}x{}", width, height);

    // Create Staging Texture (to read back to CPU for software encoding fallback)
    let staging_desc = D3D11_TEXTURE2D_DESC {
        Width: width,
        Height: height,
        MipLevels: 1,
        ArraySize: 1,
        Format: DXGI_FORMAT_B8G8R8A8_UNORM,
        SampleDesc: DXGI_SAMPLE_DESC {
            Count: 1,
            Quality: 0,
        },
        Usage: D3D11_USAGE_STAGING,
        BindFlags: 0,
        CPUAccessFlags: D3D11_CPU_ACCESS_READ.0 as u32,
        MiscFlags: 0,
    };

    let mut staging_texture_ptr: Option<ID3D11Texture2D> = None;
    unsafe {
        d3d_device.CreateTexture2D(&staging_desc, None, Some(&mut staging_texture_ptr))?;
    }
    let staging_texture = staging_texture_ptr.unwrap();

    let mut encoder = crate::encoder::MFEncoder::new(width, height)?;
    let mut bgra_buffer = vec![0u8; (width * height * 4) as usize];

    let mut current_bitrate = TARGET_BITRATE.load(std::sync::atomic::Ordering::Relaxed);
    
    let mut frame_start = tokio::time::Instant::now();
    loop {
        let new_bitrate = TARGET_BITRATE.load(std::sync::atomic::Ordering::Relaxed);
        if new_bitrate != current_bitrate {
            current_bitrate = new_bitrate;
            info!("Dynamic Bitrate Change: {} bps", current_bitrate);
            encoder.set_bitrate(current_bitrate);
        }

        let mut frame_info = DXGI_OUTDUPL_FRAME_INFO::default();
        let mut desktop_resource: Option<IDXGIResource> = None;
        
        // Wait up to 16ms for a new frame (60fps). This prevents CPU spinning on idle screens.
        let res = unsafe {
            duplication.AcquireNextFrame(16, &mut frame_info, &mut desktop_resource)
        };

        match res {
            Ok(_) => {
                if let Some(resource) = desktop_resource {
                    let frame_texture: ID3D11Texture2D = resource.cast()?;
                    unsafe {
                        d3d_context.CopyResource(&staging_texture, &frame_texture);
                        let mut mapped = D3D11_MAPPED_SUBRESOURCE::default();
                        d3d_context.Map(&staging_texture, 0, D3D11_MAP_READ, 0, Some(&mut mapped))?;
                        let pitch = mapped.RowPitch as usize;
                        let src_slice = std::slice::from_raw_parts(mapped.pData as *const u8, pitch * height as usize);
                        let row_width = (width * 4) as usize;
                        for y in 0..height as usize {
                            let src_start = y * pitch;
                            let dst_start = y * row_width;
                            bgra_buffer[dst_start..dst_start+row_width].copy_from_slice(&src_slice[src_start..src_start+row_width]);
                        }
                        d3d_context.Unmap(&staging_texture, 0);
                        let _ = duplication.ReleaseFrame();
                    }
                } else {
                    unsafe { let _ = duplication.ReleaseFrame(); }
                    continue; // No actual pixel data, skip encoding
                }
            }
            Err(e) => {
                if e.code() != DXGI_ERROR_WAIT_TIMEOUT {
                    error!("DXGI AcquireNextFrame failed: {:?}", e);
                    break Err(anyhow!("DXGI Error: {:?}", e));
                }
                // Timeout = screen didn't change. Fall through and re-encode the existing buffer.
                // This keeps the stream alive and the Android decoder fed on static screens.
            }
        }

        // 60fps throttle: keeps stream smooth without flooding the network
        let elapsed = frame_start.elapsed();
        if elapsed < Duration::from_micros(16_667) {
            tokio::time::sleep(Duration::from_micros(16_667) - elapsed).await;
        }
        frame_start = tokio::time::Instant::now();

        // Encode and send — CRITICAL: use try_send to NEVER block!
        // If the channel is full (sender is busy), DROP the stale frame instantly.
        // The next fresh frame will arrive in 16ms. This eliminates "stuck/delayed" latency buildup.
        match encoder.encode_frame(&bgra_buffer) {
            Ok(nalu) => {
                if !nalu.is_empty() {
                    info!("Encoded frame size: {} bytes. Sending...", nalu.len());
                    // MUST use blocking send — H.265 P-frames reference the previous frame.
                    // Dropping ANY frame corrupts the entire stream until the next keyframe.
                    // Channel size 1 in network_udp.rs limits backlog to max 1 frame (16ms).
                    if tx.send(nalu).await.is_err() {
                        info!("Capture loop shutting down (receiver dropped)");
                        break Ok(());
                    }
                } else {
                    debug!("Encoder returned empty frame (needs more input).");
                }
            }
            Err(e) => error!("Encoder error: {}", e),
        }
    }
}
