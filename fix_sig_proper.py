import os, re

sig_path = 'windows/src/signaling.rs'
with open(sig_path, 'r', encoding='utf-8') as f:
    content = f.read()

# We need to replace the entire match block inside read.next().await loop
# To make it robust, we'll replace from 'match msg {' up to the end of the loop

start_str = "match msg {"
end_str = "                      Ok(Message::Binary(bin)) => {\n                          crate::input::handle_input_payload(&bin);\n                      }\n                      _ => {}\n                  }\n              }\n          });"

start_idx = content.find(start_str)
end_idx = content.find(end_str)

if start_idx == -1 or end_idx == -1:
    print("Could not find block boundaries!")
else:
    new_match = """match msg {
                      Ok(Message::Text(text)) => {
                          if let Ok(signal) = serde_json::from_str::<SignalMessage>(&text) {
                              match signal {
                                  SignalMessage::Challenge { challenge } => {
                                      let mut c = client_clone.lock().await;
                                      let signature = c.signing_key.sign(challenge.as_bytes());
                                      let sig_hex = hex::encode(signature.to_bytes());
                                      
                                      let auth_msg = serde_json::to_string(&SignalMessage::HostAuth {
                                          pubKey: pubkey_hex.to_string(),
                                          signature: sig_hex,
                                      }).unwrap();
                                      
                                      let mut tx_lock = c.ws_tx.lock().await;
                                      let _ = tx_lock.send(Message::Text(auth_msg)).await;
                                  }
                                  SignalMessage::AuthSuccess { remoteId } => {
                                      let mut c = client_clone.lock().await;
                                      c.remote_id = Some(remoteId.clone());
                                      info!("========================================");
                                      info!("  DirectLink Remote ID: {}", remoteId);
                                      info!("========================================");
                                  }
                                  SignalMessage::ClientRequest { sessionId, clientIp } => {
                                      info!("Incoming connection request from {} - sending UDP candidates", clientIp);
                                      let c = client_clone.clone();
                                      let session = sessionId.clone();
                                      tokio::spawn(async move {
                                          let lock = c.lock().await;
                                          if let Some(ip) = &lock.public_ip {
                                              let candidate_msg = serde_json::to_string(&SignalMessage::Candidate {
                                                  sessionId: session.clone(),
                                                  candidate: format!("udp:{}", ip),
                                                  isHost: Some(true),
                                                  serverReflexiveIp: None,
                                              }).unwrap();
                                              let mut tx_lock = lock.ws_tx.lock().await;
                                              let _ = tx_lock.send(Message::Text(candidate_msg)).await;
                                          }
                                      });
                                  }
                                  SignalMessage::Candidate { sessionId, candidate, serverReflexiveIp, .. } => {
                                      info!("Received candidate from Android for {}: {}", sessionId, candidate);
                                      let mut targets = Vec::new();
                                      if let Some(ip) = serverReflexiveIp {
                                          info!("Android public IP: {}", ip);
                                          targets.push(ip);
                                      }
                                      if candidate.starts_with("udp:") {
                                          let local = candidate.trim_start_matches("udp:");
                                          targets.push(format!("{}:21118", local));
                                      }
                                      
                                      let socket = {
                                          let c = client_clone.lock().await;
                                          c.udp_socket.clone()
                                      };
                                      
                                      tokio::spawn(async move {
                                          let hello_packet = [b'D', b'L', b'P', b'1', 0x01];
                                          for _ in 0..10 {
                                              for t in &targets {
                                                  if let Ok(addr) = t.parse::<std::net::SocketAddr>() {
                                                      let _ = socket.send_to(&hello_packet, addr).await;
                                                  }
                                              }
                                              tokio::time::sleep(std::time::Duration::from_millis(50)).await;
                                          }
                                      });
                                  }
                                  SignalMessage::Error { message } => {
                                      tracing::error!("Signaling Error: {}", message);
                                  }
                                  _ => {}
                              }
                          }
                      }
                      Ok(Message::Binary(bin)) => {
                          crate::input::handle_input_payload(&bin);
                      }
                      _ => {}
                  }
              }
          });"""
          
    content = content[:start_idx] + new_match + content[end_idx + len(end_str):]
    with open(sig_path, 'w', encoding='utf-8') as f:
        f.write(content)
    print("Signaling block completely rewritten!")
