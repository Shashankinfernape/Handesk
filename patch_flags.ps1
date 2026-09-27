$file = "android\app\src\main\java\com\directlink\client\VideoDecoder.kt"
$content = Get-Content $file -Raw
$pattern = '(?s)val isConfig = \(data\.size > 4.*?val flags = if \(isConfig\) MediaCodec\.BUFFER_FLAG_CODEC_CONFIG else 0'
$replacement = 'val flags = 0 // Modern MediaCodec automatically parses VPS/SPS/PPS inline!'
$content = [regex]::Replace($content, $pattern, $replacement)
$content | Set-Content $file
