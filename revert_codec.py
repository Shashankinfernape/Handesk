import os

encoder_path = 'windows/src/encoder.rs'
with open(encoder_path, 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace("guidSubtype: MFVideoFormat_HEVC", "guidSubtype: MFVideoFormat_H264")
content = content.replace("out_type.SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_HEVC)?;", "out_type.SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_H264)?;")
content = content.replace("MFVideoFormat_HEVC, MFVideoFormat_NV12", "MFVideoFormat_H264, MFVideoFormat_NV12")

with open(encoder_path, 'w', encoding='utf-8') as f:
    f.write(content)

decoder_path = 'android/app/src/main/java/com/directlink/client/VideoDecoder.kt'
with open(decoder_path, 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace("MediaFormat.MIMETYPE_VIDEO_HEVC", "MediaFormat.MIMETYPE_VIDEO_AVC")
content = content.replace("""// HEVC (H.265) NAL Unit Type Parsing
                val type = (data[start + startCodeLen].toInt() and 0x7E) ushr 1
                val flags = if (type == 32 || type == 33 || type == 34) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0""", 
"""// H.264 NAL Unit Type Parsing
                val type = data[start + startCodeLen].toInt() and 0x1F
                val flags = if (type == 7 || type == 8) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0""")

with open(decoder_path, 'w', encoding='utf-8') as f:
    f.write(content)
print("Reverted to H.264!")
