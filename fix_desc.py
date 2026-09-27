import os

cap_path = 'windows/src/capture.rs'
with open(cap_path, 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace('output.GetDesc()', 'output1.GetDesc()')

with open(cap_path, 'w', encoding='utf-8') as f:
    f.write(content)

print("Fixed!")
