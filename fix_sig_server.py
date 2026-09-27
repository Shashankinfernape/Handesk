import os

sig_path = 'signaling/index.js'
with open(sig_path, 'r', encoding='utf-8') as f:
    content = f.read()

target = """            case 'CANDIDATE': {
                const { sessionId, candidate, isHost } = msg;
                const session = sessions.get(sessionId);
                if (!session) return;

                const targetWs = isHost ? session.clientWs : session.hostWs;
                if (targetWs && targetWs.readyState === 1) {
                    targetWs.send(JSON.stringify({
                        type: 'CANDIDATE',
                        sessionId,
                        candidate,
                        // Inject server-reflexive IP (STUN equivalent)
                        serverReflexiveIp: clientIp
                    }));
                }
                break;
            }"""

replacement = """            case 'CANDIDATE': {
                const { sessionId, candidate, isHost, serverReflexiveIp } = msg;
                const session = sessions.get(sessionId);
                if (!session) return;

                const targetWs = isHost ? session.clientWs : session.hostWs;
                if (targetWs && targetWs.readyState === 1) {
                    targetWs.send(JSON.stringify({
                        type: 'CANDIDATE',
                        sessionId,
                        candidate,
                        // Pass along the STUN IP if provided, otherwise inject websocket IP
                        serverReflexiveIp: serverReflexiveIp || clientIp
                    }));
                }
                break;
            }"""

content = content.replace(target, replacement)
with open(sig_path, 'w', encoding='utf-8') as f:
    f.write(content)
print("Signaling server fixed!")
