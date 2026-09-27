$file = "android\app\src\main\java\com\directlink\client\VideoDecoder.kt"
$content = Get-Content $file -Raw
$content = $content -replace 'dequeueInputBuffer\(1000\)', 'dequeueInputBuffer(-1)'
$content | Set-Content $file
