import os

cap_path = 'windows/src/capture.rs'
with open(cap_path, 'r', encoding='utf-8') as f:
    content = f.read()

target = '''    if duplication_result.is_err() {
        error!("Failed to duplicate output! Falling back to test pattern generator.");
        let width = 1600;
        let height = 900;
    let mut encoder = crate::encoder::MFEncoder::new(width, height)?;
        let mut nv12_buffer = vec![128u8; (width * height + (width * height / 2)) as usize];
        
        let mut frame_count: u32 = 0;
        loop {
            let y_color = (frame_count % 255) as u8;
            for i in 0..(width * height) as usize {
                nv12_buffer[i] = y_color; // Grayscale pulse
            }
            // UV plane remains 128 (neutral color)
            
            match encoder.encode_frame(&nv12_buffer) {'''

replacement = '''    if duplication_result.is_err() {
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
            
            match encoder.encode_frame(&bgra_buffer) {'''

content = content.replace(target, replacement)

with open(cap_path, 'w', encoding='utf-8') as f:
    f.write(content)

print("Fixed!")
