$file = "windows\src\network_udp.rs"
$content = Get-Content $file -Raw
$content = $content -replace 'spin_start\.elapsed\(\)\.as_micros\(\) < 50', 'spin_start.elapsed().as_micros() < 300'
$content | Set-Content $file
