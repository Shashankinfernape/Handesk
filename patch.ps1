$file = "android\app\src\main\java\com\directlink\client\MainActivity.kt"
$content = Get-Content $file -Raw

$pattern = '(?s)@Composable\s+fun HomeScreen.*?fun RemoteSessionScreen'
$replacement = '@Composable
fun HomeScreen(
    networkClient: NetworkClient,
    activity: Activity,
    onConnected: () -> Unit
) {
    val prefs = activity.getSharedPreferences("DirectLinkPrefs", Context.MODE_PRIVATE)
    var pcIp by remember { mutableStateOf(prefs.getString("last_pc_ip", "100.81.142.121") ?: "100.81.142.121") }
    var isConnecting by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F16)).padding(16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Home, contentDescription = "Logo", tint = Color(0xFF00D4FF), modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Text("DirectLink (Pure UDP)", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Control Remote Desktop", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(16.dp))
                Text("PC Tailscale IP", color = Color(0xFFAAAAAA), fontSize = 12.sp)
                Spacer(modifier = Modifier.height(8.dp))
                BasicTextField(
                    value = pcIp,
                    onValueChange = { pcIp = it },
                    textStyle = TextStyle(color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF2A2A3D), RoundedCornerShape(8.dp)).padding(16.dp),
                    cursorBrush = SolidColor(Color(0xFF00D4FF))
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = {
                        if (pcIp.isBlank()) { errorMsg = "Please enter IP"; return@Button }
                        errorMsg = ""
                        isConnecting = true
                        prefs.edit().putString("last_pc_ip", pcIp).apply()
                        coroutineScope.launch {
                            val err = networkClient.connectToHost(pcIp, "")
                            if (err == null) { onConnected() } else { errorMsg = err; isConnecting = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00D4FF), contentColor = Color.Black)
                ) {
                    if (isConnecting) { CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(24.dp)) } 
                    else { Text("Connect via Tailscale UDP", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                }
                if (errorMsg.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(errorMsg, color = Color.Red, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
fun RemoteSessionScreen'

$content = [regex]::Replace($content, $pattern, $replacement)

$content = $content -replace 'fun RemoteSessionScreen\(\s*networkClient: NetworkClient,\s*signalingClient: SignalingClient,\s*activity: Activity', 'fun RemoteSessionScreen(networkClient: NetworkClient, activity: Activity'
$pattern2 = '(?s)// Collect frames from the Cloudflare WebSocket relay!.*?launch \{\s*signalingClient\.binaryFrames\.collect \{ frameData ->\s*if \(useWebsocket\) \{\s*videoDecoder\?\.decodeNalu\(frameData\)\s*\}\s*\}\s*\}'
$content = [regex]::Replace($content, $pattern2, '')

$content | Set-Content $file
