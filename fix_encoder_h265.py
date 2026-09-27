import os

encoder_path = 'windows/src/encoder.rs'
with open(encoder_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Switch back to HEVC
content = content.replace("guidSubtype: MFVideoFormat_H264", "guidSubtype: MFVideoFormat_HEVC")
content = content.replace("out_type.SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_H264)?;", "out_type.SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_HEVC)?;")
content = content.replace("MFVideoFormat_H264, MFVideoFormat_NV12", "MFVideoFormat_HEVC, MFVideoFormat_NV12")

# Remove the hardcoded H264 profile!
target_profile = """                let var_profile = windows::core::VARIANT::from(66u32);
                let _ = unsafe { codec_api.SetValue(&windows::Win32::Media::MediaFoundation::CODECAPI_AVEncMPVProfile, &var_profile) };"""
replacement_profile = """                // Let the GPU driver automatically select the best Profile/Level for HEVC (H.265)
                // Hardcoding 66 (H.264 Main) causes Invalid Type crash on HEVC!"""
content = content.replace(target_profile, replacement_profile)

# Also fix the context message
content = content.replace(".context(\"SetOutputType (H264) failed\")?", ".context(\"SetOutputType (HEVC) failed\")?")

with open(encoder_path, 'w', encoding='utf-8') as f:
    f.write(content)

decoder_path = 'android/app/src/main/java/com/directlink/client/VideoDecoder.kt'
with open(decoder_path, 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace("MediaFormat.MIMETYPE_VIDEO_AVC", "MediaFormat.MIMETYPE_VIDEO_HEVC")
content = content.replace("""// H.264 NAL Unit Type Parsing
                val type = data[start + startCodeLen].toInt() and 0x1F
                val flags = if (type == 7 || type == 8) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0""", 
"""// HEVC (H.265) NAL Unit Type Parsing
                val type = (data[start + startCodeLen].toInt() and 0x7E) ushr 1
                val flags = if (type == 32 || type == 33 || type == 34) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0""")

with open(decoder_path, 'w', encoding='utf-8') as f:
    f.write(content)
print("H.265 restored with dynamic profile!")
