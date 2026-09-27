$file = "windows\src\encoder.rs"
$content = Get-Content $file -Raw
$content = $content -replace 'MFVideoFormat_H264', 'MFVideoFormat_HEVC'
$content = $content -replace 'H\.264', 'H.265'
$content | Set-Content $file

$file2 = "android\app\src\main\java\com\directlink\client\VideoDecoder.kt"
$content2 = Get-Content $file2 -Raw
$content2 = $content2 -replace 'MIMETYPE_VIDEO_AVC', 'MIMETYPE_VIDEO_HEVC'
$content2 = $content2 -replace 'type == 7 \|\| type == 8', 'type == 32 || type == 33 || type == 34'
$content2 = $content2 -replace 'data\[start \+ startCodeLen\]\.toInt\(\) and 0x1F', '(data[start + startCodeLen].toInt() and 0x7E) ushr 1'
$content2 | Set-Content $file2
