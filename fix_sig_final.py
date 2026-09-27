import os

sig_path = 'windows/src/signaling.rs'
with open(sig_path, 'r', encoding='utf-8') as f:
    content = f.read()

target = """                    info!("========================================");
                    info!("  CLIENT CONNECTED — WebSocket relay");
                    info!("  Starting video stream for session: {}", session);
                    info!("========================================");

                    // Start capture + encoder, stream binary frames over this WebSocket
                    let (tx, mut rx) = tokio::sync::mpsc::channel::<Vec<u8>>(4);
                    let capture_handle = tokio::spawn(async move {
                        if let Err(e) = crate::capture::start_capture_loop(tx).await {
                            error!("Capture loop error: {}", e);
                        }
                    });

                    {
                        let mut lock = c.lock().await;
                        lock.capture_task = Some(capture_handle);
                    }

                    let mut frame_counter: u32 = 0;
                    while let Some(nalu) = rx.recv().await {
                        let frame_id = frame_counter;
                        frame_counter = frame_counter.wrapping_add(1);

                        let chunks = nalu.chunks(1200).collect::<Vec<_>>();
                        let total_chunks = chunks.len() as u16;

                        for (i, chunk) in chunks.iter().enumerate() {
                            let chunk_idx = i as u16;
                            // Binary packet: [2-byte chunk_idx][2-byte total][4-byte frame_id][payload]
                            let mut packet = Vec::with_capacity(8 + chunk.len());
                            packet.extend_from_slice(&chunk_idx.to_le_bytes());
                            packet.extend_from_slice(&total_chunks.to_le_bytes());
                            packet.extend_from_slice(&frame_id.to_le_bytes());
                            packet.extend_from_slice(chunk);

                            let lock = c.lock().await;
                            let mut tx_lock = lock.ws_tx.lock().await;
                            if tx_lock.send(Message::Binary(packet)).await.is_err() {
                                info!("Client disconnected, stopping stream");
                                return;
                            }
                        }

                        if frame_counter % 30 == 0 {
                            info!("Streamed frame {} ({} bytes, {} chunks) via WebSocket", frame_id, nalu.len(), total_chunks);
                        }
                    }
                });"""

target = target.replace("—", "?") # the console output showed a weird dash replacement, but let's be safe and use regex.
