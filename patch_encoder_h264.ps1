$file = "windows\src\encoder.rs"
$content = Get-Content $file -Raw
$content = $content -replace 'MFVideoFormat_HEVC', 'MFVideoFormat_H264'
$content = $content -replace 'H\.265', 'H.264'
$content | Set-Content $file
