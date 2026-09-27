$file = "android\app\src\main\java\com\directlink\client\NetworkClient.kt"
$content = Get-Content $file -Raw
$content = $content -replace 'suspend fun connectToHost\(localIp: String, publicAddr: String\)', 'suspend fun connectToHost(localIp: String, publicAddr: String = "")'
$content | Set-Content $file
