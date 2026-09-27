import os

ma_path = 'android/app/src/main/java/com/directlink/client/MainActivity.kt'
with open(ma_path, 'r', encoding='utf-8') as f:
    ma_content = f.read()

target = '''                        // Discover Android's own STUN IP and send it to PC for dual-blast hole punching
                        CoroutineScope(Dispatchers.IO).launch {
                            if (networkClient.socket == null || networkClient.socket?.isClosed == true) {
                                networkClient.socket = java.net.DatagramSocket()
                            }
                            val androidPublicAddr = StunClient.getPublicAddress(networkClient.socket!!)
                            val pubStr = androidPublicAddr?.let { "${it.address.hostAddress}:${it.port}" } ?: ""
                            signalingClient.sendCandidate(json.optString("sessionId"), "udp:0.0.0.0", pubStr)
                        }

                        // Attempt dual-target UDP hole punch!
                        val err = networkClient.connectToHost(localIp, publicAddr)
                        if (err == null) {'''

replacement = '''                        // Discover Android's own STUN IP and send it to PC for dual-blast hole punching
                        CoroutineScope(Dispatchers.IO).launch {
                            if (networkClient.socket == null || networkClient.socket?.isClosed == true) {
                                networkClient.socket = java.net.DatagramSocket()
                            }
                            
                            // 1. MUST RUN STUN FIRST (Synchronously in this thread)
                            val androidPublicAddr = StunClient.getPublicAddress(networkClient.socket!!)
                            val pubStr = androidPublicAddr?.let { "${it.address.hostAddress}:${it.port}" } ?: ""
                            
                            // 2. Send our candidate to the PC so it can blast us!
                            signalingClient.sendCandidate(json.optString("sessionId"), "udp:0.0.0.0", pubStr)
                            
                            // Give the Matchmaker/PC 100ms to process before we blast (aligning the collision)
                            kotlinx.coroutines.delay(100)

                            // 3. NOW attempt dual-target UDP hole punch!
                            val err = networkClient.connectToHost(localIp, publicAddr)
                            
                            if (err == null) {
                                println("DirectLink: UDP P2P Connection SUCCESS! ($localIp)")
                                useWebsocket = false
                                networkClient.inputPacketSender = null
                            } else {
                                println("DirectLink: UDP Failed, falling back to Relay...")
                                val sessionId = json.optString("sessionId")
                                signalingClient.requestRelay(sessionId)
                            }
                        }'''

# Since we moved the if (err == null) block inside the coroutine, we need to remove it from below
ma_content = ma_content.replace(target, replacement)

# We need to strip the leftover else block since we moved it
target_leftover = '''                        } else {
                            println("DirectLink: UDP Failed, falling back to Relay...")
                            val sessionId = json.optString("sessionId")
                            signalingClient.requestRelay(sessionId)
                        }'''

ma_content = ma_content.replace(target_leftover, '')

with open(ma_path, 'w', encoding='utf-8') as f:
    f.write(ma_content)

print("Fixed!")
