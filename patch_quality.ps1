$file = "windows\src\encoder.rs"
$content = Get-Content $file -Raw
$content = $content -replace 'let var_profile = windows::core::VARIANT::from\(66u32\);', 'let var_profile = windows::core::VARIANT::from(1u32);'
$content | Set-Content $file

$file2 = "windows\src\network_udp.rs"
$content2 = Get-Content $file2 -Raw
$pattern = '(?s)tokio::task::yield_now\(\)\.await;'
$replacement = 'let spin_start = std::time::Instant::now();
                                while spin_start.elapsed().as_micros() < 50 {
                                    std::hint::spin_loop();
                                }
                                tokio::task::yield_now().await;'
$content2 = [regex]::Replace($content2, $pattern, $replacement)
$content2 | Set-Content $file2
