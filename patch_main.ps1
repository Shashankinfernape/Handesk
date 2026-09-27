$file = "windows\src\main.rs"
$content = Get-Content $file -Raw
$pattern = '(?s)if let Err\(e\) = network_udp::start_direct_server\(\)\.await \{'
$replacement = 'let socket = std::sync::Arc::new(tokio::net::UdpSocket::bind("0.0.0.0:21118").await?);
    if let Err(e) = network_udp::start_direct_server(socket).await {'
$content = [regex]::Replace($content, $pattern, $replacement)
$content | Set-Content $file
