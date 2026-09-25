const { WebSocket } = require('ws');
const crypto = require('crypto');
const dgram = require('dgram');

const SIGNALING_URL = 'ws://localhost:3000';

async function runTest() {
    console.log("Starting ICE/Relay Verification Test...\n");

    // We will connect as a client and try to reach the Rust Host.
    // Assuming the Rust host is running and has printed its Remote ID.
    // Wait, the test script can't know the remote ID unless we pass it.
    
    console.log("To verify the ICE transport, please run the Rust host (`cargo run`),");
    console.log("copy the 9-digit Remote ID, and run this script with it:");
    console.log("node ice_test.js \"123 456 789\"");
}

const args = process.argv.slice(2);
if (args.length > 0) {
    const remoteId = args[0];
    console.log(`Connecting to ${remoteId}...`);
    
    const ws = new WebSocket(SIGNALING_URL);
    ws.on('open', () => {
        ws.send(JSON.stringify({ type: 'CLIENT_CONNECT', remoteId }));
    });

    ws.on('message', (data) => {
        const msg = JSON.parse(data);
        console.log("<-", msg.type);

        if (msg.type === 'CANDIDATE' || (msg.type === 'CLIENT_REQUEST' && false)) { // Client request isn't sent to client
            // Ignore
        }

        if (msg.type === 'CANDIDATE') {
            console.log(`Host Candidate Received: ${msg.candidate}`);
            
            // Simulate UDP Hole Punching to candidate
            const udp = dgram.createSocket('udp4');
            udp.bind(0, () => {
                const [ip, port] = msg.candidate.split(':');
                const punch = Buffer.from("PUNCH:TEST_SESSION");
                udp.send(punch, parseInt(port), ip === '::1' ? '127.0.0.1' : ip, (err) => {
                    if (err) console.error(err);
                    console.log(`-> Sent UDP PUNCH to Host at ${ip}:${port}`);
                });
                
                // If P2P fails, we'd request relay:
                setTimeout(() => {
                    console.log("-> Requesting Relay (Simulating P2P failure)...");
                    ws.send(JSON.stringify({ type: 'ALLOCATE_RELAY', sessionId: 'TEST_SESSION' }));
                }, 2000);
            });

            udp.on('message', (buf) => {
                console.log("<- UDP Received:", buf.toString());
                console.log("\n✅ P2P Verification SUCCESS!");
                process.exit(0);
            });
        }

        if (msg.type === 'RELAY_ALLOCATED') {
            console.log(`\n✅ RELAY Verification SUCCESS! Allocated at ${msg.relayIp}:${msg.relayPort}`);
            process.exit(0);
        }
    });

} else {
    runTest();
}
