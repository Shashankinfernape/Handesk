import os

enc_path = 'windows/src/encoder.rs'
with open(enc_path, 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace("CODECAPI_AVEncMPVProfile, &var_tru", "CODECAPI_AVEncMPVProfile, &var_true")

with open(enc_path, 'w', encoding='utf-8') as f:
    f.write(content)
print("Fixed encoder compilation error!")
