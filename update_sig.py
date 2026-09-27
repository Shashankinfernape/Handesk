import os

file_path = 'windows/src/signaling.rs'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Update struct
content = content.replace(
    'pub capture_task: Option<tokio::task::JoinHandle<()>>,\n}',
    'pub capture_task: Option<tokio::task::JoinHandle<()>>,\n    pub public_ip: Option<String>,\n}'
)

# 2. Update connect signature and initialization
content = content.replace(
    'pub async fn connect(url: &str) -> Result<Arc<Mutex<Self>>> {',
    'pub async fn connect(url: &str, public_ip: Option<String>) -> Result<Arc<Mutex<Self>>> {'
)

content = content.replace(
    '''            remote_id: None,
            capture_task: None,
        }));''',
    '''            remote_id: None,
            capture_task: None,
            public_ip,
        }));'''
)

# 3. Update CANDIDATE sending to include public IP
target = '''                    let candidate_msg = serde_json::to_string(&SignalMessage::Candidate {
                        sessionId: session.clone(),
                        candidate: format!("udp:{}", { let s = std::net::UdpSocket::bind("0.0.0.0:0").unwrap(); s.connect("8.8.8.8:80").unwrap(); s.local_addr().unwrap().ip().to_string() }),
                        isHost: Some(true),
                        serverReflexiveIp: None,
                    }).unwrap();'''

replacement = '''                    let public_ip = {
                        let lock = c.lock().await;
                        lock.public_ip.clone()
                    };
                    
                    let candidate_msg = serde_json::to_string(&SignalMessage::Candidate {
                        sessionId: session.clone(),
                        candidate: format!("udp:{}", { let s = std::net::UdpSocket::bind("0.0.0.0:0").unwrap(); s.connect("8.8.8.8:80").unwrap(); s.local_addr().unwrap().ip().to_string() }),
                        isHost: Some(true),
                        serverReflexiveIp: public_ip,
                    }).unwrap();'''

content = content.replace(target, replacement)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)
print('Done!')
