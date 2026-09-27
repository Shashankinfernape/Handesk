import os

sig_path = 'windows/src/signaling.rs'
with open(sig_path, 'r', encoding='utf-8') as f:
    content = f.read()

target = """                                              let candidate_msg = serde_json::to_string(&SignalMessage::Candidate {
                                                  sessionId: session.clone(),
                                                  candidate: format!("udp:{}", ip),
                                                  isHost: Some(true),
                                                  serverReflexiveIp: None,
                                              }).unwrap();"""

replacement = """                                              let candidate_msg = serde_json::to_string(&SignalMessage::Candidate {
                                                  sessionId: session.clone(),
                                                  candidate: "udp:0.0.0.0".to_string(),
                                                  isHost: Some(true),
                                                  serverReflexiveIp: Some(ip.clone()),
                                              }).unwrap();"""

content = content.replace(target, replacement)

with open(sig_path, 'w', encoding='utf-8') as f:
    f.write(content)
print("Candidate fix applied!")
