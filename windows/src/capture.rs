use anyhow::{Result, Context, anyhow};
use std::time::Duration;
use tracing::{info, error};

use windows::core::Interface;
use windows::Win32::Graphics::Direct3D::D3D_FEATURE_LEVEL_11_0;
use windows::Win32::Graphics::Direct3D11::{
    D3D11CreateDevice, ID3D11Device, ID3D11DeviceContext, ID3D11Texture2D, ID3D11Multithread,
    D3D11_CREATE_DEVICE_BGRA_SUPPORT, D3D11_SDK_VERSION, D3D11_TEXTURE2D_DESC,
    D3D11_USAGE_DEFAULT, D3D11_USAGE_STAGING, D3D11_BIND_RENDER_TARGET, D3D11_BIND_SHADER_RESOURCE,
    D3D11_CPU_ACCESS_READ, D3D11_CPU_ACCESS_WRITE, D3D11_MAP_READ_WRITE, D3D11_BOX,
};
use windows::Win32::Graphics::Dxgi::{
    IDXGIDevice, IDXGIOutput1, IDXGIOutputDuplication,
    DXGI_OUTDUPL_FRAME_INFO, DXGI_ERROR_WAIT_TIMEOUT,
    IDXGIResource, DXGI_OUTDUPL_POINTER_SHAPE_INFO,
};
use windows::Win32::Graphics::Dxgi::Common::{DXGI_FORMAT_B8G8R8A8_UNORM, DXGI_SAMPLE_DESC};
use windows::Win32::UI::WindowsAndMessaging::{GetCursorPos, GetSystemMetrics, SM_CXSCREEN, SM_CYSCREEN};
use windows::Win32::Foundation::POINT;

pub static TARGET_BITRATE: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(12_000_000); // 12 Mbps default for 60fps clarity
pub static CONFIGURED_BITRATE: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(12_000_000);
pub static AUTO_BITRATE_ENABLED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
pub static TARGET_FPS: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(60);
pub static TARGET_GOP: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(120);
pub static DESKTOP_WIDTH: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);
pub static DESKTOP_HEIGHT: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);
pub static FORCE_IDR: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
pub static IS_TAILSCALE_CLIENT: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
pub static SHOW_CURSOR_ON_STREAM: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

pub fn get_desktop_dimensions() -> (u32, u32) {
    let mut w = DESKTOP_WIDTH.load(std::sync::atomic::Ordering::Relaxed);
    let mut h = DESKTOP_HEIGHT.load(std::sync::atomic::Ordering::Relaxed);
    if w == 0 || h == 0 {
        unsafe {
            let sm_w = GetSystemMetrics(SM_CXSCREEN) as u32;
            let sm_h = GetSystemMetrics(SM_CYSCREEN) as u32;
            if sm_w > 0 && sm_h > 0 {
                DESKTOP_WIDTH.store(sm_w, std::sync::atomic::Ordering::Relaxed);
                DESKTOP_HEIGHT.store(sm_h, std::sync::atomic::Ordering::Relaxed);
                w = sm_w;
                h = sm_h;
            }
        }
    }
    if w == 0 || h == 0 {
        (1440, 900)
    } else {
        (w, h)
    }
}

#[link(name = "user32")]
extern "system" {
    fn OpenDesktopW(lpszDesktop: windows::core::PCWSTR, dwFlags: u32, fInherit: i32, dwDesiredAccess: u32) -> isize;
    fn SetThreadDesktop(hDesktop: isize) -> i32;
}

// ─── Cursor Shape & Rendering Constants ──────────────────────────────────────

const POINTER_SHAPE_TYPE_MONOCHROME: u32 = 1;
const POINTER_SHAPE_TYPE_COLOR: u32 = 2;
const POINTER_SHAPE_TYPE_MASKED_COLOR: u32 = 4;

struct CursorShape {
    pixels: Vec<u8>, // 32-bit BGRA
    width: u32,
    height: u32,
    hot_x: i32,
    hot_y: i32,
}

