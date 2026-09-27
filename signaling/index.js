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
const udpRelay = dgram.createSocket({ type: 'udp6', ipv6Only: false });
// map of endpoint "IP:PORT" -> { type: 'HOST'|'CLIENT', sessionId: '...' }
const udpBindings = new Map();

udpRelay.on('message', (msg, rinfo) => {
    const senderKey = `${rinfo.address}:${rinfo.port}`;
    
    // 1. Is this a BIND request? Format: BIND:<32-char-hex-session-id>:<HOST|CLIENT>
    const msgStr = msg.toString('utf8');
    if (msgStr.startsWith("BIND:")) {
        const parts = msgStr.split(':');
        if (parts.length === 3) {
            const sessionId = parts[1];
            const role = parts[2]; // 'HOST' or 'CLIENT'
            
            const session = sessions.get(sessionId);
            if (session) {
                udpBindings.set(senderKey, { type: role, sessionId });
                if (role === 'HOST') session.hostUdp = rinfo;
                if (role === 'CLIENT') session.clientUdp = rinfo;
                console.log(`[RELAY] Bound ${role} UDP: ${senderKey} for session ${sessionId}`);
                // Ack the bind
                udpRelay.send("BIND_OK", rinfo.port, rinfo.address);
            }
        }
        return;
    }

    // 2. Not a bind request, it's a raw video/input packet.
    const binding = udpBindings.get(senderKey);
    if (!binding) return; // Drop unauthenticated packets

    const session = sessions.get(binding.sessionId);
    if (!session) return; // Session ended

    // Route packet natively!
    if (binding.type === 'HOST' && session.clientUdp) {
        udpRelay.send(msg, session.clientUdp.port, session.clientUdp.address);
    } else if (binding.type === 'CLIENT' && session.hostUdp) {
        udpRelay.send(msg, session.hostUdp.port, session.hostUdp.address);
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
