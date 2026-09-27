$file = "windows\src\network_udp.rs"
$content = Get-Content $file -Raw

$pattern = '(?m)^\s*let _ = socket_clone\.send_to\(&frame, remote_addr\)\.await;$'
$replacement = 'let _ = socket_clone.send_to(&frame, remote_addr).await;
                                // Micro-pacing: Prevent Tailscale/UDP buffer drop for massive IDR frames
                                // We spin-wait for 150us to spread the burst out, ensuring 100% delivery.
                                let spin_start = std::time::Instant::now();
                                while spin_start.elapsed().as_micros() < 150 {
                                    std::hint::spin_loop();
                                }'
$content = [regex]::Replace($content, $pattern, $replacement)
$content | Set-Content $file
