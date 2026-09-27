$file = "android\app\src\main\java\com\directlink\client\NetworkClient.kt"
$content = Get-Content $file -Raw
$content = $content -replace 'socket\?\.soTimeout = 500 // Short timeout', 'socket?.soTimeout = 1500 // Increased timeout for PC init'
$content = $content -replace 'for \(i in 1\.\.3\) \{', 'for (i in 1..6) {'
$content | Set-Content $file
