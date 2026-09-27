$file = "windows\src\encoder.rs"
$content = Get-Content $file -Raw
$pattern = '(?s)if !out_data\.is_empty\(\) && !self\.sent_header \{.*?self\.sent_header = true;?
\s*\}'
$replacement = '// Let NVENC naturally embed VPS/SPS/PPS in the IDR frame stream'
$content = [regex]::Replace($content, $pattern, $replacement)
$content | Set-Content $file
