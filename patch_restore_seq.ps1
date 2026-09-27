$file = "windows\src\encoder.rs"
$content = Get-Content $file -Raw
$pattern = '(?s)// Bypassing seq_header injection because NVENC H.265 already embeds it!?
\s*self\.sent_header = true;'
$replacement = 'if let Some(header) = &self.seq_header {
                    let mut full_frame = header.clone();
                    full_frame.extend(&out_data);
                    out_data = full_frame;
                    self.sent_header = true;
                }'
$content = [regex]::Replace($content, $pattern, $replacement)
$content | Set-Content $file
