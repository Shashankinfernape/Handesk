import os

decoder_path = 'android/app/src/main/java/com/directlink/client/VideoDecoder.kt'
with open(decoder_path, 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace("MediaFormat.MIMETYPE_VIDEO_AVC", "MediaFormat.MIMETYPE_VIDEO_HEVC")
content = content.replace(
    "// H.264 NAL Unit Type Parsing\n                val type = data[start + startCodeLen].toInt() and 0x1F\n                val flags = if (type == 7 || type == 8) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0",
    "// HEVC (H.265) NAL Unit Type Parsing\n                val type = (data[start + startCodeLen].toInt() and 0x7E) ushr 1\n                val flags = if (type == 32 || type == 33 || type == 34) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0"
)

with open(decoder_path, 'w', encoding='utf-8') as f:
    f.write(content)
print("Android decoder switched to H.265 HEVC!")
