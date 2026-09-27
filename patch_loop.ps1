$file = "windows\src\capture.rs"
$content = Get-Content $file -Raw

$pattern = '(?s)let mut frame_start = tokio::time::Instant::now\(\);.*?break Ok\(\(\);\)\s*\}\s*\}'
$new_loop = 'let mut frame_start = tokio::time::Instant::now();
    loop {
        let mut frame_info = DXGI_OUTDUPL_FRAME_INFO::default();
        let mut desktop_resource: Option<IDXGIResource> = None;
        
        let res = unsafe {
            duplication.AcquireNextFrame(0, &mut frame_info, &mut desktop_resource)
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
                }
            }
            Err(e) => {
                if e.code() != DXGI_ERROR_WAIT_TIMEOUT {
                    error!("DXGI AcquireNextFrame failed: {:?}", e);
                    break Err(anyhow!("DXGI Error: {:?}", e));
                }
            }
        }

        // Strict 60fps Throttle to maintain constant smooth stream and avoid network buffer bloat
        let elapsed = frame_start.elapsed();
        if elapsed < Duration::from_millis(16) {
            tokio::time::sleep(Duration::from_millis(16) - elapsed).await;
        }
        frame_start = tokio::time::Instant::now();

        // Send to encoder (always sends, even if frame didnt change, to keep UDP alive and smooth)
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
    }'

$content = [regex]::Replace($content, $pattern, $new_loop)
$content | Set-Content $file
