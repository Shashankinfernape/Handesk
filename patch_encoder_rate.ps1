$file = "windows\src\encoder.rs"
$content = Get-Content $file -Raw
$content = $content -replace '8_000_000', '5_000_000'
$content = $content -replace 'pack_ratio\(120, 1\)', 'pack_ratio(60, 1)'
$content | Set-Content $file
