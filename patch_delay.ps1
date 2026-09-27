$file = "android\app\src\main\java\com\directlink\client\NetworkClient.kt"
$content = Get-Content $file -Raw
$content = $content -replace '\} catch \(e: Exception\) \{(\s*)// Try again', '} catch (e: Exception) {    kotlinx.coroutines.delay(500)'
$content | Set-Content $file
