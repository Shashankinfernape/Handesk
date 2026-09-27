import os

net_path = 'android/app/src/main/java/com/directlink/client/NetworkClient.kt'
with open(net_path, 'r', encoding='utf-8') as f:
    content = f.read()

target = """                if (socket != null && serverAddress != null) {
                    val dp = DatagramPacket(fullPacket, fullPacket.size, serverAddress, targetPort)
                    if (inputPacketSender != null) { inputPacketSender?.invoke(fullPacket) } else { socket?.send(dp) }
                }"""

replacement = """                if (inputPacketSender != null) { 
                    inputPacketSender?.invoke(fullPacket) 
                } else if (socket != null && serverAddress != null) {
                    val dp = DatagramPacket(fullPacket, fullPacket.size, serverAddress, targetPort)
                    socket?.send(dp)
                }"""

content = content.replace(target, replacement)
with open(net_path, 'w', encoding='utf-8') as f:
    f.write(content)
print("Android input drop fixed!")
