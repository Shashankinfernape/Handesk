$file = "android\app\src\main\java\com\directlink\client\NetworkClient.kt"
$content = Get-Content $file -Raw
$content = $content -replace 'catch \(e: java\.net\.SocketTimeoutException\) \{', 'catch (e: Exception) {'
$content | Set-Content $file
