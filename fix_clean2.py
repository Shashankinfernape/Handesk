import os

cap_path = 'windows/src/capture.rs'
with open(cap_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Remove the old buggy clone block
bad_block = """    // We didn't find any valid GPU/Monitor combo, we must fallback.
    let duplication_result = match best_duplication {
        Some(d) => Ok(d),
        None => Err(anyhow::anyhow!("E_ACCESSDENIED: No GPU adapter has privileges to capture the DWM Desktop.")),
    };
    
    let d3d_device = best_device.clone();
    let d3d_context = best_context.clone();
    let output1_opt = best_output.clone();
    
    // In order for the rest of the function to compile, if it fails, it will hit the test pattern.
    let output1 = match output1_opt {
        Some(o) => o,
        None => unsafe { factory.EnumAdapters(0)?.EnumOutputs(0)?.cast()? } // Dummy for compilation, will be skipped by is_err()
    };
    
    if let Some(dev) = d3d_device.as_ref() {
        let dxgi_device: IDXGIDevice = dev.cast()?;
    }
    
    if duplication_result.is_err() {
        error!("Failed to duplicate output! Error: {:?}. Falling back to test pattern generator.", duplication_result.err().unwrap());
        let width = 1600;
        let height = 900;
        let mut encoder = crate::encoder::MFEncoder::new(width, height)?;
        // We MUST pass a BGRA buffer because encode_frame does BGRA->NV12 conversion internally!
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

    let duplication: IDXGIOutputDuplication = duplication_result.unwrap();"""

new_block = """    if best_duplication.is_none() {
        error!("Failed to duplicate output! Error: E_ACCESSDENIED. Falling back to test pattern generator.");
        let width = 1600;
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
    let output1 = best_output.unwrap();"""

if bad_block in content:
    content = content.replace(bad_block, new_block)
    with open(cap_path, 'w', encoding='utf-8') as f:
        f.write(content)
    print("Fixed accurately!")
else:
    print("Bad block not found!")
