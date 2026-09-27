$file = "windows\src\encoder.rs"
$content = Get-Content $file -Raw
$content = $content -replace '10_000_000', '5_000_000'
$content = $content -replace 'pack_ratio\(120, 1\)', 'pack_ratio(60, 1)'
$content = $content -replace '120fps, 25Mbps', '60fps, 5Mbps'
$content | Set-Content $file
