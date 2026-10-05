use anyhow::Result;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::sync::mpsc;
use tracing::{error, info, warn};

pub async fn start_tcp_server() -> Result<()> {
    let listener = TcpListener::bind("0.0.0.0:21118").await?;
    info!("TCP Server listening on 0.0.0.0:21118 (FLAWLESS QUALITY MODE)");

    loop {
        let (mut socket, addr) = match listener.accept().await {
            Ok(s) => s,
            Err(e) => {
                error!("TCP Accept error: {}", e);
                continue;
            }
        };

        info!("TCP Client connected: {}", addr);
        if let Err(e) = socket.set_nodelay(true) {
            warn!("Failed to set TCP_NODELAY: {}", e);
        }

        tokio::spawn(async move {
            let (mut rx_socket, mut tx_socket) = socket.into_split();

            let (tx, mut rx) = mpsc::channel::<Vec<u8>>(1);
            let capture_handle = std::thread::spawn(move || {
                if let Err(e) = crate::capture::start_capture_loop() {
                    error!("TCP Capture error: {}", e);
                }
            });

            // Writer task
            let writer_task = tokio::spawn(async move {
                while let Some(nalu) = rx.recv().await {
                    let len = nalu.len() as u32;
                    if tx_socket.write_all(&len.to_le_bytes()).await.is_err() {
                        break;
                    }
                    if tx_socket.write_all(&nalu).await.is_err() {
                        break;
                    }
                }
            });

            // Reader task for inputs (mouse/touch)
            let reader_task = tokio::spawn(async move {
                loop {
                    // All touch/mouse packets from Android are currently exactly 15 bytes!
                    let mut b = [0u8; 15];
                    if rx_socket.read_exact(&mut b).await.is_err() {
                        break;
                    }

                    // b[0..4] is "DLP1", b[4] is 0x07 (Input Type)
                    // The actual input payload starts at index 5.
                    if b[0] == b'D' && b[1] == b'L' && b[2] == b'P' && b[3] == b'1' && b[4] == 0x07
                    {
                        crate::input::handle_input_payload(&b[5..]);
                    }
                }
            });

            // Wait for either to finish
            tokio::select! {
                _ = writer_task => {},
                _ = reader_task => {},
            }

            info!("TCP Client disconnected: {}", addr);
        });
    }
}
