import os

encoder_path = 'windows/src/encoder.rs'
with open(encoder_path, 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace('info!("MF H.264 Encoder initialized: {}x{} @ 60fps, 5Mbps (Paced UDP Mode)", width, height);', 
                          'info!("MF H.265 (HEVC) Encoder initialized: {}x{} @ 120fps, 8Mbps (TCP Tunnel Mode)", width, height);')

with open(encoder_path, 'w', encoding='utf-8') as f:
    f.write(content)
print("Log text fixed!")
