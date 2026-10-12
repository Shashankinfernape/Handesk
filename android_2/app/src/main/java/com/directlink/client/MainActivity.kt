package com.directlink.client

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import java.util.UUID

// Modern Dark Theme Colors
private val BrandBlue = Color(0xFF0D6EFD)
private val BrandBlueVariant = Color(0xFF0A58CA)
private val DarkBg = Color(0xFF121212)
private val SurfaceDark = Color(0xFF1E1E1E)
private val SurfaceVariantDark = Color(0xFF2C2C2C)
private val TextPrimary = Color(0xFFE0E0E0)
private val TextSecondary = Color(0xFFA0A0A0)
private val ErrorRed = Color(0xFFCF6679)
private val SuccessGreen = Color(0xFF34C759)

class MainActivity : ComponentActivity() {
    private val networkClient = NativeClient()
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Setup edge-to-edge window
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            DirectLinkTheme {
                DirectLinkApp(networkClient, this)
            }
        }
    }

    fun acquireLowLatencyLocks() {
        try {
            if (wifiLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                wifiLock = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    wifiManager.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "DirectLink:LowLatency")
                } else {
                    @Suppress("DEPRECATION")
                    wifiManager.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DirectLink:HighPerf")
                }
                wifiLock?.setReferenceCounted(false)
            }
            if (wakeLock == null) {
                val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "DirectLink:WakeLock")
                wakeLock?.setReferenceCounted(false)
            }
            wifiLock?.acquire()
            wakeLock?.acquire()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun releaseLowLatencyLocks() {
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseLowLatencyLocks()
        networkClient.disconnect()
    }
}

@Composable
fun DirectLinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = DarkBg,
            surface = SurfaceDark,
            surfaceVariant = SurfaceVariantDark,
            primary = BrandBlue,
            onPrimary = Color.White,
            error = ErrorRed
        ),
        content = content
    )
}

@Composable
fun DirectLinkApp(networkClient: NativeClient, activity: Activity) {
    var isConnected by remember { mutableStateOf(networkClient.isConnected) }
    var currentTab by remember { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()

    if (!isConnected) {
        Scaffold(
            bottomBar = {
                NavigationBar(
                    containerColor = SurfaceDark,
                    contentColor = TextPrimary
                ) {
                    NavigationBarItem(
                        selected = currentTab == 0,
                        onClick = { currentTab = 0 },
                        icon = { Icon(Icons.Filled.Home, contentDescription = "Home") },
                        label = { Text("Home") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = BrandBlue,
                            selectedTextColor = BrandBlue,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = SurfaceVariantDark
                        )
                    )
                    NavigationBarItem(
                        selected = currentTab == 1,
                        onClick = { currentTab = 1 },
                        icon = { Icon(Icons.Filled.List, contentDescription = "Recent") },
                        label = { Text("Recent") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = BrandBlue,
                            selectedTextColor = BrandBlue,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = SurfaceVariantDark
                        )
                    )
                    NavigationBarItem(
                        selected = currentTab == 2,
                        onClick = { currentTab = 2 },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = BrandBlue,
                            selectedTextColor = BrandBlue,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = SurfaceVariantDark
                        )
                    )
                }
            }
        ) { paddingValues ->
            Box(modifier = Modifier.padding(paddingValues).fillMaxSize().background(DarkBg)) {
                when (currentTab) {
                    0 -> HomeTab(networkClient, activity, onConnected = { 
                        (activity as? MainActivity)?.acquireLowLatencyLocks()
                        isConnected = true 
                    })
                    1 -> RecentTab(activity, onConnect = { ip -> 
                        coroutineScope.launch {
                            val err = networkClient.connectToHost(ip)
                            if (err == null) {
                                (activity as? MainActivity)?.acquireLowLatencyLocks()
                                isConnected = true
                            }
                        }
                    })
                    2 -> SettingsTab()
                }
            }
        }
    } else {
        RemoteSessionScreen(
            networkClient = networkClient,
            activity = activity,
            onDisconnect = { 
                (activity as? MainActivity)?.releaseLowLatencyLocks()
                networkClient.disconnect()
                isConnected = false 
            }
        )
    }
}