/// Fallback standard crisp Windows arrow cursor (black outline, white interior)
fn default_arrow_cursor() -> CursorShape {
    let width = 32u32;
    let height = 32u32;
    let mut pixels = vec![0u8; (width * height * 4) as usize];

    #[rustfmt::skip]
    let pattern: [&str; 22] = [
        "1                     ",
        "11                    ",
        "121                   ",
        "1221                  ",
        "12221                 ",
        "122221                ",
        "1222221               ",
        "12222221              ",
        "122222221             ",
        "1222222221            ",
        "12222222221           ",
        "122222222221          ",
        "1222222111111         ",
        "12221221              ",
        "1221 1221             ",
        "121  1221             ",
        "11    1221            ",
        "1     1221            ",
        "       1221           ",
        "       1221           ",
        "        11            ",
        "                      ",
    ];

    for (y, row) in pattern.iter().enumerate() {
        for (x, ch) in row.chars().enumerate() {
            if x >= width as usize || y >= height as usize { continue; }
            let idx = (y * width as usize + x) * 4;
            match ch {
                '1' => {
                    // Black border: BGRA = (0, 0, 0, 255)
                    pixels[idx] = 0;
                    pixels[idx + 1] = 0;
                    pixels[idx + 2] = 0;
                    pixels[idx + 3] = 255;
                }
                '2' => {
                    // White interior: BGRA = (255, 255, 255, 255)
                    pixels[idx] = 255;
                    pixels[idx + 1] = 255;
                    pixels[idx + 2] = 255;
                    pixels[idx + 3] = 255;
                }
                _ => {} // Transparent
            }
        }
    }

    CursorShape {
        pixels,
        width,
        height,
        hot_x: 0,
        hot_y: 0,
    }
}

/// Convert raw DXGI pointer shape buffer to standard 32-bit BGRA pixels.
fn convert_cursor_to_bgra(
    buf: &[u8],
    shape_type: u32,
    width: u32,
    height: u32,
    pitch: u32,
) -> Vec<u8> {
    let w = width as usize;
    let h = height as usize;
    let p = pitch as usize;

    if shape_type == POINTER_SHAPE_TYPE_COLOR || shape_type == POINTER_SHAPE_TYPE_MASKED_COLOR {
        let mut out = vec![0u8; w * h * 4];
        for row in 0..h {
            let src_start = row * p;
            let dst_start = row * w * 4;
            let copy_len = (w * 4).min(buf.len().saturating_sub(src_start));
            if copy_len > 0 {
                out[dst_start..dst_start + copy_len]
                    .copy_from_slice(&buf[src_start..src_start + copy_len]);
            }
        }
        out
    } else if shape_type == POINTER_SHAPE_TYPE_MONOCHROME {
        let mut out = vec![0u8; w * h * 4];
        for row in 0..h {
            for col in 0..w {
                let byte_offset = col / 8;
                let bit_mask = 0x80u8 >> (col % 8);

                let and_idx = row * p + byte_offset;
                let and_bit = if and_idx < buf.len() { buf[and_idx] & bit_mask != 0 } else { true };

                let xor_idx = (row + h) * p + byte_offset;
                let xor_bit = if xor_idx < buf.len() { buf[xor_idx] & bit_mask != 0 } else { false };

                let dst = (row * w + col) * 4;
                if !and_bit && !xor_bit {
                    // Black
                    out[dst] = 0;
                    out[dst + 1] = 0;
                    out[dst + 2] = 0;
                    out[dst + 3] = 255;
                } else if !and_bit && xor_bit {
                    // White
                    out[dst] = 255;
                    out[dst + 1] = 255;
                    out[dst + 2] = 255;
                    out[dst + 3] = 255;
                } else if and_bit && !xor_bit {
                    // Transparent
                    out[dst + 3] = 0;
                } else {
                    // Screen Inversion
                    out[dst] = 255;
                    out[dst + 1] = 255;
                    out[dst + 2] = 255;
                    out[dst + 3] = 255;
                }
            }
        }
        out
    } else {
        vec![0u8; w * h * 4]
    }
}

