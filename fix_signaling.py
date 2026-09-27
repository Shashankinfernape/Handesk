import os

sig_path = 'windows/src/signaling.rs'
with open(sig_path, 'r', encoding='utf-8') as f:
    content = f.read()

target = """                    // Start capture + encoder, stream binary frames over this WebSocket
                    let (tx, mut rx) = tokio::sync::mpsc::channel::<Vec<u8>>(4);
                    let capture_handle = tokio::spawn(async move {
                        if let Err(e) = crate::capture::start_capture_loop(tx).await {
                            error!("Capture loop error: {}", e);
                        }
                    });

                    {
                        let mut c = client.lock().await;
                        c.active_session = Some(session_id.clone());
                    }"""

replacement = """                    {
                        let mut c = client.lock().await;
                        c.active_session = Some(session_id.clone());
                    }"""

content = content.replace(target, replacement)

target2 = """                tokio::select! {
                    Some(nalu) = rx.recv() => {
                        let _ = ws_tx.send(warp::ws::Message::binary(nalu)).await;
                        frame_count += 1;
                        if frame_count % 30 == 0 {
                            info!("Streamed frame {} ({} bytes) via WebSocket", frame_count, _nalu_size_tracker); // Just to satisfy compiler warning avoidance if we used it, but let's keep logic simple
                        }
                    }
                    Some(msg) = ws_rx.next() => {"""

replacement2 = """                tokio::select! {
                    Some(msg) = ws_rx.next() => {"""

# Fallback for the select loop replacement in case formatting differs:
import re
content = re.sub(r'Some\(nalu\) = rx\.recv\(\) => \{.*?\}', '', content, flags=re.DOTALL)

with open(sig_path, 'w', encoding='utf-8') as f:
    f.write(content)
print("Fixed signaling!")