@Composable
fun HomeTab(networkClient: NativeClient, activity: Activity, onConnected: () -> Unit) {
    val prefs = activity.getSharedPreferences("DirectLinkPrefs", Context.MODE_PRIVATE)
    var hostIp by remember { mutableStateOf(prefs.getString("last_ip", "") ?: "") }
    var isConnecting by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    
    // Auto-generate a fake ID for this device to mimic real remote desktop apps
    val localId by remember { mutableStateOf(prefs.getString("local_id", generateFakeId())?.also {
        prefs.edit().putString("local_id", it).apply()
    } ?: "000 000 000") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        // App Header
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 32.dp)) {
            Icon(Icons.Filled.Home, contentDescription = "Logo", tint = BrandBlue, modifier = Modifier.size(36.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Text("DirectLink", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        }

        // Your Desktop Card
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("This Desk", color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(localId, color = BrandBlue, fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { /* Copy to clipboard */ }) {
                        Icon(Icons.Filled.Share, contentDescription = "Copy", tint = TextSecondary)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(SuccessGreen))
                    Spacer(Modifier.width(8.dp))
                    Text("Ready for incoming connection", color = TextSecondary, fontSize = 14.sp)
                }
            }
        }

        // Connect to Remote Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Control Remote Desk", color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(16.dp))
                
                OutlinedTextField(
                    value = hostIp,
                    onValueChange = { hostIp = it },
                    placeholder = { Text("Enter Remote ID or IP", color = Color.DarkGray) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrandBlue,
                        unfocusedBorderColor = SurfaceVariantDark,
                        focusedContainerColor = SurfaceVariantDark,
                        unfocusedContainerColor = SurfaceVariantDark,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    trailingIcon = {
                        if (hostIp.isNotEmpty()) {
                            IconButton(onClick = { hostIp = "" }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear", tint = TextSecondary)
                            }
                        }
                    }
                )
                
                if (hostIp.isNotBlank()) {
                    val isTs = hostIp.trim().startsWith("100.") || hostIp.trim().contains("ts.net")
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isTs) Color(0xFF00E5FF).copy(alpha = 0.15f) else Color(0xFF00E676).copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (isTs) "⚡ TAILSCALE WAN DETECTED" else "⚡ LOCAL LAN DETECTED",
                                color = if (isTs) Color(0xFF00E5FF) else Color(0xFF00E676),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Button(
                    onClick = {
                        prefs.edit().putString("last_ip", hostIp).apply()
                        
                        // Maintain recent history
                        val historySet = prefs.getStringSet("recent_ips", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                        historySet.add(hostIp)
                        prefs.edit().putStringSet("recent_ips", historySet).apply()

                        coroutineScope.launch {
                            isConnecting = true
                            val err = networkClient.connectToHost(hostIp)
                            isConnecting = false
                            if (err == null) onConnected()
                            else errorMsg = err
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                    enabled = !isConnecting && hostIp.isNotEmpty()
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Connecting...", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Filled.ArrowForward, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Connect", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
                
                if (errorMsg.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(errorMsg, color = ErrorRed, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
fun RecentTab(activity: Activity, onConnect: (String) -> Unit) {
    val prefs = activity.getSharedPreferences("DirectLinkPrefs", Context.MODE_PRIVATE)
    val history = prefs.getStringSet("recent_ips", setOf())?.toList() ?: emptyList()

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Recent Sessions", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))

        if (history.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No recent sessions", color = TextSecondary)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(history) { ip ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onConnect(ip) },
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(48.dp).background(SurfaceVariantDark, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.Home, contentDescription = null, tint = BrandBlue)
                            }
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(ip, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text("Offline", color = TextSecondary, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsTab() {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Settings", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))

        SettingsGroup("General") {
            SettingsItem(Icons.Filled.Place, "Language", "System Default")
            SettingsItem(Icons.Filled.Face, "Theme", "Dark")
        }
        
        Spacer(Modifier.height(24.dp))
        
        SettingsGroup("Security") {
            SettingsItem(Icons.Filled.Lock, "Unattended Access", "Disabled")
            SettingsItem(Icons.Filled.Lock, "Change Password", "")
        }

        Spacer(Modifier.height(24.dp))
        
        SettingsGroup("Network") {
            SettingsItem(Icons.Filled.Share, "Direct Connection", "Enabled")
        }
    }
}

@Composable
fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, color = BrandBlue, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column {
                content()
            }
        }
    }
}

@Composable
fun SettingsItem(icon: ImageVector, title: String, subtitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 16.sp)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, color = TextSecondary, fontSize = 12.sp)
            }
        }
        Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = SurfaceVariantDark)
    }
}

@Composable
fun RemoteSessionScreen(
    networkClient: NativeClient,
    activity: Activity,
    onDisconnect: () -> Unit
) {
    val isTailscale = networkClient.isTailscale
    var showToolbar by remember { mutableStateOf(false) }
    var expandedQualityMenu by remember { mutableStateOf(false) }
    var currentQuality by remember { mutableStateOf(if (isTailscale) "Auto (Balanced WAN)" else "1080p HD") }
    var isAutoMode by remember { mutableStateOf(isTailscale) }
    var expandedFpsMenu by remember { mutableStateOf(false) }
    var currentFps by remember { mutableIntStateOf(60) }
    var isPcAudioMuted by remember { mutableStateOf(false) }
    
    val focusRequester = remember { FocusRequester() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    LaunchedEffect(networkClient) {
        networkClient.onStatusUpdateListener = { newFps, newBitrate ->
            activity.runOnUiThread {
                if (currentQuality.startsWith("Auto")) {
                    if (newFps in 15..144) {
                        currentFps = newFps
                    }
                    val matchingQuality = when {
                        newBitrate >= 40_000_000 -> "Source (Lossless)"
                        newBitrate >= 20_000_000 -> "1440p HD"
                        newBitrate >= 10_000_000 -> "1080p HD"
                        newBitrate in 5_500_000..9_999_999 -> if (isTailscale) "1080p (WAN Max)" else "1080p HD"
                        newBitrate in 4_000_000..5_499_999 -> if (isTailscale) "Auto (Balanced WAN)" else "720p"
                        newBitrate in 3_000_000..3_999_999 -> if (isTailscale) "720p HD (WAN)" else "480p"
                        newBitrate in 2_000_000..2_999_999 -> "480p"
                        newBitrate in 1_200_000..1_999_999 -> "360p"
                        newBitrate in 800_000..1_199_999 -> "240p"
                        else -> "144p"
                    }
                    if (matchingQuality.isNotEmpty() && currentQuality != matchingQuality) {
                        currentQuality = matchingQuality
                    }
                }
            }
        }
    }
    
    val prefs = activity.getSharedPreferences("DirectLinkPrefs", Context.MODE_PRIVATE)
    var isKeyboardActive by remember { mutableStateOf(false) }
    var showSpecialKeys by remember { mutableStateOf(false) }
    var isKeyboardClosingPhase by remember { mutableStateOf(false) }
    var isLandscape by remember { mutableStateOf(true) }
    var desktopWidth by remember { mutableIntStateOf(prefs.getInt("last_desktop_w", 1440)) }
    var desktopHeight by remember { mutableIntStateOf(prefs.getInt("last_desktop_h", 900)) }
    var desktopAspectRatio by remember { mutableFloatStateOf(prefs.getFloat("last_aspect_ratio", 1440f / 900f)) }
    var currentSurfaceView by remember { mutableStateOf<SurfaceView?>(null) }

    LaunchedEffect(networkClient) {
        networkClient.onResolutionUpdateListener = { width, height ->
            activity.runOnUiThread {
                if (width > 0 && height > 0) {
                    val ratio = width.toFloat() / height.toFloat()
                    android.util.Log.i("MainActivity", "Adopting Windows host aspect ratio: ${width}x${height} (ratio=$ratio)")
                    desktopWidth = width
                    desktopHeight = height
                    desktopAspectRatio = ratio
                    prefs.edit()
                        .putInt("last_desktop_w", width)
                        .putInt("last_desktop_h", height)
                        .putFloat("last_aspect_ratio", ratio)
                        .apply()
                    currentSurfaceView?.holder?.setFixedSize(width, height)
                }
            }
        }
    }
    val activeModifiers = remember { mutableStateListOf<Int>() }
    var textBuffer by remember { mutableStateOf("") }
    
    val viewportScale = remember { androidx.compose.animation.core.Animatable(1f) }
    val viewportOffsetX = remember { androidx.compose.animation.core.Animatable(0f) }
    val viewportOffsetY = remember { androidx.compose.animation.core.Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    
    fun releaseAllRemoteKeys() {
        activeModifiers.forEach { networkClient.sendKeyEvent(it, false) }
        activeModifiers.clear()
    }

    fun toggleKeyboard() {
        isKeyboardActive = !isKeyboardActive
        if (isKeyboardActive) {
            showSpecialKeys = true // auto-show special keys when typing
            focusRequester.requestFocus()
        } else {
            focusManager.clearFocus()
            releaseAllRemoteKeys()
        }
        showToolbar = false
    }

    // Handle Orientation
    LaunchedEffect(isLandscape) {
        activity.requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            releaseAllRemoteKeys()
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    BackHandler {
        releaseAllRemoteKeys()
        onDisconnect()
    }

    // --- HIDDEN KEYBOARD INJECTOR ---
    BasicTextField(
        value = textBuffer,
        onValueChange = { newStr ->
            if (!isKeyboardActive) return@BasicTextField
            if (newStr.length < textBuffer.length) {
                // Backspace detected via string shrinkage
                val diff = textBuffer.length - newStr.length
                repeat(diff) {
                    networkClient.sendKeyEvent(0x08, true)
                    networkClient.sendKeyEvent(0x08, false)
                }
            } else if (newStr.length > textBuffer.length) {
                // Characters added
                val diffStr = newStr.substring(textBuffer.length)
                for (newChar in diffStr) {
                    val vkCode = VkMapper.getVkCode(newChar)
                    val needsShift = VkMapper.requiresShift(newChar)
                    
                    if (needsShift && !activeModifiers.contains(0x10)) {
                        networkClient.sendKeyEvent(0x10, true) // Shift down
                    }
                    
                    networkClient.sendKeyEvent(vkCode, true)
                    networkClient.sendKeyEvent(vkCode, false)
                    
                    if (needsShift && !activeModifiers.contains(0x10)) {
                        networkClient.sendKeyEvent(0x10, false) // Shift up
                    }
                }
            }
            textBuffer = newStr
        },
        keyboardOptions = KeyboardOptions(autoCorrect = false),
        modifier = Modifier
            .size(1.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { state ->
                if (!state.isFocused && isKeyboardActive) {
                    isKeyboardActive = false
                    showSpecialKeys = false
                    isKeyboardClosingPhase = false
                }
            }
            .onKeyEvent { keyEvent ->
                if (!isKeyboardActive) return@onKeyEvent false
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

    var hasStartedNative by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black).imePadding()) {
        
        BoxWithConstraints(modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var isTransforming = false
                    var startCentroid = Offset.Zero
                    var lastCentroid = Offset.Zero
                    var lastSpan = 0f
                    var startTime = 0L

                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size == 3) {
                            event.changes.forEach { it.consume() } // Isolate from AndroidView
                            
                            var cx = 0f; var cy = 0f
                            event.changes.forEach { cx += it.position.x; cy += it.position.y }
                            val centroid = Offset(cx / 3f, cy / 3f)
                            
                            var span = 0f
                            event.changes.forEach { span += (it.position - centroid).getDistance() }
                            span /= 3f

                            if (!isTransforming) {
                                isTransforming = true
                                startCentroid = centroid
                                lastCentroid = centroid
                                lastSpan = span
                                startTime = System.currentTimeMillis()
                            } else {
                                val timeElapsed = System.currentTimeMillis() - startTime
                                val distMoved = centroid - startCentroid
                                // Swipe Detection (Reset Viewport only)
                                if (timeElapsed < 400 && distMoved.getDistance() > 60f && abs(span - lastSpan) < 80f) {
                                    val isVertical = abs(distMoved.y) > abs(distMoved.x)
                                    if (!isVertical) {
                                        // Swipe Horizontal -> Reset Viewport
                                        coroutineScope.launch {
                                            launch { viewportScale.animateTo(1f, animationSpec = androidx.compose.animation.core.tween(300)) }
                                            launch { viewportOffsetX.animateTo(0f, animationSpec = androidx.compose.animation.core.tween(300)) }
                                            launch { viewportOffsetY.animateTo(0f, animationSpec = androidx.compose.animation.core.tween(300)) }
                                        }
                                    }
                                    // Wait until fingers are lifted
                                    while (awaitPointerEvent().changes.any { it.pressed }) {
                                        awaitPointerEvent().changes.forEach { it.consume() }
                                    }
                                    break
                                } else if (timeElapsed > 150 || abs(span - lastSpan) > 10f) {
                                    // Live Zoom and Pan
                                    val zoomDelta = if (lastSpan > 0) span / lastSpan else 1f
                                    val panDelta = centroid - lastCentroid
                                    
                                    coroutineScope.launch {
                                        val oldScale = viewportScale.value
                                        val newScale = (oldScale * zoomDelta).coerceIn(1f, 10f)
                                        val actualZoom = newScale / oldScale
                                        
                                        if (newScale <= 1.01f) { // Snap to reset if zoomed out
                                            viewportScale.snapTo(1f)
                                            viewportOffsetX.snapTo(0f)
                                            viewportOffsetY.snapTo(0f)
                                        } else {
                                            viewportScale.snapTo(newScale)
                                            viewportOffsetX.snapTo((viewportOffsetX.value - centroid.x) * actualZoom + centroid.x + panDelta.x)
                                            viewportOffsetY.snapTo((viewportOffsetY.value - centroid.y) * actualZoom + centroid.y + panDelta.y)
                                        }
                                    }
                                    lastCentroid = centroid
                                    lastSpan = span
                                }
                            }
                        } else {
                            isTransforming = false
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
        ) {
            val containerWidth = maxWidth.value
            val containerHeight = maxHeight.value
            val containerAspect = if (containerHeight > 0f) containerWidth / containerHeight else 16f / 9f

            // Dynamic Fit Mode: letterbox or pillarbox to keep PC's true aspect ratio
            val fitModifier = if (containerAspect > desktopAspectRatio) {
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(desktopAspectRatio, matchHeightConstraintsFirst = true)
            } else {
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(desktopAspectRatio, matchHeightConstraintsFirst = false)
            }

            // --- VIDEO SURFACE ---
            AndroidView(
                modifier = fitModifier
                    .graphicsLayer {
                        scaleX = viewportScale.value
                        scaleY = viewportScale.value
                        translationX = viewportOffsetX.value
                        translationY = viewportOffsetY.value
                        transformOrigin = TransformOrigin(0f, 0f)
                    },
                factory = { ctx ->
                    val surface = SurfaceView(ctx)
                    currentSurfaceView = surface
                    if (desktopWidth > 0 && desktopHeight > 0) {
                        surface.holder.setFixedSize(desktopWidth, desktopHeight)
                    }
                    val touchHandler = DirectTouchHandler(networkClient, surface)
                    touchHandler.onTwoFingerSingleTap = {
                        activity.runOnUiThread {
                            if (!isKeyboardActive) {
                                // 1st tap: bring keyboard up alone
                                isKeyboardActive = true
                                showSpecialKeys = false
                                isKeyboardClosingPhase = false
                                focusRequester.requestFocus()
                            } else if (!showSpecialKeys && !isKeyboardClosingPhase) {
                                // 2nd tap: bring special keys up
                                showSpecialKeys = true
                            } else if (showSpecialKeys) {
                                // 3rd tap (closing): special keys get down
                                showSpecialKeys = false
                                isKeyboardClosingPhase = true
                            } else if (isKeyboardClosingPhase) {
                                // 4th tap (closing): keyboard gets down
                                isKeyboardActive = false
                                isKeyboardClosingPhase = false
                                focusManager.clearFocus()
                                releaseAllRemoteKeys()
                            }
                        }
                    }
                    surface.tag = touchHandler
                    surface.setOnTouchListener(touchHandler)
                    surface.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            if (desktopWidth > 0 && desktopHeight > 0) {
                                holder.setFixedSize(desktopWidth, desktopHeight)
                            }
                            if (!hasStartedNative) {
                                networkClient.startNative(holder.surface)
                                hasStartedNative = true
                            } else {
                                networkClient.updateSurface(holder.surface)
                            }
                        }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            networkClient.updateSurface(null)
                        }
                    })
                    surface
                },
                update = { view ->
                    val handler = view.tag as? DirectTouchHandler
                    handler?.updateTransform(viewportScale.value, viewportOffsetX.value, viewportOffsetY.value)
                }
            )

            // --- INTERACTIVE TOOLBAR ---
            Box(modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp)) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = showToolbar,
                    enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
                ) {
                Row(
                    modifier = Modifier
                        .background(Color(0xD91E1E1E), RoundedCornerShape(32.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // System Keyboard
                    ToolbarIconButton(
                        icon = Icons.Filled.Edit,
                        label = "Keyboard",
                        tint = if (isKeyboardActive) BrandBlue else TextPrimary,
                        onClick = { toggleKeyboard() }
                    )
                    
                    // Special Keys Toggle
                    ToolbarIconButton(
                        icon = Icons.Filled.Menu,
                        label = "Special Keys",
                        tint = if (showSpecialKeys) BrandBlue else TextPrimary,
                        onClick = { 
                            showSpecialKeys = !showSpecialKeys 
                            showToolbar = false
                        }
                    )
                    
                    // Orientation Toggle
                    ToolbarIconButton(
                        icon = Icons.Filled.Refresh,
                        label = "Rotate",
                        tint = if (isLandscape) TextPrimary else BrandBlue,
                        onClick = { 
                            isLandscape = !isLandscape 
                            showToolbar = false
                        }
                    )
                    
                    // Network Mode Badge
                    ToolbarIconButton(
                        icon = Icons.Filled.Share,
                        label = if (isTailscale) "WAN" else "LAN",
                        tint = if (isTailscale) Color(0xFF00E5FF) else Color(0xFF00E676),
                        onClick = { }
                    )

                    // Display Quality
                    Box {
                        ToolbarIconButton(
                            icon = Icons.Filled.Settings,
                            label = "Quality",
                            onClick = { expandedQualityMenu = true }
                        )
                        
                        DropdownMenu(
                            expanded = expandedQualityMenu,
                            onDismissRequest = { expandedQualityMenu = false },
                            modifier = Modifier.background(SurfaceDark).width(220.dp)
                        ) {
                            val options = if (isTailscale) {
                                listOf(
                                    Triple("Auto (Balanced WAN)", 60, 4_500_000),
                                    Triple("1080p (WAN Max)", 60, 6_000_000),
                                    Triple("720p HD (WAN)", 60, 4_000_000),
                                    Triple("480p (Smooth)", 45, 2_500_000),
                                    Triple("360p (Saver)", 30, 1_500_000)
                                )
                            } else {
                                listOf(
                                    Triple("Source (Lossless)", 144, 50_000_000),
                                    Triple("1440p HD", 120, 25_000_000),
                                    Triple("1080p HD", 60, 12_000_000),
                                    Triple("720p", 60, 5_000_000),
                                    Triple("480p", 60, 3_500_000),
                                    Triple("Auto (Adaptive)", 60, 8_000_000)
                                )
                            }
                            options.forEach { (label, fps, bitrate) ->
                                DropdownMenuItem(
                                    text = { 
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (currentQuality == label) {
                                                Icon(Icons.Filled.Check, contentDescription = null, tint = BrandBlue, modifier = Modifier.size(18.dp))
                                            } else {
                                                Spacer(Modifier.width(18.dp))
                                            }
                                            Spacer(Modifier.width(12.dp))
                                            Text(label, color = TextPrimary, fontSize = 14.sp)
                                        }
                                    },
                                    onClick = {
                                        currentQuality = label
                                        currentFps = fps
                                        val isAuto = label.startsWith("Auto")
                                        networkClient.sendSettings(fps, bitrate, isAuto)
                                        expandedQualityMenu = false
                                        showToolbar = false
                                    }
                                )
                            }
                        }
                    }
                    
                    // Frame Rate
                    Box {
                        ToolbarIconButton(
                            icon = Icons.Filled.List,
                            label = "${currentFps} FPS",
                            onClick = { expandedFpsMenu = true }
                        )

                        DropdownMenu(
                            expanded = expandedFpsMenu,
                            onDismissRequest = { expandedFpsMenu = false },
                            modifier = Modifier.background(SurfaceDark).width(160.dp)
                        ) {
                            val fpsOptions = if (isTailscale) listOf(60, 45, 30) else listOf(144, 120, 90, 60, 30)
                            fpsOptions.forEach { fps ->
                                DropdownMenuItem(
                                    text = { 
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (currentFps == fps) {
                                                Icon(Icons.Filled.Check, contentDescription = null, tint = BrandBlue, modifier = Modifier.size(18.dp))
                                            } else {
                                                Spacer(Modifier.width(18.dp))
                                            }
                                            Spacer(Modifier.width(12.dp))
                                            Text("$fps FPS", color = TextPrimary, fontSize = 14.sp)
                                        }
                                    },
                                    onClick = {
                                        currentFps = fps
                                        val curBitrate = when (currentQuality) {
                                            "Source (Lossless)" -> 50_000_000
                                            "1440p HD" -> 25_000_000
                                            "1080p HD" -> 12_000_000
                                            "1080p (WAN Max)" -> 6_000_000
                                            "Auto (Balanced WAN)" -> 4_500_000
                                            "720p HD (WAN)" -> 4_000_000
                                            "720p" -> 5_000_000
                                            "480p (Smooth)", "480p" -> 2_500_000
                                            "360p (Saver)", "360p" -> 1_500_000
                                            else -> 4_500_000
                                        }
                                        networkClient.sendSettings(fps, curBitrate, isAuto = false)
                                        expandedFpsMenu = false
                                        showToolbar = false
                                    }
                                )
                            }
                        }
                    }

                    // Audio PC Toggle
                    ToolbarIconButton(
                        icon = if (isPcAudioMuted) Icons.Filled.Close else Icons.Filled.PlayArrow,
                        label = "Audio",
                        tint = if (isPcAudioMuted) TextSecondary else SuccessGreen,
                        onClick = { 
                            isPcAudioMuted = !isPcAudioMuted
                            networkClient.togglePcMute() 
                        }
                    )

                    // Divider
                    Box(modifier = Modifier.height(24.dp).width(1.dp).background(SurfaceVariantDark))
                    
                    // Disconnect
                    ToolbarIconButton(
                        icon = Icons.Filled.Close,
                        label = "Disconnect",
                        tint = ErrorRed,
                        onClick = {
                            releaseAllRemoteKeys()
                            onDisconnect()
                        }
                    )
                    
                    // Hide Toolbar
                    ToolbarIconButton(
                        icon = Icons.Filled.KeyboardArrowUp,
                        label = "Hide",
                        onClick = { showToolbar = false }
                    )
                }
            }
            } // Close Box

            // --- FLOATING MENU TOGGLE ---
            if (!showToolbar) {
                IconButton(
                    onClick = { showToolbar = true },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .background(
                            color = Color(0x66000000),
                            shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
                        )
                        .padding(horizontal = 24.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Menu", tint = Color.White)
                }
            }
        }
        
        // --- SPECIAL KEYS TOOLBAR ---
        if (showSpecialKeys) {
            Box(modifier = Modifier.fillMaxWidth().background(Color(0xFF1E1E1E))) {
                SpecialKeysToolbar(
                    activeModifiers = activeModifiers.toSet(),
                    onToggleModifier = { vk ->
                        if (activeModifiers.contains(vk)) {
                            networkClient.sendKeyEvent(vk, false)
                            activeModifiers.remove(vk)
                        } else {
                            networkClient.sendKeyEvent(vk, true)
                            activeModifiers.add(vk)
                        }
                    },
                    onTapKey = { vk ->
                        networkClient.sendKeyEvent(vk, true)
                        networkClient.sendKeyEvent(vk, false)
                    }
                )
            }
        }
    }
}

@Composable
fun ToolbarIconButton(
    icon: ImageVector,
    label: String,
    tint: Color = TextPrimary,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp).clip(CircleShape)
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
    }
}

private fun generateFakeId(): String {
    val rng = java.util.Random()
    return "${rng.nextInt(900)+100} ${rng.nextInt(900)+100} ${rng.nextInt(900)+100}"
}
