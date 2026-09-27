import re

cap_path = 'windows/src/capture.rs'
with open(cap_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Use regex to replace everything from "We didn't find any valid GPU" up to "let duplication: IDXGIOutputDuplication ="
pattern = re.compile(r'    // We didn\'t find any valid GPU.*?let duplication: IDXGIOutputDuplication = duplication_result\.unwrap\(\);', re.DOTALL)

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

content, count = pattern.subn(new_block, content)
print("Replaced count:", count)

with open(cap_path, 'w', encoding='utf-8') as f:
    f.write(content)
