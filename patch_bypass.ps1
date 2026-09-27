$file = "windows\src\encoder.rs"
$content = Get-Content $file -Raw
$pattern = '(?s)if let Some\(header\) = &self\.seq_header \{.*?out_data = full_frame;\s*self\.sent_header = true;\s*\}'
$replacement = '// Bypassing seq_header injection because NVENC H.265 already embeds it!
                self.sent_header = true;'
$content = [regex]::Replace($content, $pattern, $replacement)
$content | Set-Content $file
