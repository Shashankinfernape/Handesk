import os

file_path = 'android/app/src/main/java/com/directlink/client/MainActivity.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Update SignalingClient instantiation
content = content.replace('private val signalingClient = SignalingClient("wss://joseph-affect-avenue-involved.trycloudflare.com")', 'private val signalingClient = SignalingClient()')

# 2. Add serverUrl state
target2 = 'var remoteId by remember { mutableStateOf(prefs.getString("last_remote_id", "") ?: "") }'
rep2 = target2 + '\n    var serverUrl by remember { mutableStateOf(prefs.getString("last_server_url", "wss://your-url.trycloudflare.com") ?: "wss://your-url.trycloudflare.com") }'
content = content.replace(target2, rep2)

# 3. Add Server URL UI
ui_target = '''                Text("Enter the 9-Digit DirectLink ID of the host machine.", color = Color(0xFF888899), fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),'''

ui_replacement = '''                Text("Enter the 9-Digit DirectLink ID of the host machine.", color = Color(0xFF888899), fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                
                // --- SERVER URL FIELD ---
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(Color(0xFF0F0F16), RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (serverUrl.isEmpty()) {
                        Text("wss://<tunnel>.trycloudflare.com", color = Color(0xFF444455), fontSize = 14.sp)
                    }
                    BasicTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        textStyle = TextStyle(color = Color(0xFF888899), fontSize = 14.sp),
                        singleLine = true,
                        cursorBrush = SolidColor(Color(0xFF00D4FF)),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(12.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),'''

content = content.replace(ui_target, ui_replacement)

# 4. Update the connect logic
connect_target = '''                        onClick = {
                            prefs.edit().putString("last_remote_id", remoteId).apply()
                            coroutineScope.launch {
                                isConnecting = true
                                // Call signaling client to connect!
                                signalingClient.connect(remoteId, '''

connect_replacement = '''                        onClick = {
                            prefs.edit().putString("last_remote_id", remoteId).putString("last_server_url", serverUrl).apply()
                            coroutineScope.launch {
                                isConnecting = true
                                // Call signaling client to connect!
                                signalingClient.connect(serverUrl, remoteId, '''

content = content.replace(connect_target, connect_replacement)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)
print('Done!')
