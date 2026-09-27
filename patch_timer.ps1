$file = "windows\src\main.rs"
$content = Get-Content $file -Raw
$pattern = '(?s)windows::Win32::System::Threading::REALTIME_PRIORITY_CLASS?
\s*\);'
$replacement = 'windows::Win32::System::Threading::REALTIME_PRIORITY_CLASS
        );
        windows::Win32::Media::timeBeginPeriod(1);'
$content = [regex]::Replace($content, $pattern, $replacement)
$content | Set-Content $file