/// Blit the cursor directly onto the target GPU texture using a small 128x128 staging scratchpad.
/// Only the tiny (<=128x128) cursor bounding box is transferred and alpha blended.
fn blit_cursor_to_gpu_texture(
    ctx: &ID3D11DeviceContext,
    staging: &ID3D11Texture2D,
    target: &ID3D11Texture2D,
    cursor: &CursorShape,
    pos_x: i32,
    pos_y: i32,
    frame_w: u32,
    frame_h: u32,
) {
    let draw_x = pos_x - cursor.hot_x;
    let draw_y = pos_y - cursor.hot_y;

    let left = draw_x.max(0) as u32;
    let top = draw_y.max(0) as u32;
    let right = (draw_x + cursor.width as i32).clamp(0, frame_w as i32) as u32;
    let bottom = (draw_y + cursor.height as i32).clamp(0, frame_h as i32) as u32;

    if right <= left || bottom <= top {
        return;
    }

    let box_w = (right - left).min(128);
    let box_h = (bottom - top).min(128);

    unsafe {
        // 1. Copy the background under the cursor from target GPU texture -> staging texture
        let src_box = D3D11_BOX {
            left,
            top,
            front: 0,
            right: left + box_w,
            bottom: top + box_h,
            back: 1,
        };
        ctx.CopySubresourceRegion(staging, 0, 0, 0, 0, target, 0, Some(&src_box));

        // 2. Map staging texture for read/write
        let mut mapped = std::mem::zeroed();
        if ctx.Map(staging, 0, D3D11_MAP_READ_WRITE, 0, Some(&mut mapped)).is_err() {
            return;
        }

        let pitch = mapped.RowPitch as usize;
        let ptr = mapped.pData as *mut u8;

        // 3. Alpha blend cursor pixels into mapped staging scratchpad
        for cy in 0..box_h {
            let screen_y = top + cy;
            let cur_y = (screen_y as i32) - draw_y;
            if cur_y < 0 || cur_y >= cursor.height as i32 { continue; }

            let row_ptr = ptr.add(cy as usize * pitch);

            for cx in 0..box_w {
                let screen_x = left + cx;
                let cur_x = (screen_x as i32) - draw_x;
                if cur_x < 0 || cur_x >= cursor.width as i32 { continue; }

                let cur_idx = ((cur_y as usize * cursor.width as usize) + cur_x as usize) * 4;
                if cur_idx + 3 >= cursor.pixels.len() { continue; }

                let ca = cursor.pixels[cur_idx + 3];
                if ca == 0 { continue; }

                let cb = cursor.pixels[cur_idx];
                let cg = cursor.pixels[cur_idx + 1];
                let cr = cursor.pixels[cur_idx + 2];

                let frame_idx = cx as usize * 4;
                if ca == 255 {
                    *row_ptr.add(frame_idx)     = cb;
                    *row_ptr.add(frame_idx + 1) = cg;
                    *row_ptr.add(frame_idx + 2) = cr;
                } else {
                    let a = ca as u32;
                    let ia = 255 - a;
                    let fb = *row_ptr.add(frame_idx) as u32;
                    let fg = *row_ptr.add(frame_idx + 1) as u32;
                    let fr = *row_ptr.add(frame_idx + 2) as u32;
                    *row_ptr.add(frame_idx)     = ((cb as u32 * a + fb * ia) / 255) as u8;
                    *row_ptr.add(frame_idx + 1) = ((cg as u32 * a + fg * ia) / 255) as u8;
                    *row_ptr.add(frame_idx + 2) = ((cr as u32 * a + fr * ia) / 255) as u8;
                }
            }
        }

        // 4. Unmap staging
        ctx.Unmap(staging, 0);

        // 5. Copy blended region back to target GPU texture
        let dst_box = D3D11_BOX {
            left: 0,
            top: 0,
            front: 0,
            right: box_w,
            bottom: box_h,
            back: 1,
        };
        ctx.CopySubresourceRegion(target, 0, left, top, 0, staging, 0, Some(&dst_box));
    }
}

