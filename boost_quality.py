import os

encoder_path = 'windows/src/encoder.rs'
with open(encoder_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Bump Bitrate from 8,000,000 to 25,000,000
content = content.replace("out_type.SetUINT32(&MF_MT_AVG_BITRATE, 8_000_000)?;", "out_type.SetUINT32(&MF_MT_AVG_BITRATE, 25_000_000)?;")

# Fix log text
content = content.replace('info!("MF H.264 Encoder initialized: {}x{} @ 120fps, 8Mbps (TCP Tunnel Mode)", width, height);', 
                          'info!("MF NVENC H.264 Encoder initialized: {}x{} @ 120fps, 25Mbps (Cloud Gaming TCP Mode)", width, height);')

# Also, let's explicitly set CODECAPI_AVEncCommonQuality to optimize for SPEED (low latency)
# 100 = Quality, 0 = Speed. NVENC uses this for its preset selection.
# Wait, let's just make sure B-frames is 0 and LowLatency is true (it already is).

with open(encoder_path, 'w', encoding='utf-8') as f:
    f.write(content)
print("Bitrate boosted to 25 Mbps!")
