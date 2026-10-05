use anyhow::Result;
use tokio::sync::mpsc;
use tracing::{error, info};

use crate::capture;
use crate::transport::Transport;

pub async fn start_session(transport: Transport) -> Result<()> {
    info!(
        "Starting Remote Desktop session over {:?}",
        transport.transport_type
    );

    // Bounded channel: if capture is faster than network, drop stale frames (newest wins)
    let (tx, mut rx) = mpsc::channel::<Vec<u8>>(4);

    // Start DXGI Capture + Encoder loop on dedicated thread
    std::thread::spawn(move || {
        if let Err(e) = capture::start_capture_loop() {
            error!("Capture loop error: {}", e);
        }
    });

    let socket = transport.socket;
    let target = transport.target;
    let mut frame_counter: u32 = 0;

    // Receive NALUs and send over UDP
    while let Some(nalu) = rx.recv().await {
        let prefix = transport.session_prefix.as_deref().unwrap_or(&[]);
        let prefix_len = prefix.len();
        let frame_id = frame_counter;
        frame_counter = frame_counter.wrapping_add(1);

        let chunks = nalu.chunks(1200).collect::<Vec<_>>();
        let total_chunks = chunks.len() as u16;

        for (i, chunk) in chunks.iter().enumerate() {
            let chunk_idx = i as u16;
            // Header: [2-byte chunk_idx][2-byte total_chunks][4-byte frame_id]
            let mut packet = Vec::with_capacity(prefix_len + 8 + chunk.len());
            if prefix_len > 0 {
                packet.extend_from_slice(prefix);
            }
            packet.extend_from_slice(&chunk_idx.to_le_bytes());
            packet.extend_from_slice(&total_chunks.to_le_bytes());
            packet.extend_from_slice(&frame_id.to_le_bytes());
            packet.extend_from_slice(chunk);

            if let Err(e) = socket.send_to(&packet, target).await {
                error!("UDP send failed: {}", e);
                return Ok(());
            }
        }

        info!(
            "Sent frame {} ({} bytes) in {} chunks to {}",
            frame_id,
            nalu.len(),
            total_chunks,
            target
        );
    }

    Ok(())
}
