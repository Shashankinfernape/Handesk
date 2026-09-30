package com.directlink.client

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*

class MainActivity : ComponentActivity() {
    private val networkClient = NetworkClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DirectLinkApp(networkClient, this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        networkClient.disconnect()
    }
}

@Composable
fun DirectLinkApp(networkClient: NetworkClient, activity: Activity) {
    var isConnected by remember { mutableStateOf(networkClient.isConnected) }

    if (!isConnected) {
        HomeScreen(
            networkClient = networkClient,
            activity = activity,
            onConnected = { isConnected = true }
        )
    } else {
        RemoteSessionScreen(
            networkClient = networkClient,
            activity = activity,
            onDisconnect = { 
                networkClient.disconnect()
                isConnected = false 
            }
        )
    }
}

@Composable
fun HomeScreen(
    networkClient: NetworkClient,
    activity: Activity,
    onConnected: () -> Unit
) {
    val prefs = activity.getSharedPreferences("DirectLinkPrefs", Context.MODE_PRIVATE)
    var hostIp by remember { mutableStateOf(prefs.getString("last_ip", "") ?: "") }
    var isConnecting by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    
    // Fake recent sessions for UI demonstration (in a real app, load from Room/Prefs)
    val recentSessions = listOf("192.168.1.15", "10.0.0.23")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F16))
            .padding(16.dp)
    ) {
        // App Bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Home, contentDescription = "Logo", tint = Color(0xFF00D4FF), modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Text("DirectLink Desktop", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }

        // Connection Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Control Remote Device", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Enter the Direct IP Address of the host machine.", color = Color(0xFF888899), fontSize = 14.sp)
                Spacer(Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .background(Color(0xFF0F0F16), RoundedCornerShape(8.dp))
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (hostIp.isEmpty()) {
                            Text("e.g. 192.168.1.15", color = Color(0xFF444455), fontSize = 16.sp)
                        }
                        BasicTextField(
                            value = hostIp,
                            onValueChange = { hostIp = it },
                            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            cursorBrush = SolidColor(Color(0xFF00D4FF)),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    Button(
                        onClick = {
                            prefs.edit().putString("last_ip", hostIp).apply()
                            coroutineScope.launch {
                                isConnecting = true
                                val err = networkClient.connectToHost(hostIp) // Back to UDP!
                                isConnecting = false
                                if (err == null) onConnected()
                                else errorMsg = err
                            }
                        },
                        modifier = Modifier.height(56.dp).width(120.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00D4FF)),
                        enabled = !isConnecting && hostIp.isNotEmpty()
                    ) {
                        if (isConnecting) CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                        else Text("CONNECT", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
                
                if (errorMsg.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(errorMsg, color = Color(0xFFFF4444), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        
        Spacer(Modifier.height(32.dp))
        Text("RECENT SESSIONS", color = Color(0xFF555566), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        
        LazyColumn {
            items(recentSessions) { sessionIp ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .background(Color(0xFF1A1A2E), RoundedCornerShape(8.dp))
                        .clickable { 
                            hostIp = sessionIp
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Home, contentDescription = null, tint = Color(0xFF888899))
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(sessionIp, color = Color.White, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
fun RemoteSessionScreen(
    networkClient: NetworkClient,
    activity: Activity,
    onDisconnect: () -> Unit
) {
    var videoDecoder by remember { mutableStateOf<VideoDecoder?>(null) }
    var showToolbar by remember { mutableStateOf(false) }
    var videoAspectRatio by remember { mutableStateOf(16f / 9f) }
    
    // Keyboard Injection
    val focusRequester = remember { FocusRequester() }

    DisposableEffect(Unit) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        onDispose {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            networkClient.videoFrameCallback = null
            videoDecoder?.stop()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // --- VIDEO: TextureView + full-screen touch overlay inside a FrameLayout ---
        // Using a FrameLayout lets us stack the TextureView (video) and a transparent
        // View (touch overlay) as siblings inside a single AndroidView. The overlay
        // sits on top and captures all touches. The TextureView gets setTransform(Matrix)
        // calls for real visual zoom/pan without affecting Compose touch dispatch.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val frame = android.widget.FrameLayout(ctx)

                // TextureView for video output
                val textureView = TextureView(ctx)
                frame.addView(textureView, android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                ))

                // Full-screen transparent overlay — receives all touches
                val overlay = View(ctx)
                frame.addView(overlay, android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                ))

                val touchHandler = DirectTouchHandler(networkClient, textureView, overlay)
                
                touchHandler.onTwoFingerSingleTap = {
                    activity.runOnUiThread { focusRequester.requestFocus() }
                }
                touchHandler.onTwoFingerDoubleTap = {
                    activity.runOnUiThread { showToolbar = true }
                }

                overlay.setOnTouchListener(touchHandler)

                textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        val surface = Surface(st)
                        val decoder = VideoDecoder(surface, 1600, 900) { vw, vh ->
                            if (vh > 0) {
                                activity.runOnUiThread {
                                    videoAspectRatio = vw.toFloat() / vh.toFloat()
                                    touchHandler.videoAspectRatio = videoAspectRatio
                                }
                            }
                        }
                        decoder.start()
                        videoDecoder = decoder
                        networkClient.videoFrameCallback = { nalu -> decoder.decodeNalu(nalu) }
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        videoDecoder?.stop(); videoDecoder = null
                        networkClient.videoFrameCallback = null
                        return true
                    }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }

                frame
            }
        )
        
        // --- DIAGNOSTIC HUD ---
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .background(Color(0x88000000), RoundedCornerShape(8.dp))
                .padding(16.dp)
        ) {
            Text("DIRECTLINK DEBUG", color = Color.Yellow, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Pkts Rx: ${DebugStats.packetsReceived}", color = Color.White, fontSize = 10.sp)
            Text("Frames OK: ${DebugStats.framesCompleted}", color = Color.Green, fontSize = 10.sp)
            Text("Frames Drop: ${DebugStats.framesDropped}", color = Color.Red, fontSize = 10.sp)
            Text("Decoded: ${DebugStats.framesDecoded}", color = Color.Cyan, fontSize = 10.sp)
            if (DebugStats.lastError.isNotEmpty()) {
                Text("ERR: ${DebugStats.lastError}", color = Color.Red, fontSize = 10.sp)
            }
        }
        
        // --- HIDDEN KEYBOARD INJECTOR ---
        BasicTextField(
            value = "",
            onValueChange = { str ->
                if (str.isNotEmpty()) {
                    // Send over UDP (rudimentary ASCII mapping for PoC)
                    val char = str.last()
                    val vkCode = char.uppercaseChar().code
                    networkClient.sendKeyEvent(vkCode, true)
                    networkClient.sendKeyEvent(vkCode, false)
                }
            },
            modifier = Modifier
                .size(1.dp)
                .focusRequester(focusRequester)
                .onKeyEvent { keyEvent ->
                    // Handle Backspace, Enter, etc.
                    if (keyEvent.type == KeyEventType.KeyDown || keyEvent.type == KeyEventType.KeyUp) {
                        val isDown = keyEvent.type == KeyEventType.KeyDown
                        val vkCode = when (keyEvent.key) {
                            Key.Backspace -> 0x08
                            Key.Enter -> 0x0D
                            Key.Spacebar -> 0x20
                            else -> 0
                        }
                        if (vkCode != 0) {
                            networkClient.sendKeyEvent(vkCode, isDown)
                            return@onKeyEvent true
                        }
                    }
                    false
                }
        )

        // --- RUSTDESK FLOATING ACTION BUTTON ---
        if (!showToolbar) {
            FloatingActionButton(
                onClick = { showToolbar = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(32.dp),
                containerColor = Color(0xAA1A1A2E), // Semi-transparent
                contentColor = Color(0xFF00D4FF),
                shape = CircleShape
            ) {
                Icon(Icons.Filled.Menu, contentDescription = "Menu")
            }
        }
        
        // --- RUSTDESK TOOLBAR ---
        if (showToolbar) {
            var fpsExpanded by remember { mutableStateOf(false) }
            var qualityExpanded by remember { mutableStateOf(false) }
            var currentFps by remember { mutableStateOf(60) }
            var currentBitrate by remember { mutableStateOf(2000000) }
            var audioEnabled by remember { mutableStateOf(AudioPlayer.isEnabled) }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .background(Color(0xE01A1A2E), RoundedCornerShape(32.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                IconButton(onClick = { focusRequester.requestFocus() }) {
                    Icon(androidx.compose.material.icons.Icons.Filled.Edit, contentDescription = "Keyboard", tint = Color.White, modifier = Modifier.size(28.dp))
                }

                TextButton(onClick = { 
                    audioEnabled = !audioEnabled
                    AudioPlayer.isEnabled = audioEnabled
                }) {
                    Text(
                        if (audioEnabled) "Audio: ON" else "Audio: OFF",
                        color = if (audioEnabled) Color(0xFF00D4FF) else Color.Gray,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Box {
                    TextButton(onClick = { fpsExpanded = true }) {
                        Text("$currentFps FPS", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                    DropdownMenu(expanded = fpsExpanded, onDismissRequest = { fpsExpanded = false }) {
                        listOf(30, 45, 60, 75, 90, 105, 120, 135, 150).forEach { fps ->
                            DropdownMenuItem(
                                text = { Text("$fps FPS") },
                                onClick = { 
                                    currentFps = fps
                                    fpsExpanded = false
                                    networkClient.sendSettings(currentFps, currentBitrate)
                                }
                            )
                        }
                    }
                }

                Box {
                    TextButton(onClick = { qualityExpanded = true }) {
                        Text("Quality", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                    DropdownMenu(expanded = qualityExpanded, onDismissRequest = { qualityExpanded = false }) {
                        val qualities = listOf(
                            Pair(500000, "144p"),
                            Pair(1000000, "240p"),
                            Pair(1500000, "360p"),
                            Pair(2000000, "480p"),
                            Pair(5000000, "720p"),
                            Pair(10000000, "1080p"),
                            Pair(20000000, "1440p"),
                            Pair(40000000, "2160p"),
                            Pair(100000000, "Lossless")
                        )
                        qualities.forEach { (bitrate, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { 
                                    currentBitrate = bitrate
                                    qualityExpanded = false
                                    networkClient.sendSettings(currentFps, currentBitrate)
                                }
                            )
                        }
                    }
                }
                
                IconButton(onClick = onDisconnect) {
                    Icon(androidx.compose.material.icons.Icons.Filled.Close, contentDescription = "Disconnect", tint = Color(0xFFFF4444), modifier = Modifier.size(28.dp))
                }
                
                IconButton(onClick = { showToolbar = false }) {
                    Icon(androidx.compose.material.icons.Icons.Filled.Menu, contentDescription = "Hide Menu", tint = Color(0xFF00D4FF), modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}
