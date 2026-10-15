package com.directlink.client

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.unit.IntOffset
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
private const val PHANTOM_KEYBOARD_BUFFER = "                              " // 30 spaces for bulletproof backspace detection

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
            HandeskTheme {
                HandeskApp(networkClient, this)
            }
        }
    }

    fun acquireLowLatencyLocks() {
        try {
            if (wifiLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                wifiLock = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    wifiManager.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Handesk:LowLatency")
                } else {
                    @Suppress("DEPRECATION")
                    wifiManager.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Handesk:HighPerf")
                }
                wifiLock?.setReferenceCounted(false)
            }
            if (wakeLock == null) {
                val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Handesk:WakeLock")
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
fun HandeskTheme(content: @Composable () -> Unit) {
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
fun HandeskApp(networkClient: NativeClient, activity: Activity) {
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
    val prefs = activity.getSharedPreferences("HandeskPrefs", Context.MODE_PRIVATE).also { p ->
        if (!p.contains("last_ip")) {
            val old = activity.getSharedPreferences("DirectLinkPrefs", Context.MODE_PRIVATE)
            old.getString("last_ip", null)?.let { p.edit().putString("last_ip", it).apply() }
            old.getString("local_id", null)?.let { p.edit().putString("local_id", it).apply() }
        }
    }
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
            Text("Handesk", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
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
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
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
                        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                        imm?.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                        activity.currentFocus?.clearFocus()

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
    val prefs = activity.getSharedPreferences("HandeskPrefs", Context.MODE_PRIVATE).also { p ->
        if (!p.contains("recent_ips")) {
            val old = activity.getSharedPreferences("DirectLinkPrefs", Context.MODE_PRIVATE)
            old.getStringSet("recent_ips", null)?.let { p.edit().putStringSet("recent_ips", it).apply() }
        }
    }
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
    var activeGestureMode by remember { mutableStateOf(GestureMode.getSavedMode(activity)) }
    var expandedGestureMenu by remember { mutableStateOf(false) }
    var showGestureHelpDialog by remember { mutableStateOf(false) }
    var cursorNormX by remember { mutableFloatStateOf(0.5f) }
    var cursorNormY by remember { mutableFloatStateOf(0.5f) }
    var currentTouchHandler by remember { mutableStateOf<DirectTouchHandler?>(null) }
    
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
    
    val prefs = activity.getSharedPreferences("HandeskPrefs", Context.MODE_PRIVATE).also { p ->
        if (!p.contains("last_desktop_w")) {
            val old = activity.getSharedPreferences("DirectLinkPrefs", Context.MODE_PRIVATE)
            if (old.contains("last_desktop_w")) p.edit().putInt("last_desktop_w", old.getInt("last_desktop_w", 1440)).apply()
            if (old.contains("last_desktop_h")) p.edit().putInt("last_desktop_h", old.getInt("last_desktop_h", 900)).apply()
        }
    }
    var isKeyboardActive by remember { mutableStateOf(false) }
    var showSpecialKeys by remember { mutableStateOf(false) }
    var userDismissedKeyboard by remember { mutableStateOf(true) }
    var isAutoKeyboardEnabled by remember { mutableStateOf(prefs.getBoolean("auto_keyboard_enabled", true)) }
    var isLandscape by remember { mutableStateOf(true) }
    var desktopWidth by remember { mutableIntStateOf(prefs.getInt("last_desktop_w", 1440)) }
    var desktopHeight by remember { mutableIntStateOf(prefs.getInt("last_desktop_h", 900)) }
    var desktopAspectRatio by remember { mutableFloatStateOf(prefs.getFloat("last_aspect_ratio", 1440f / 900f)) }
    var currentSurfaceView by remember { mutableStateOf<SurfaceView?>(null) }
    var showSelectionToolbar by remember { mutableStateOf(false) }
    var selectionMenuX by remember { mutableFloatStateOf(0f) }
    var selectionMenuY by remember { mutableFloatStateOf(0f) }
    var selectionToolbarDismissJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val sendCtrlShortcut: (Int) -> Unit = { vkKey ->
        networkClient.sendKeyEvent(0x11, true)
        networkClient.sendKeyEvent(vkKey, true)
        networkClient.sendKeyEvent(vkKey, false)
        networkClient.sendKeyEvent(0x11, false)
    }

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

    LaunchedEffect(networkClient, isAutoKeyboardEnabled) {
        networkClient.onTextFocusUpdateListener = { focused, _, _, _, _ ->
            if (isAutoKeyboardEnabled) {
                activity.runOnUiThread {
                    if (focused) {
                        if (!isKeyboardActive) {
                            userDismissedKeyboard = false
                            isKeyboardActive = true
                            focusRequester.requestFocus()
                            keyboardController?.show()
                            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                            imm?.showSoftInput(activity.currentFocus ?: activity.window.decorView, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                        }
                    }
                }
            }
        }
    }

    val activeModifiers = remember { mutableStateListOf<Int>() }
    var textFieldState by remember {
        mutableStateOf(
            TextFieldValue(
                text = PHANTOM_KEYBOARD_BUFFER,
                selection = TextRange(PHANTOM_KEYBOARD_BUFFER.length)
            )
        )
    }
    var lastKeyEventBackspaceTime by remember { mutableLongStateOf(0L) }
    
    val viewportScale = remember { androidx.compose.animation.core.Animatable(1f) }
    val viewportOffsetX = remember { androidx.compose.animation.core.Animatable(0f) }
    val viewportOffsetY = remember { androidx.compose.animation.core.Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    
    fun releaseAllRemoteKeys() {
        activeModifiers.forEach { networkClient.sendKeyEvent(it, false) }
        activeModifiers.clear()
    }

    fun toggleNormalKeyboard() {
        if (isKeyboardActive || showSpecialKeys) {
            isKeyboardActive = false
            showSpecialKeys = false
            userDismissedKeyboard = true
            focusManager.clearFocus()
            keyboardController?.hide()
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            releaseAllRemoteKeys()
        } else {
            userDismissedKeyboard = false
            textFieldState = TextFieldValue(
                text = PHANTOM_KEYBOARD_BUFFER,
                selection = TextRange(PHANTOM_KEYBOARD_BUFFER.length)
            )
            isKeyboardActive = true
            focusRequester.requestFocus()
            keyboardController?.show()
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(activity.currentFocus ?: activity.window.decorView, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
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

    // --- HIDDEN KEYBOARD INJECTOR WITH PHANTOM BUFFER (100% RELIABLE BACKSPACE & NATIVE ENTER) ---
    BasicTextField(
        value = textFieldState,
        onValueChange = { newVal ->
            if (!isKeyboardActive) return@BasicTextField
            
            val oldLen = textFieldState.text.length
            val newLen = newVal.text.length

            if (newLen < oldLen) {
                // Backspace detected via string shrinkage!
                val now = System.currentTimeMillis()
                if (now - lastKeyEventBackspaceTime > 50) {
                    val diff = oldLen - newLen
                    repeat(diff) {
                        networkClient.sendKeyEvent(0x08, true)
                        networkClient.sendKeyEvent(0x08, false)
                    }
                }
            } else if (newLen > oldLen) {
                // Characters added via soft keyboard (supports all unicode, emojis, accents, Enter)
                val addedText = if (newVal.text.startsWith(PHANTOM_KEYBOARD_BUFFER)) {
                    newVal.text.substring(PHANTOM_KEYBOARD_BUFFER.length)
                } else if (newVal.text.length > PHANTOM_KEYBOARD_BUFFER.length) {
                    newVal.text.takeLast(newVal.text.length - PHANTOM_KEYBOARD_BUFFER.length)
                } else {
                    newVal.text
                }

                if (addedText.isNotEmpty()) {
                    if (addedText == "\n" || addedText == "\r\n") {
                        networkClient.sendKeyEvent(0x0D, true)
                        networkClient.sendKeyEvent(0x0D, false)
                    } else {
                        val parts = addedText.split(Regex("\r?\n"))
                        parts.forEachIndexed { index, part ->
                            if (part.isNotEmpty()) {
                                networkClient.sendTextCommit(part)
                            }
                            if (index < parts.size - 1) {
                                networkClient.sendKeyEvent(0x0D, true)
                                networkClient.sendKeyEvent(0x0D, false)
                            }
                        }
                    }
                }
            }

            // Immediately reset phantom buffer so Backspace NEVER runs out of characters!
            textFieldState = TextFieldValue(
                text = PHANTOM_KEYBOARD_BUFFER,
                selection = TextRange(PHANTOM_KEYBOARD_BUFFER.length)
            )
        },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrect = false,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.None
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                networkClient.sendKeyEvent(0x0D, true)
                networkClient.sendKeyEvent(0x0D, false)
            },
            onGo = {
                networkClient.sendKeyEvent(0x0D, true)
                networkClient.sendKeyEvent(0x0D, false)
            },
            onNext = {
                networkClient.sendKeyEvent(0x0D, true)
                networkClient.sendKeyEvent(0x0D, false)
            },
            onSend = {
                networkClient.sendKeyEvent(0x0D, true)
                networkClient.sendKeyEvent(0x0D, false)
            },
            onSearch = {
                networkClient.sendKeyEvent(0x0D, true)
                networkClient.sendKeyEvent(0x0D, false)
            }
        ),
        singleLine = false,
        modifier = Modifier
            .size(1.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { state ->
                if (!state.isFocused && isKeyboardActive) {
                    isKeyboardActive = false
                    userDismissedKeyboard = true
                }
            }
            .onKeyEvent { keyEvent ->
                if (!isKeyboardActive) return@onKeyEvent false
                if (keyEvent.type == KeyEventType.KeyDown || keyEvent.type == KeyEventType.KeyUp) {
                    val isDown = keyEvent.type == KeyEventType.KeyDown
                    val vkCode = when (keyEvent.key) {
                        Key.Backspace -> {
                            if (isDown) lastKeyEventBackspaceTime = System.currentTimeMillis()
                            0x08
                        }
                        Key.Enter, Key.NumPadEnter -> 0x0D
                        Key.Spacebar -> 0x20
                        Key.Tab -> 0x09
                        Key.Escape -> 0x1B
                        Key.DirectionUp -> 0x26
                        Key.DirectionDown -> 0x28
                        Key.DirectionLeft -> 0x25
                        Key.DirectionRight -> 0x27
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
        ) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val containerWidthPx = with(density) { maxWidth.toPx() }
            val containerHeightPx = with(density) { maxHeight.toPx() }
            val containerAspect = if (containerHeightPx > 0f) containerWidthPx / containerHeightPx else 16f / 9f

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

            // --- 1. VIDEO SURFACE ---
            Box(
                modifier = fitModifier
                    .align(Alignment.Center)
                    .graphicsLayer {
                        scaleX = viewportScale.value
                        scaleY = viewportScale.value
                        translationX = viewportOffsetX.value
                        translationY = viewportOffsetY.value
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val surface = SurfaceView(ctx)
                        currentSurfaceView = surface
                        if (desktopWidth > 0 && desktopHeight > 0) {
                            surface.holder.setFixedSize(desktopWidth, desktopHeight)
                        }
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
                    }
                )
            }

            // --- 2. FULL-SCREEN TOUCH CAPTURE OVERLAY (ZERO CLIPPING & MATHEMATICALLY ACCURATE) ---
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val overlayView = View(ctx)
                    val touchHandler = DirectTouchHandler(networkClient, overlayView)
                    touchHandler.currentMode = activeGestureMode
                    touchHandler.updateLayout(containerWidthPx, containerHeightPx, desktopAspectRatio)
                    touchHandler.updateTransform(viewportScale.value, viewportOffsetX.value, viewportOffsetY.value)
                    touchHandler.onCursorPositionChanged = { nx, ny ->
                        cursorNormX = nx
                        cursorNormY = ny
                    }
                    var viewportJob: kotlinx.coroutines.Job? = null
                    touchHandler.onDirectViewportChange = { newScale, newOffsetX, newOffsetY ->
                        viewportJob?.cancel()
                        viewportJob = coroutineScope.launch {
                            viewportScale.snapTo(newScale)
                            viewportOffsetX.snapTo(newOffsetX)
                            viewportOffsetY.snapTo(newOffsetY)
                        }
                    }
                    touchHandler.onRequestSnapReset = {
                        coroutineScope.launch {
                            launch {
                                viewportScale.animateTo(
                                    targetValue = 1f,
                                    animationSpec = androidx.compose.animation.core.spring(
                                        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                                        stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                                    )
                                )
                            }
                            launch {
                                viewportOffsetX.animateTo(
                                    targetValue = 0f,
                                    animationSpec = androidx.compose.animation.core.spring(
                                        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                                        stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                                    )
                                )
                            }
                            launch {
                                viewportOffsetY.animateTo(
                                    targetValue = 0f,
                                    animationSpec = androidx.compose.animation.core.spring(
                                        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                                        stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                                    )
                                )
                            }
                        }
                    }
                    touchHandler.onViewportTransformUpdate = { zoomDelta, panDeltaX, panDeltaY, focalX, focalY ->
                        coroutineScope.launch {
                            val oldScale = viewportScale.value
                            val newScale = (oldScale * zoomDelta).coerceIn(1f, 6f)
                            val actualZoom = newScale / oldScale
                            val focalRelX = focalX - touchHandler.touchTransform.contentLeft
                            val focalRelY = focalY - touchHandler.touchTransform.contentTop
                            val rawX = focalRelX - (focalRelX - viewportOffsetX.value) * actualZoom + panDeltaX
                            val rawY = focalRelY - (focalRelY - viewportOffsetY.value) * actualZoom + panDeltaY
                            val (clampedX, clampedY) = touchHandler.touchTransform.clampOffsets(newScale, rawX, rawY)
                            viewportScale.snapTo(newScale)
                            viewportOffsetX.snapTo(clampedX)
                            viewportOffsetY.snapTo(clampedY)
                            touchHandler.updateTransform(newScale, clampedX, clampedY)
                        }
                    }
                    touchHandler.onUserInteraction = {
                        userDismissedKeyboard = false
                    }
                    touchHandler.onTwoFingerSingleTap = {
                        activity.runOnUiThread {
                            toggleNormalKeyboard()
                        }
                    }
                    touchHandler.onTwoFingerDoubleTap = {
                        activity.runOnUiThread {
                            toggleNormalKeyboard()
                        }
                    }
                    touchHandler.onTextSelectionDetected = { sx, sy ->
                        activity.runOnUiThread {
                            selectionMenuX = sx
                            selectionMenuY = sy
                            showSelectionToolbar = true
                            selectionToolbarDismissJob?.cancel()
                            selectionToolbarDismissJob = coroutineScope.launch {
                                kotlinx.coroutines.delay(7000)
                                showSelectionToolbar = false
                            }
                        }
                    }
                    touchHandler.onSingleTapDetected = {
                        activity.runOnUiThread {
                            if (showSelectionToolbar) {
                                showSelectionToolbar = false
                                selectionToolbarDismissJob?.cancel()
                            }
                        }
                    }
                    overlayView.tag = touchHandler
                    overlayView.setOnTouchListener(touchHandler)
                    currentTouchHandler = touchHandler
                    overlayView
                },
                update = { view ->
                    val handler = view.tag as? DirectTouchHandler
                    handler?.updateLayout(containerWidthPx, containerHeightPx, desktopAspectRatio)
                    handler?.updateTransform(viewportScale.value, viewportOffsetX.value, viewportOffsetY.value)
                    handler?.currentMode = activeGestureMode
                }
            )

            // --- 3. VIRTUAL MOUSE POINTER (SHARP, EXACT DESKTOP POSITION, NO CLIPPING) ---
            if (activeGestureMode == GestureMode.MOUSE) {
                val pointerScreenX = currentTouchHandler?.touchTransform?.normToScreenX(cursorNormX) ?: 0f
                val pointerScreenY = currentTouchHandler?.touchTransform?.normToScreenY(cursorNormY) ?: 0f
                VirtualMousePointer(
                    screenPxX = pointerScreenX,
                    screenPxY = pointerScreenY
                )
            }

            // --- 3.5. FLOATING TEXT SELECTION TOOLBAR (ANDROID-STYLE SELECTION ACTIONS) ---
            androidx.compose.animation.AnimatedVisibility(
                visible = showSelectionToolbar,
                enter = fadeIn() + scaleIn(initialScale = 0.85f),
                exit = fadeOut() + scaleOut(targetScale = 0.85f)
            ) {
                FloatingTextSelectionToolbar(
                    posX = selectionMenuX,
                    posY = selectionMenuY,
                    containerWidth = containerWidthPx,
                    containerHeight = containerHeightPx,
                    onCut = {
                        sendCtrlShortcut(0x58) // Ctrl+X
                        Toast.makeText(activity, "Cut", Toast.LENGTH_SHORT).show()
                        showSelectionToolbar = false
                    },
                    onCopy = {
                        sendCtrlShortcut(0x43) // Ctrl+C
                        Toast.makeText(activity, "Copied", Toast.LENGTH_SHORT).show()
                        showSelectionToolbar = false
                    },
                    onPaste = {
                        sendCtrlShortcut(0x56) // Ctrl+V
                        showSelectionToolbar = false
                    },
                    onSelectAll = {
                        sendCtrlShortcut(0x41) // Ctrl+A
                        selectionToolbarDismissJob?.cancel()
                        selectionToolbarDismissJob = coroutineScope.launch {
                            kotlinx.coroutines.delay(7000)
                            showSelectionToolbar = false
                        }
                    }
                )
            }

            // --- 4. TOP-RIGHT FLOATING CONTROLS (RESET ZOOM) ---
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 16.dp, top = 16.dp)
            ) {
                // Reset Zoom Tube (Visible when zoomed in)
                androidx.compose.animation.AnimatedVisibility(
                    visible = viewportScale.value > 1.05f,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut()
                ) {
                    Surface(
                        onClick = {
                            coroutineScope.launch {
                                launch {
                                    viewportScale.animateTo(
                                        targetValue = 1f,
                                        animationSpec = androidx.compose.animation.core.spring(
                                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                                            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                                        )
                                    )
                                }
                                launch {
                                    viewportOffsetX.animateTo(
                                        targetValue = 0f,
                                        animationSpec = androidx.compose.animation.core.spring(
                                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                                            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                                        )
                                    )
                                }
                                launch {
                                    viewportOffsetY.animateTo(
                                        targetValue = 0f,
                                        animationSpec = androidx.compose.animation.core.spring(
                                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                                            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                                        )
                                    )
                                }
                                currentTouchHandler?.updateTransform(1f, 0f, 0f)
                            }
                        },
                        shape = RoundedCornerShape(32.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, Brush.horizontalGradient(listOf(Color(0x80FFFFFF), Color(0x33FFFFFF)))),
                        shadowElevation = 8.dp
                    ) {
                        Box(
                            modifier = Modifier.background(
                                Brush.horizontalGradient(
                                    listOf(
                                        Color(0xFF0052D4),
                                        Color(0xFF0D6EFD),
                                        Color(0xFF00B4D8)
                                    )
                                )
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 5.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // High-tech black circle dial with white 'X'
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(Color(0xFF0B0E14), CircleShape)
                                        .border(BorderStroke(1.dp, Color(0x33FFFFFF)), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Reset Zoom",
                                        tint = Color.White,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }

                                Spacer(Modifier.width(8.dp))

                                Text(
                                    text = "Reset",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.4.sp
                                )

                                Spacer(Modifier.width(8.dp))

                                // Futuristic Live Zoom Badge Pill
                                Box(
                                    modifier = Modifier
                                        .background(Color(0x33000000), RoundedCornerShape(12.dp))
                                        .border(BorderStroke(0.5.dp, Color(0x26FFFFFF)), RoundedCornerShape(12.dp))
                                        .padding(horizontal = 7.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = String.format(java.util.Locale.US, "%.1fx", viewportScale.value),
                                        color = Color(0xFFE0F7FA),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 0.2.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

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
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // System Keyboard (Google Keyboard Icon)
                    ToolbarIconButton(
                        icon = Icons.Rounded.Keyboard,
                        label = "Keyboard",
                        tint = if (isKeyboardActive) BrandBlue else TextPrimary,
                        onClick = { toggleNormalKeyboard() }
                    )
                    
                    // Keyboard Auto-Popup Toggle (Turn ON / OFF in Control Panel)
                    Surface(
                        onClick = { 
                            isAutoKeyboardEnabled = !isAutoKeyboardEnabled
                            prefs.edit().putBoolean("auto_keyboard_enabled", isAutoKeyboardEnabled).apply()
                            Toast.makeText(
                                activity,
                                if (isAutoKeyboardEnabled) "Keyboard Auto-Popup: ON" else "Keyboard Auto-Popup: OFF",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        shape = RoundedCornerShape(20.dp),
                        color = Color.Transparent,
                        border = BorderStroke(
                            1.dp,
                            if (isAutoKeyboardEnabled) Brush.horizontalGradient(listOf(Color(0xFF00E5FF), Color(0xFF0D6EFD)))
                            else Brush.horizontalGradient(listOf(Color(0x33FFFFFF), Color(0x18FFFFFF)))
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .background(
                                    if (isAutoKeyboardEnabled) {
                                        Brush.horizontalGradient(
                                            listOf(
                                                Color(0x3300E5FF),
                                                Color(0x330D6EFD)
                                            )
                                        )
                                    } else {
                                        Brush.horizontalGradient(
                                            listOf(
                                                Color(0x1AFFFFFF),
                                                Color(0x0DFFFFFF)
                                            )
                                        )
                                    }
                                )
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = if (isAutoKeyboardEnabled) Icons.Rounded.AutoAwesome else Icons.Rounded.KeyboardHide,
                                contentDescription = "Auto-Pop Keyboard",
                                tint = if (isAutoKeyboardEnabled) Color(0xFF00E5FF) else Color(0xFF888888),
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = if (isAutoKeyboardEnabled) "Auto-Pop: ON" else "Auto-Pop: OFF",
                                color = if (isAutoKeyboardEnabled) Color.White else Color(0xFF888888),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.2.sp
                            )
                        }
                    }
                    
                    // Special Keys Toggle (Google Material Chip / Pill Style with KeyboardCommandKey)
                    Surface(
                        onClick = { 
                            showSpecialKeys = !showSpecialKeys 
                            if (!showSpecialKeys) {
                                releaseAllRemoteKeys()
                            }
                            showToolbar = false
                        },
                        shape = RoundedCornerShape(20.dp),
                        color = Color.Transparent,
                        border = BorderStroke(
                            1.dp,
                            if (showSpecialKeys) Brush.horizontalGradient(listOf(Color(0xFF00E5FF), Color(0xFF0D6EFD)))
                            else Brush.horizontalGradient(listOf(Color(0x55FFFFFF), Color(0x22FFFFFF)))
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .background(
                                    if (showSpecialKeys) {
                                        Brush.horizontalGradient(
                                            listOf(
                                                Color(0xFF0052D4),
                                                Color(0xFF0D6EFD),
                                                Color(0xFF00B4D8)
                                            )
                                        )
                                    } else {
                                        Brush.horizontalGradient(
                                            listOf(
                                                Color(0x2AFFFFFF),
                                                Color(0x14FFFFFF)
                                            )
                                        )
                                    }
                                )
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.KeyboardCommandKey,
                                contentDescription = "Special Keys",
                                tint = if (showSpecialKeys) Color.White else Color(0xFFE0E0E0),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Special Keys",
                                color = if (showSpecialKeys) Color.White else Color(0xFFE0E0E0),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.3.sp
                            )
                        }
                    }
                    
                    // Orientation Toggle (Google Screen Rotation Icon)
                    ToolbarIconButton(
                        icon = Icons.Rounded.ScreenRotation,
                        label = "Rotate",
                        tint = if (isLandscape) TextPrimary else BrandBlue,
                        onClick = { 
                            isLandscape = !isLandscape 
                            showToolbar = false
                        }
                    )
                    
                    // Network Mode Badge (Google Wifi / Public Globe Icon)
                    ToolbarIconButton(
                        icon = if (isTailscale) Icons.Rounded.Public else Icons.Rounded.Wifi,
                        label = if (isTailscale) "WAN" else "LAN",
                        tint = if (isTailscale) Color(0xFF00E5FF) else Color(0xFF00E676),
                        onClick = { }
                    )

                    // Gesture Interaction Mode (Google Touch / Click / Mouse Icons)
                    Box {
                        ToolbarIconButton(
                            icon = when (activeGestureMode) {
                                GestureMode.HANDESK -> Icons.Rounded.TouchApp
                                GestureMode.TOUCH -> Icons.Rounded.AdsClick
                                GestureMode.MOUSE -> Icons.Rounded.Mouse
                            },
                            label = activeGestureMode.title,
                            tint = BrandBlue,
                            onClick = { expandedGestureMenu = true }
                        )

                        DropdownMenu(
                            expanded = expandedGestureMenu,
                            onDismissRequest = { expandedGestureMenu = false },
                            modifier = Modifier.background(SurfaceDark).width(200.dp)
                        ) {
                            GestureMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (activeGestureMode == mode) {
                                                Icon(Icons.Rounded.Check, contentDescription = null, tint = BrandBlue, modifier = Modifier.size(18.dp))
                                            } else {
                                                Spacer(Modifier.width(18.dp))
                                            }
                                            Spacer(Modifier.width(12.dp))
                                            Text(mode.title, color = TextPrimary, fontSize = 14.sp)
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = when (mode) {
                                                GestureMode.HANDESK -> Icons.Rounded.TouchApp
                                                GestureMode.TOUCH -> Icons.Rounded.AdsClick
                                                GestureMode.MOUSE -> Icons.Rounded.Mouse
                                            },
                                            contentDescription = null,
                                            tint = if (activeGestureMode == mode) BrandBlue else TextSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    onClick = {
                                        activeGestureMode = mode
                                        GestureMode.saveMode(activity, mode)
                                        currentTouchHandler?.currentMode = mode
                                        expandedGestureMenu = false
                                        showToolbar = false
                                    }
                                )
                            }
                            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(SurfaceVariantDark))
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.Info, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(12.dp))
                                        Text("Gesture Guide...", color = TextSecondary, fontSize = 14.sp)
                                    }
                                },
                                onClick = {
                                    expandedGestureMenu = false
                                    showToolbar = false
                                    showGestureHelpDialog = true
                                }
                            )
                        }
                    }

                    // Display Quality (Google Tune Equalizer Icon)
                    Box {
                        ToolbarIconButton(
                            icon = Icons.Rounded.Tune,
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
                                                Icon(Icons.Rounded.Check, contentDescription = null, tint = BrandBlue, modifier = Modifier.size(18.dp))
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
                    
                    // Frame Rate (Google Speed Gauge Icon)
                    Box {
                        ToolbarIconButton(
                            icon = Icons.Rounded.Speed,
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
                                                Icon(Icons.Rounded.Check, contentDescription = null, tint = BrandBlue, modifier = Modifier.size(18.dp))
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

                    // Audio PC Toggle (Google VolumeUp / VolumeOff Icons)
                    ToolbarIconButton(
                        icon = if (isPcAudioMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                        label = "Audio",
                        tint = if (isPcAudioMuted) TextSecondary else SuccessGreen,
                        onClick = { 
                            isPcAudioMuted = !isPcAudioMuted
                            networkClient.togglePcMute() 
                        }
                    )

                    // Divider
                    Box(modifier = Modifier.height(24.dp).width(1.dp).background(SurfaceVariantDark))
                    
                    // Disconnect (Google Power Button Icon)
                    ToolbarIconButton(
                        icon = Icons.Rounded.PowerSettingsNew,
                        label = "Disconnect",
                        tint = ErrorRed,
                        onClick = {
                            releaseAllRemoteKeys()
                            onDisconnect()
                        }
                    )
                    
                    // Hide Toolbar (Google Arrow Up)
                    ToolbarIconButton(
                        icon = Icons.Rounded.KeyboardArrowUp,
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
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Menu", tint = Color.White)
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
                    },
                    onShortcut = { modifier, key ->
                        networkClient.sendKeyEvent(modifier, true)
                        networkClient.sendKeyEvent(key, true)
                        networkClient.sendKeyEvent(key, false)
                        networkClient.sendKeyEvent(modifier, false)
                    }
                )
            }
        }

        // --- GESTURE HELP DIALOG ---
        if (showGestureHelpDialog) {
            GestureHelpDialog(
                currentMode = activeGestureMode,
                onSelectMode = { mode ->
                    activeGestureMode = mode
                    GestureMode.saveMode(activity, mode)
                    currentTouchHandler?.currentMode = mode
                },
                onDismiss = { showGestureHelpDialog = false }
            )
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

@Composable
fun FloatingTextSelectionToolbar(
    posX: Float,
    posY: Float,
    containerWidth: Float,
    containerHeight: Float,
    onCut: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onSelectAll: () -> Unit
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val approxWidthPx = with(density) { 280.dp.toPx() }
    val approxHeightPx = with(density) { 42.dp.toPx() }
    val marginPx = with(density) { 16.dp.toPx() }
    val spacingPx = with(density) { 28.dp.toPx() }

    val clampedX = (posX - approxWidthPx / 2f).coerceIn(marginPx, (containerWidth - approxWidthPx - marginPx).coerceAtLeast(marginPx))
    val preferredY = posY - approxHeightPx - spacingPx
    val clampedY = if (preferredY < marginPx) {
        (posY + spacingPx).coerceIn(marginPx, (containerHeight - approxHeightPx - marginPx).coerceAtLeast(marginPx))
    } else {
        preferredY.coerceIn(marginPx, (containerHeight - approxHeightPx - marginPx).coerceAtLeast(marginPx))
    }

    Surface(
        modifier = Modifier.offset { IntOffset(clampedX.toInt(), clampedY.toInt()) },
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFF2C2C2C),
        shadowElevation = 8.dp,
        tonalElevation = 4.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            AndroidSelectionButton(label = "Cut", onClick = onCut)
            AndroidSelectionDivider()
            AndroidSelectionButton(label = "Copy", onClick = onCopy)
            AndroidSelectionDivider()
            AndroidSelectionButton(label = "Paste", onClick = onPaste)
            AndroidSelectionDivider()
            AndroidSelectionButton(label = "Select all", onClick = onSelectAll)
        }
    }
}

@Composable
private fun AndroidSelectionButton(
    label: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color(0xFFF0F0F0),
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun AndroidSelectionDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(16.dp)
            .background(Color(0x2EFFFFFF))
    )
}
