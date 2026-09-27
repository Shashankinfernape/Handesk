import os

# Fix NetworkClient.kt
nc_path = 'android/app/src/main/java/com/directlink/client/NetworkClient.kt'
with open(nc_path, 'r', encoding='utf-8') as f:
    nc_content = f.read()

nc_content = nc_content.replace('import java.net.DatagramSocket', 'import java.net.DatagramSocket\nimport java.net.InetSocketAddress')
nc_content = nc_content.replace('private var socket: DatagramSocket?', 'var socket: DatagramSocket?')

with open(nc_path, 'w', encoding='utf-8') as f:
    f.write(nc_content)


# Fix MainActivity.kt
ma_path = 'android/app/src/main/java/com/directlink/client/MainActivity.kt'
with open(ma_path, 'r', encoding='utf-8') as f:
    ma_content = f.read()

ma_content = ma_content.replace('import kotlinx.coroutines.launch', 'import kotlinx.coroutines.launch\nimport kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.CoroutineScope')
ma_content = ma_content.replace('coroutineScope.launch(Dispatchers.IO)', 'CoroutineScope(Dispatchers.IO).launch')
ma_content = ma_content.replace('println("DirectLink: UDP P2P Connection SUCCESS! ($ip)")', 'println("DirectLink: UDP P2P Connection SUCCESS! ($localIp)")')

with open(ma_path, 'w', encoding='utf-8') as f:
    f.write(ma_content)

print("Fixed!")
