import os

stun_path = 'android/app/src/main/java/com/directlink/client/StunClient.kt'
with open(stun_path, 'r', encoding='utf-8') as f:
    content = f.read()

target = 'val stunServer = InetAddress.getByName("stun.l.google.com")'
replacement = """// Force IPv4 STUN server so Android doesn't accidentally ask for an IPv6 STUN mapping
            // (142.250.14.127 is stun.l.google.com IPv4)
            val stunServer = InetAddress.getByName("142.250.14.127")"""

content = content.replace(target, replacement)
with open(stun_path, 'w', encoding='utf-8') as f:
    f.write(content)
print("Android STUN fixed!")
