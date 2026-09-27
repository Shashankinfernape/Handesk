$file = "windows\src\encoder.rs"
$content = Get-Content $file -Raw
$content = $content -replace '5_000_000', '25_000_000'
$content = $content -replace 'pack_ratio\(60, 1\)', 'pack_ratio(144, 1)'
$content | Set-Content $file

$file2 = "windows\src\capture.rs"
$content2 = Get-Content $file2 -Raw
$content2 = $content2 -replace 'Duration::from_millis\(16\)', 'Duration::from_micros(6944)'
$content2 = $content2 -replace '16', '6'
$content2 | Set-Content $file2