// ─── Main capture loop ────────────────────────────────────────────────────────

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
    DESKTOP_WIDTH.store(width, std::sync::atomic::Ordering::Relaxed);
    DESKTOP_HEIGHT.store(height, std::sync::atomic::Ordering::Relaxed);
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

    // Small 128x128 staging texture for cursor blit scratchpad
    let cursor_staging_desc = D3D11_TEXTURE2D_DESC {
        Width: 128,
        Height: 128,
        MipLevels: 1,
        ArraySize: 1,
        Format: DXGI_FORMAT_B8G8R8A8_UNORM,
        SampleDesc: DXGI_SAMPLE_DESC { Count: 1, Quality: 0 },
        Usage: D3D11_USAGE_STAGING,
        BindFlags: 0,
        CPUAccessFlags: (D3D11_CPU_ACCESS_READ.0 | D3D11_CPU_ACCESS_WRITE.0) as u32,
        MiscFlags: 0,
    };
    let mut cursor_staging_ptr: Option<ID3D11Texture2D> = None;
    unsafe { d3d_device.CreateTexture2D(&cursor_staging_desc, None, Some(&mut cursor_staging_ptr))?; }
    let cursor_staging_tex = cursor_staging_ptr.unwrap();

    // Initialize cursor state with default arrow and live screen coordinates
    let mut cursor_shape: Option<CursorShape> = Some(default_arrow_cursor());
    let mut initial_pt = POINT::default();
    unsafe { let _ = GetCursorPos(&mut initial_pt); }
    let mut cursor_x: i32 = initial_pt.x;
    let mut cursor_y: i32 = initial_pt.y;
    let mut cursor_visible: bool = true;
    let mut cursor_shape_buf: Vec<u8> = Vec::new();

    let mut encoder = crate::encoder::MFEncoder::new(width, height, &d3d_device)?;

    let mut current_bitrate = TARGET_BITRATE.load(std::sync::atomic::Ordering::Relaxed);
    encoder.set_bitrate(current_bitrate);
    let mut current_gop = TARGET_GOP.load(std::sync::atomic::Ordering::Relaxed);
    encoder.set_gop_size(current_gop);

    let mut last_idr = std::time::Instant::now();
    let mut last_sent = std::time::Instant::now();
    let mut last_frame_time = std::time::Instant::now();

    loop {
        let new_bitrate = TARGET_BITRATE.load(std::sync::atomic::Ordering::Relaxed);
        if new_bitrate != current_bitrate {
            current_bitrate = new_bitrate;
            info!("Dynamic Bitrate Change: {} bps", current_bitrate);
            encoder.set_bitrate(current_bitrate);
        }

        let new_gop = TARGET_GOP.load(std::sync::atomic::Ordering::Relaxed);
        if new_gop != current_gop {
            current_gop = new_gop;
            info!("Dynamic GOP Change: {} frames", current_gop);
            encoder.set_gop_size(current_gop);
        }

        let rtt = crate::network_udp::SMOOTHED_RTT_MS.load(std::sync::atomic::Ordering::Relaxed);
        let is_tailscale = IS_TAILSCALE_CLIENT.load(std::sync::atomic::Ordering::Relaxed);

        // IDR recovery throttle:
        // LAN: ultra-fast 10ms throttle (zero artificial delay)
        // Tailscale (100.x.x.x): 250ms..800ms scaled by RTT to prevent WAN congestion storms
        let idr_throttle_ms = if is_tailscale {
            (250u64).max((rtt as u64) * 2).min(800)
        } else {
            (120u64).max((rtt as u64) * 2).min(400)
        };

        if FORCE_IDR.swap(false, std::sync::atomic::Ordering::Relaxed) {
            if last_idr.elapsed() >= Duration::from_millis(idr_throttle_ms) {
                info!("Triggering instantaneous IDR keyframe (RTT: {}ms, Tailscale: {})", rtt, is_tailscale);
                encoder.force_idr();
                last_idr = std::time::Instant::now();
            } else {
                FORCE_IDR.store(true, std::sync::atomic::Ordering::Relaxed);
            }
        }

        // Precision FPS pacing (LAN supports 144 FPS, Tailscale 60 FPS)
        let max_fps = if is_tailscale { 60 } else { 144 };
        let fps = TARGET_FPS.load(std::sync::atomic::Ordering::Relaxed).clamp(15, max_fps) as u64;
        let frame_interval = Duration::from_micros(1_000_000 / fps);
        let elapsed = last_frame_time.elapsed();
        if elapsed < frame_interval {
            let remain = frame_interval - elapsed;
            if remain > Duration::from_micros(2500) {
                std::thread::sleep(remain - Duration::from_micros(1500));
            }
            while last_frame_time.elapsed() < frame_interval {
                std::hint::spin_loop();
            }
        }
        last_frame_time = std::time::Instant::now();

        let mut frame_info = DXGI_OUTDUPL_FRAME_INFO::default();
        let mut desktop_resource: Option<IDXGIResource> = None;

        let mut got_new_frame = false;
        let mut current_texture: Option<&ID3D11Texture2D> = None;

        // 2ms polling timeout: returns immediately if frame is ready, never blocks for 16ms
        let res = unsafe {
            duplication.AcquireNextFrame(2, &mut frame_info, &mut desktop_resource)
        };

        match res {
            Ok(_) => {
                let show_cursor = SHOW_CURSOR_ON_STREAM.load(std::sync::atomic::Ordering::Relaxed);

                if show_cursor {
                    // ── Cursor shape update from DXGI ───────────────────────────
                    if frame_info.PointerShapeBufferSize > 0 {
                        let buf_size = frame_info.PointerShapeBufferSize as usize;
                        cursor_shape_buf.resize(buf_size, 0u8);
                        let mut required: u32 = 0;
                        let mut shape_info = DXGI_OUTDUPL_POINTER_SHAPE_INFO::default();
                        let shape_res = unsafe {
                            duplication.GetFramePointerShape(
                                buf_size as u32,
                                cursor_shape_buf.as_mut_ptr() as *mut _,
                                &mut required,
                                &mut shape_info,
                            )
                        };
                        if shape_res.is_ok() {
                            let cw = shape_info.Width;
                            let ch = if shape_info.Type == POINTER_SHAPE_TYPE_COLOR || shape_info.Type == POINTER_SHAPE_TYPE_MASKED_COLOR {
                                shape_info.Height
                            } else {
                                shape_info.Height / 2 // Monochrome
                            };
                            let pixels = convert_cursor_to_bgra(
                                &cursor_shape_buf[..buf_size],
                                shape_info.Type,
                                cw,
                                ch,
                                shape_info.Pitch,
                            );
                            cursor_shape = Some(CursorShape {
                                pixels,
                                width: cw,
                                height: ch,
                                hot_x: shape_info.HotSpot.x,
                                hot_y: shape_info.HotSpot.y,
                            });
                        }
                    }

                    // ── Cursor position update ──────────────────────────────────
                    if frame_info.PointerPosition.Visible.as_bool() {
                        cursor_x = frame_info.PointerPosition.Position.x;
                        cursor_y = frame_info.PointerPosition.Position.y;
                        cursor_visible = true;
                    } else if frame_info.LastMouseUpdateTime != 0 {
                        cursor_visible = false;
                    } else {
                        // Fallback: poll live mouse position
                        let mut pt = POINT::default();
                        if unsafe { GetCursorPos(&mut pt).is_ok() } {
                            cursor_x = pt.x;
                            cursor_y = pt.y;
                        }
                    }
                }

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

                    // ── Blit cursor directly onto GPU frame texture ─────────
                    if show_cursor && cursor_visible {
                        if let Some(ref shape) = cursor_shape {
                            blit_cursor_to_gpu_texture(
                                &d3d_context,
                                &cursor_staging_tex,
                                target_tex,
                                shape,
                                cursor_x,
                                cursor_y,
                                width,
                                height,
                            );
                        }
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
