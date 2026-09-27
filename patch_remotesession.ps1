$file = "android\app\src\main\java\com\directlink\client\MainActivity.kt"
$content = Get-Content $file -Raw

# Remove inputPacketSender assignment entirely since we do pure UDP input directly in NetworkClient
$content = $content -replace '(?m)^\s*networkClient\.inputPacketSender = .*$', ''

# Remove the entire LaunchedEffect that collects signaling events
$pattern = '(?s)LaunchedEffect\(Unit\) \{\s*// Collect text signaling events.*?\}\s*\}\s*\}'
$content = [regex]::Replace($content, $pattern, '')

$content | Set-Content $file
