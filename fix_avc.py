import os

enc_path = 'windows/src/encoder.rs'
with open(enc_path, 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace("guidSubtype: windows::Win32::Media::MediaFoundation::MFVideoFormat_HEVC", "guidSubtype: windows::Win32::Media::MediaFoundation::MFVideoFormat_H264")
content = content.replace("MFVideoFormat_HEVC", "MFVideoFormat_H264")

with open(enc_path, 'w', encoding='utf-8') as f:
    f.write(content)

dec_path = 'android/app/src/main/java/com/directlink/client/VideoDecoder.kt'
with open(dec_path, 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace("MIMETYPE_VIDEO_HEVC", "MIMETYPE_VIDEO_AVC")

with open(dec_path, 'w', encoding='utf-8') as f:
    f.write(content)

print("Reverted to AVC!")
