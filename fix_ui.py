import os

sc_path = 'android/app/src/main/java/com/directlink/client/SignalingClient.kt'
with open(sc_path, 'r', encoding='utf-8') as f:
    sc_content = f.read()

target = 'if (json.optString("type") == "CLIENT_REQUEST_ACK" || json.optString("type") == "HOST_INFO") {'
replacement = 'if (json.optString("type") == "CLIENT_REQUEST_ACK" || json.optString("type") == "HOST_INFO" || json.optString("type") == "CANDIDATE") {'

sc_content = sc_content.replace(target, replacement)

with open(sc_path, 'w', encoding='utf-8') as f:
    f.write(sc_content)

print("Fixed!")
