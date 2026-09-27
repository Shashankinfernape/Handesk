import os, re

sig_path = 'windows/src/signaling.rs'
with open(sig_path, 'r', encoding='utf-8') as f:
    content = f.read()

pattern = re.compile(r'candidate: format!\("udp:\{\}", ip\),\s*isHost: Some\(true\),\s*serverReflexiveIp: None,')

replacement = 'candidate: "udp:0.0.0.0".to_string(),\n                                                  isHost: Some(true),\n                                                  serverReflexiveIp: Some(ip.clone()),'

content, count = pattern.subn(replacement, content)
print("Replaced:", count)

with open(sig_path, 'w', encoding='utf-8') as f:
    f.write(content)
