$file = "windows\src\network_udp.rs"
$content = Get-Content $file -Raw
$pattern = '(?s)let spin_start = std::time::Instant::now\(\);.*?\}'
$replacement = 'tokio::task::yield_now().await;'
$content = [regex]::Replace($content, $pattern, $replacement)
$content | Set-Content $file
