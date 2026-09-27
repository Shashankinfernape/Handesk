import os

enc_path = 'windows/src/encoder.rs'
with open(enc_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Change format from H264 to HEVC
content = content.replace("guidSubtype: MFVideoFormat_H264", "guidSubtype: windows::Win32::Media::MediaFoundation::MFVideoFormat_HEVC")
content = content.replace("CODECAPI_AVEncMPVProfile, &var_profile", "CODECAPI_AVEncMPVProfile, &var_tru") # Just pass true/dummy or remove it. Better yet, we can comment it out, but let's just let it run. Wait, HEVC might fail if passed H264 profile!
# Actually, eAVEncH264VProfile_Main is 66, which might be invalid for HEVC.
content = content.replace("let _ = unsafe { codec_api.SetValue(&windows::Win32::Media::MediaFoundation::CODECAPI_AVEncMPVProfile, &var_profile) };", "")

# Change MFVideoFormat_H264 to MFVideoFormat_HEVC in imports
content = content.replace("MFVideoFormat_H264", "MFVideoFormat_HEVC")

with open(enc_path, 'w', encoding='utf-8') as f:
    f.write(content)
print("Fixed encoder to HEVC!")
