package com.directlink.client

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
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
import androidx.compose.material.icons.filled.Settings
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
    private val networkClient = NativeClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

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
fun DirectLinkApp(networkClient: NativeClient, activity: Activity) {
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
    networkClient: NativeClient,
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
    networkClient: NativeClient,
    activity: Activity,
    onDisconnect: () -> Unit
) {
    var showToolbar by remember { mutableStateOf(false) }
    
    // Keyboard Injection
    val focusRequester = remember { FocusRequester() }

    DisposableEffect(Unit) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        onDispose {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // --- VIDEO SURFACE ---
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .wrapContentSize(Alignment.BottomCenter)
                .aspectRatio(16f / 9f),
            factory = { ctx ->
                val surface = SurfaceView(ctx)
                val touchHandler = DirectTouchHandler(networkClient, surface)
                surface.setOnTouchListener(touchHandler)

                surface.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        networkClient.startNative(holder.surface)
                    }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        // C++ teardown handles it
                    }
                })
                surface
            }
        )
        
        // --- QUALITY SETTINGS GEAR ---
        var expanded by remember { mutableStateOf(false) }
        Box(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
            IconButton(
                onClick = { expanded = true },
                modifier = Modifier.background(Color(0x88000000), CircleShape)
            ) {
                Icon(Icons.Filled.Settings, contentDescription = "Quality Settings", tint = Color.White)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(Color(0xFF1E1E24))
            ) {
                DropdownMenuItem(
                    text = { Text("Low (1 Mbps)", color = Color.White) },
                    onClick = { networkClient.sendQualityChange(0); expanded = false }
                )
                DropdownMenuItem(
                    text = { Text("Medium (2 Mbps)", color = Color.White) },
                    onClick = { networkClient.sendQualityChange(1); expanded = false }
                )
                DropdownMenuItem(
                    text = { Text("High (5 Mbps)", color = Color.White) },
                    onClick = { networkClient.sendQualityChange(2); expanded = false }
                )
                DropdownMenuItem(
                    text = { Text("Ultra (15 Mbps)", color = Color.White) },
                    onClick = { networkClient.sendQualityChange(3); expanded = false }
                )
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
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .background(Color(0xEE1A1A2E), RoundedCornerShape(32.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                IconButton(onClick = { focusRequester.requestFocus() }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Keyboard", tint = Color.White)
                }
                
                IconButton(onClick = onDisconnect) {
                    Icon(Icons.Filled.Close, contentDescription = "Disconnect", tint = Color(0xFFFF4444))
                }
                
                IconButton(onClick = { showToolbar = false }) {
                    Icon(Icons.Filled.Menu, contentDescription = "Close Menu", tint = Color(0xFF00D4FF))
                }
            }
        }
    }
}
