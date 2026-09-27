# coding=utf-8
import re

sig_path = 'windows/src/signaling.rs'
with open(sig_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Pattern to capture everything from "// Start capture + encoder" to the end of the while let loop
pattern = re.compile(r'// Start capture \+ encoder, stream binary frames over this WebSocket.*?while let Some\(nalu\) = rx\.recv\(\)\.await \{.*?\n\s*\}\n\s*\}\);', re.DOTALL)

replacement = "});"

content, count = pattern.subn(replacement, content)
print("Replaced:", count)

with open(sig_path, 'w', encoding='utf-8') as f:
    f.write(content)
