$file = "android\app\src\main\java\com\directlink\client\MainActivity.kt"
$content = Get-Content $file -Raw
$pattern1 = '(?s)class MainActivity : ComponentActivity\(\) \{.*?override fun onCreate\(savedInstanceState: Bundle\?\)'
$replace1 = 'class MainActivity : ComponentActivity() {
    private val networkClient = NetworkClient()
    private val signalingClient = SignalingClient()

    override fun onCreate(savedInstanceState: Bundle?)'

$pattern2 = '(?s)setContent \{.*?DirectLinkApp\(networkClient, this\).*?\}'
$replace2 = 'setContent {
            DirectLinkApp(networkClient, signalingClient, this)
        }'

$pattern3 = '(?s)fun DirectLinkApp\(networkClient: NetworkClient, activity: Activity\) \{.*?if \(\!isConnected\) \{.*?HomeScreen\(.*?networkClient = networkClient,.*?activity = activity,.*?onConnected = \{ isConnected = true \}.*?\).*?\} else \{.*?RemoteSessionScreen\(.*?networkClient = networkClient,.*?activity = activity,.*?onDisconnect = \{.*?networkClient\.disconnect\(\).*?isConnected = false.*?\}'
$replace3 = 'fun DirectLinkApp(networkClient: NetworkClient, signalingClient: SignalingClient, activity: Activity) {
    var isConnected by remember { mutableStateOf(networkClient.isConnected) }

    if (!isConnected) {
        HomeScreen(
            networkClient = networkClient,
            signalingClient = signalingClient,
            activity = activity,
            onConnected = { isConnected = true }
        )
    } else {
        RemoteSessionScreen(
            networkClient = networkClient,
            signalingClient = signalingClient,
            activity = activity,
            onDisconnect = { 
                networkClient.disconnect()
                signalingClient.disconnect()
                isConnected = false 
            }'

$content = [regex]::Replace($content, $pattern1, $replace1)
$content = [regex]::Replace($content, $pattern2, $replace2)
$content = [regex]::Replace($content, $pattern3, $replace3)
$content | Set-Content $file
