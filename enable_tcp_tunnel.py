import os

main_path = 'windows/src/main.rs'
with open(main_path, 'r', encoding='utf-8') as f:
    content = f.read()
# Comment out the UDP server so it doesn't collide
content = content.replace("tokio::spawn(async move {\n        if let Err(e) = network_udp::start_server(udp_socket).await {\n            error!(\"UDP Server error: {:?}\", e);\n        }\n    });", 
"""//tokio::spawn(async move {
    //    if let Err(e) = network_udp::start_server(udp_socket).await {
    //        error!("UDP Server error: {:?}", e);
    //    }
    //});""")
with open(main_path, 'w', encoding='utf-8') as f:
    f.write(content)

sig_path = 'windows/src/signaling.rs'
with open(sig_path, 'r', encoding='utf-8') as f:
    content = f.read()

target = """                tokio::spawn(async move {
                    let lock = c.lock().await;
                    if let Some(ip) = &lock.public_ip {
                        let candidate_msg = serde_json::to_string(&SignalMessage::Candidate {
                            sessionId: session.clone(),
                            candidate: "udp:0.0.0.0".to_string(),
                                                  isHost: Some(true),
                                                  serverReflexiveIp: Some(ip.clone()),
                        }).unwrap();
                        
                        let mut tx_lock = lock.ws_tx.lock().await;
                        let _ = tx_lock.send(Message::Text(candidate_msg)).await;
                    } else {
                        error!("No STUN IP available! Cannot send UDP candidate.");
                    }
                });"""

replacement = """                tokio::spawn(async move {
                    let mut lock = c.lock().await;
                    
                    // Kill any existing capture task
                    if let Some(t) = lock.capture_task.take() {
                        t.abort();
                    }

                    // Send TCP Cloudflare TUNNEL ACK
                    let candidate_msg = serde_json::to_string(&SignalMessage::Candidate {
                        sessionId: session.clone(),
                        candidate: "tcp:tunnel".to_string(),
                        isHost: Some(true),
                        serverReflexiveIp: None,
                    }).unwrap();
                    
                    let mut tx_lock = lock.ws_tx.lock().await;
                    let _ = tx_lock.send(Message::Text(candidate_msg)).await;
                    drop(tx_lock);
                    
                    // START TCP VIDEO TUNNEL OVER WEBSOCKET
                    let ws_tx_clone = lock.ws_tx.clone();
                    let (video_tx, mut video_rx) = tokio::sync::mpsc::channel::<Vec<u8>>(8);
                    
                    tokio::spawn(async move {
                        if let Err(e) = crate::capture::start_capture_loop(video_tx).await {
                            error!("TCP Capture loop failed: {:?}", e);
                        }
                    });

                    let handle = tokio::spawn(async move {
                        info!("TCP Video Tunnel Active! Routing H.265 via Cloudflare.");
                        while let Some(nalu) = video_rx.recv().await {
                            let mut tx = ws_tx_clone.lock().await;
                            let _ = tx.send(Message::Binary(nalu)).await;
                            // yield to prevent lock starvation
                            tokio::task::yield_now().await;
                        }
                    });
                    
                    lock.capture_task = Some(handle);
                });"""

content = content.replace(target, replacement)
with open(sig_path, 'w', encoding='utf-8') as f:
    f.write(content)
print("TCP Video Tunnel Injected!")
