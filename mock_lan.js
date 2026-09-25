const WebSocket = require('ws');
const dgram = require('dgram');

const remoteId = process.argv[2];
if (!remoteId) {
    console.error("Provide Remote ID");
    process.exit(1);
}

// Connect to the LAN IP instead of localhost
const SIGNAL_URL = "ws://10.155.32.181:3000";
const ws = new WebSocket(SIGNAL_URL);

ws.on('open', () => {
    console.log("Mock LAN Client: Connected to Signaling");
    ws.send(JSON.stringify({
        type: 'CLIENT_CONNECT',
        remoteId: remoteId,
        clientIp: '10.155.32.181'
    }));
});

ws.on('message', (data) => {
    const msg = JSON.parse(data.toString());
    console.log("Mock LAN Client: Received", msg);

    if (msg.type === 'CANDIDATE' && msg.candidate === 'relay') {
        const sessionId = msg.sessionId;
        const relayIp = "10.155.32.181";
        const relayPort = 3001;
        
        console.log(`Mock LAN Client: Sending punch packet to ${relayIp}:${relayPort}`);
        const udp = dgram.createSocket('udp4');
        
        const prefix = Buffer.from(sessionId, 'hex');
        const punch = Buffer.from('PUNCH:ANDROID_SESSION');
        const packet = Buffer.concat([prefix, punch]);
        
        udp.send(packet, relayPort, relayIp, (err) => {
            if (err) console.error(err);
        });

        // Also ping it repeatedly to keep NAT open
        setInterval(() => {
            udp.send(packet, relayPort, relayIp);
        }, 1000);

        udp.on('message', (msg, rinfo) => {
            console.log(`Mock LAN Client: Received UDP packet of ${msg.length} bytes from ${rinfo.address}:${rinfo.port}`);
        });
    } else if (msg.type === 'ERROR') {
        console.error(msg.message);
        process.exit(1);
    }
});
