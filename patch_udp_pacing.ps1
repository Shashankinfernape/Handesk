$file = "windows\src\network_udp.rs"
$content = Get-Content $file -Raw
$pattern = 'let _ = socket_clone\.send_to\(&packet, remote_addr\)\.await;'
$replacement = 'let _ = socket_clone.send_to(&packet, remote_addr).await;
                        // UDP Pacing: 1ms delay between chunks to prevent Tailscale/Cellular buffer overflow
                        tokio::time::sleep(std::time::Duration::from_millis(1)).await;'
$content = [regex]::Replace($content, $pattern, $replacement)
$content | Set-Content $file
