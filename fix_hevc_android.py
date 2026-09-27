import os

dec_path = 'android/app/src/main/java/com/directlink/client/VideoDecoder.kt'
with open(dec_path, 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace("MIMETYPE_VIDEO_AVC", "MIMETYPE_VIDEO_HEVC")

with open(dec_path, 'w', encoding='utf-8') as f:
    f.write(content)
print("Fixed Android to HEVC!")
