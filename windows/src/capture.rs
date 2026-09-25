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

pub async fn start_capture_loop(tx: mpsc::Sender<Vec<u8>>) -> Result<()> {
    info!("Starting DXGI Desktop Duplication capture loop");

    // 1. Create D3D11 Device
    let mut device: Option<ID3D11Device> = None;
    let mut context: Option<ID3D11DeviceContext> = None;
    unsafe {
        D3D11CreateDevice(
            None,
            D3D_DRIVER_TYPE_HARDWARE,
            None,
            D3D11_CREATE_DEVICE_BGRA_SUPPORT,
            Some(&[D3D_FEATURE_LEVEL_11_0]),
            D3D11_SDK_VERSION,
            Some(&mut device),
            None,
            Some(&mut context),
        ).context("Failed to create D3D11 device")?;
    }

    let d3d_device = device.unwrap();
    let d3d_context = context.unwrap();

    // 2. Get DXGI structures
    let dxgi_device: IDXGIDevice = d3d_device.cast()?;
    let adapter: IDXGIAdapter = unsafe { dxgi_device.GetAdapter()? };
    
    // Get primary output (monitor 0)
    let output: IDXGIOutput = unsafe { adapter.EnumOutputs(0)? };
    let output1: IDXGIOutput1 = output.cast()?;

    // 3. Duplicate Output
    let duplication_result = unsafe {
        output1.DuplicateOutput(&dxgi_device)
    };

    if duplication_result.is_err() {
        error!("Failed to duplicate output! Falling back to test pattern generator.");
        let width = 1600;
        let height = 900;
    let mut encoder = crate::encoder::MFEncoder::new(width, height)?;
        let mut bgra_buffer = vec![255u8; (width * height * 4) as usize];
        
        let mut frame_count = 0;
        loop {
            let color = (frame_count % 255) as u8;
            for i in (0..bgra_buffer.len()).step_by(4) {
                bgra_buffer[i] = color;
                bgra_buffer[i+1] = color;
                bgra_buffer[i+2] = color;
                bgra_buffer[i+3] = 255;
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

    let duplication: IDXGIOutputDuplication = duplication_result.unwrap();

    // Get monitor resolution
    let desc = unsafe { output.GetDesc()? };
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

    let mut frame_start = tokio::time::Instant::now();
    loop {
        let mut frame_info = DXGI_OUTDUPL_FRAME_INFO::default();
        let mut desktop_resource: Option<IDXGIResource> = None;
        
        let res = unsafe {
            duplication.AcquireNextFrame(100, &mut frame_info, &mut desktop_resource)
        };

        match res {
            Ok(_) => {
                // If LastPresentTime is 0, the screen didn't update.
                // Send to encoder even if LastPresentTime is 0 so the video stream initializes!
                if let Some(resource) = desktop_resource {
                    let frame_texture: ID3D11Texture2D = resource.cast()?;
                    
                    // Copy to staging texture to read CPU bytes
                    unsafe {
                        d3d_context.CopyResource(&staging_texture, &frame_texture);
                        
                        let mut mapped = D3D11_MAPPED_SUBRESOURCE::default();
                        d3d_context.Map(
                            &staging_texture,
                            0,
                            D3D11_MAP_READ,
                            0,
                            Some(&mut mapped)
                        )?;

                        let pitch = mapped.RowPitch as usize;
                        let src_slice = std::slice::from_raw_parts(mapped.pData as *const u8, pitch * height as usize);
                        
                        // Copy row by row to drop padding if pitch > width * 4
                        let row_width = (width * 4) as usize;
                        for y in 0..height as usize {
                            let src_start = y * pitch;
                            let dst_start = y * row_width;
                            bgra_buffer[dst_start..dst_start+row_width]
                                .copy_from_slice(&src_slice[src_start..src_start+row_width]);
                        }

                        d3d_context.Unmap(&staging_texture, 0);
                        let _ = duplication.ReleaseFrame();
                    }

                    // Strict 120fps Throttle to prevent Network Buffer bloat while allowing zero-latency mirroring
                    let elapsed = frame_start.elapsed();
                    if elapsed < Duration::from_millis(8) {
                        tokio::time::sleep(Duration::from_millis(8) - elapsed).await;
                    }
                    frame_start = tokio::time::Instant::now();

                    // Send to encoder
                    match encoder.encode_frame(&bgra_buffer) {
                        Ok(nalu) => {
                            if !nalu.is_empty() {
                                if tx.send(nalu).await.is_err() {
                                    info!("Capture loop shutting down (receiver dropped)");
                                    break Ok(());
                                }
                            }
                        }
                        Err(e) => error!("Encoder error: {}", e),
                    }
                } else {
                    unsafe { let _ = duplication.ReleaseFrame(); }
                }
            }
            Err(e) => {
                if e.code() == DXGI_ERROR_WAIT_TIMEOUT {
                    // Screen unchanged — skip, avoid flooding encoder with duplicate frames
                    continue;
                } else {
                    error!("DXGI AcquireNextFrame failed: {:?}", e);
                    break Err(anyhow!("DXGI Error: {:?}", e));
                }
            }
        }
    }
}
