import re
with open('windows/src/signaling.rs', 'r', encoding='utf-8') as f:
    content = f.read()

# Nuke the channel creation and spawn block
content = re.sub(r'// Start capture \+ encoder, stream binary frames over this WebSocket.*?let capture_handle = tokio::spawn\(async move \{.*?\}\);', '', content, flags=re.DOTALL)

# Nuke the loop reading from rx and sending to ws_tx
content = re.sub(r'tokio::spawn\(async move \{[\s\S]*?while let Some\(nalu\) = rx\.recv\(\)\.await \{[\s\S]*?\}\);', '', content, flags=re.DOTALL)

with open('windows/src/signaling.rs', 'w', encoding='utf-8') as f:
    f.write(content)
print("Nuked!")
