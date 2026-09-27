import os

encoder_path = 'windows/src/encoder.rs'
with open(encoder_path, 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace("MFVideoFormat_H264, MFVideoFormat_NV12", "MFVideoFormat_HEVC, MFVideoFormat_NV12")

with open(encoder_path, 'w', encoding='utf-8') as f:
    f.write(content)
print("Import fixed!")
