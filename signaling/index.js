const express = require('express');
const { WebSocketServer } = require('ws');
const crypto = require('crypto');
const dgram = require('dgram');
const http = require('http');

const app = express();
const server = http.createServer(app);
const wss = new WebSocketServer({ server });

// --- STATE ---
// hosts: remoteId (9-digit) -> { ws, pubKey, status }
const hosts = new Map();
const wsToId = new Map();

// sessions: sessionId -> { hostWs, clientWs, hostUdp, clientUdp, status }
const sessions = new Map();

// --- UDP RELAY SERVER ---
// Listens for UDP packets and routes them between Client and Host if P2P fails
const udpRelay = dgram.createSocket('udp4');

udpRelay.on('message', (msg, rinfo) => {
    // Relay Packet Format: [SessionID: 16 bytes][Payload...]
    if (msg.length < 16) return;
    const sessionId = msg.toString('hex', 0, 16);
    const session = sessions.get(sessionId);
    if (!session) return;

    const senderKey = `${rinfo.address}:${rinfo.port}`;
    const payload = msg.subarray(16);
    const payloadStr = payload.toString('utf8');
    
    // Auto-learn endpoints for the relay based on explicit punch packets
    if (payloadStr.startsWith("PUNCH:HOST_SESSION")) {
        session.hostUdp = rinfo;
        console.log(`[RELAY] Bound Host UDP: ${senderKey}`);
        return; // Don't route the punch packet itself
    } else if (payloadStr.startsWith("PUNCH:ANDROID_SESSION")) {
        session.clientUdp = rinfo;
        console.log(`[RELAY] Bound Client UDP: ${senderKey}`);
        return;
    }

    // Route packet
    if (session.hostUdp && senderKey === `${session.clientUdp?.address}:${session.clientUdp?.port}`) {
        udpRelay.send(payload, session.hostUdp.port, session.hostUdp.address);
        console.log(`[RELAY] Routed ${payload.length} bytes to HOST`);
    } else if (session.clientUdp && senderKey === `${session.hostUdp?.address}:${session.hostUdp?.port}`) {
        udpRelay.send(payload, session.clientUdp.port, session.clientUdp.address);
        console.log(`[RELAY] Routed ${payload.length} bytes to CLIENT`);
    }
});

// --- HELPER FUNCTIONS ---
function generateRemoteId() {
    let id;
    do { id = Math.floor(100000000 + Math.random() * 900000000).toString(); }
    while (hosts.has(id));
    return `${id.slice(0,3)} ${id.slice(3,6)} ${id.slice(6,9)}`;
}

function verifySignature(pubKeyHex, signatureHex, data) {
    try {
        const pubKey = crypto.createPublicKey({
            key: Buffer.from(`302a300506032b6570032100${pubKeyHex}`, 'hex'),
            format: 'der',
            type: 'spki'
        });
        return crypto.verify(null, Buffer.from(data), pubKey, Buffer.from(signatureHex, 'hex'));
    } catch (e) {
        return false;
    }
}

// --- WEBSOCKET SIGNALING ---
wss.on('connection', (ws, req) => {
    const clientIp = req.headers['x-forwarded-for']?.split(',')[0].trim() || req.socket.remoteAddress;
    let pendingChallenge = null;
    let currentSessionId = null;

    ws.on('message', (raw) => {
        // Binary message = video frame relay (host -> client) OR input relay (client -> host)
        if (raw instanceof Buffer && !(raw[0] === 0x7b)) { // not '{'
            for (const [, session] of sessions) {
                // Host to Client (Video)
                if (session.hostWs === ws && session.clientWs?.readyState === 1) {
                    session.clientWs.send(raw, { binary: true });
                    return;
                }
                // Client to Host (Input)
                if (session.clientWs === ws && session.hostWs?.readyState === 1) {
                    session.hostWs.send(raw, { binary: true });
                    return;
                }
            }
            return;
        }

        let msg;
        try { msg = JSON.parse(raw); } catch { return; }

        switch (msg.type) {
            
            // 1. HOST REGISTRATION
            case 'HOST_REGISTER': {
                // Generates a challenge for the host to sign
                pendingChallenge = crypto.randomBytes(32).toString('hex');
                ws.send(JSON.stringify({ type: 'CHALLENGE', challenge: pendingChallenge }));
                break;
            }

            case 'HOST_AUTH': {
                // Host returns signature of the challenge using its Ed25519 key
                if (!pendingChallenge) return;
                const { pubKey, signature } = msg;
                
                if (verifySignature(pubKey, signature, pendingChallenge)) {
                    const remoteId = generateRemoteId();
                    hosts.set(remoteId, { ws, pubKey, publicIp: clientIp, status: 'ONLINE' });
                    wsToId.set(ws, remoteId);
                    ws.send(JSON.stringify({ type: 'AUTH_SUCCESS', remoteId }));
                    console.log(`[HOST] Authenticated ${remoteId} (${clientIp})`);
                } else {
                    ws.send(JSON.stringify({ type: 'ERROR', message: 'Authentication Failed' }));
                }
                pendingChallenge = null;
                break;
            }

            // 2. CLIENT CONNECTION
            case 'CLIENT_CONNECT': {
                let { remoteId } = msg;
                if (!remoteId) return;
                
                // Find host by normalizing spaces in case client stripped them
                let host = hosts.get(remoteId);
                if (!host) {
                    const normalizedId = remoteId.replace(/\s/g, '');
                    for (const [key, val] of hosts.entries()) {
                        if (key.replace(/\s/g, '') === normalizedId) {
                            host = val;
                            break;
                        }
                    }
                }

                if (!host || host.ws.readyState !== 1) {
                    ws.send(JSON.stringify({ type: 'ERROR', message: 'Remote ID not found or offline' }));
                    return;
                }

                currentSessionId = crypto.randomBytes(16).toString('hex');
                sessions.set(currentSessionId, { hostWs: host.ws, clientWs: ws, status: 'NEGOTIATING' });

                // Tell the host a client wants to connect
                host.ws.send(JSON.stringify({
                    type: 'CLIENT_REQUEST',
                    sessionId: currentSessionId,
                    clientIp
                }));
                break;
            }

            // 3. CANDIDATE EXCHANGE (ICE)
            case 'CANDIDATE': {
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
            }

            // 4. RELAY ALLOCATION
            case 'ALLOCATE_RELAY': {
                const { sessionId } = msg;
                const session = sessions.get(sessionId);
                if (!session) return;

                const relayPort = udpRelay.address().port;
                ws.send(JSON.stringify({
                    type: 'RELAY_ALLOCATED',
                    relayIp: process.env.PUBLIC_IP || '127.0.0.1', // Would be true public IP in prod
                    relayPort,
                    sessionId
                }));
                break;
            }
        }
    });

    ws.on('close', () => {
        const id = wsToId.get(ws);
        if (id) {
            hosts.delete(id);
            wsToId.delete(ws);
            console.log(`[-] Host ${id} disconnected`);
        }
    });
});

app.get('/health', (_, res) => res.json({ status: 'ok', hostsOnline: hosts.size, sessionsActive: sessions.size }));

const PORT = process.env.PORT || 3000;
const UDP_PORT = process.env.UDP_PORT || 3001;

server.listen(PORT, () => console.log(`Signaling Server (TCP) on port ${PORT}`));
udpRelay.bind(UDP_PORT, () => console.log(`Relay Server (UDP) on port ${UDP_PORT}`));
