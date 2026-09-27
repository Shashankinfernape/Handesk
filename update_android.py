import os

# 1. Update NetworkClient.kt
nc_path = 'android/app/src/main/java/com/directlink/client/NetworkClient.kt'
with open(nc_path, 'r', encoding='utf-8') as f:
    nc_content = f.read()

# Change connectToHost signature and logic
target = '''    suspend fun connectToHost(hostIp: String): String? = withContext(Dispatchers.IO) {
        try {
            val cleanIp = hostIp.trim()
            serverAddress = InetAddress.getByName(cleanIp)
            targetPort = 21118 // Explicitly reset just in case
            socket = DatagramSocket() // Bind to any local port
            socket?.soTimeout = 1000 // 1 second timeout to verify P2P

            // Send HELLO packet (DLP1 + 0x01)
            val helloPacket = byteArrayOf('D'.code.toByte(), 'L'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte(), 0x01)
            val dp = DatagramPacket(helloPacket, helloPacket.size, serverAddress, targetPort)
            
            // The HELLO packet MUST go over actual UDP to punch the hole and alert the PC!
            socket?.send(dp)
            println("DirectLink: Sent UDP HELLO to $cleanIp:$targetPort")

            // Wait for PC to stream video chunks over UDP to verify hole punch!
            val testBuf = ByteArray(2048)
            val testPacket = DatagramPacket(testBuf, testBuf.size)
            try {
                socket?.receive(testPacket)
                println("DirectLink: Hole punch successful, received video over UDP!")
                socket?.soTimeout = 0 // Reset to infinite for the listener loop
            } catch (e: java.net.SocketTimeoutException) {
                socket?.close()
                println("DirectLink: UDP Hole punch TIMED OUT (CGNAT).")
                return@withContext "Timeout"
            }'''

replacement = '''    suspend fun connectToHost(localIp: String, publicAddr: String): String? = withContext(Dispatchers.IO) {
        try {
            if (socket == null || socket?.isClosed == true) {
                socket = DatagramSocket() // Bind to any local port
            }
            socket?.soTimeout = 500 // Short timeout for volley
            targetPort = 21118

            val helloPacket = byteArrayOf('D'.code.toByte(), 'L'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte(), 0x01)
            
            val targets = mutableListOf<InetSocketAddress>()
            try { targets.add(InetSocketAddress(InetAddress.getByName(localIp.trim()), targetPort)) } catch(e: Exception){}
            
            if (publicAddr.isNotEmpty() && publicAddr.contains(":")) {
                try {
                    val parts = publicAddr.split(":")
                    targets.add(InetSocketAddress(InetAddress.getByName(parts[0]), parts[1].toInt()))
                } catch(e: Exception){}
            }

            println("DirectLink: Blasting UDP HELLO to targets: $targets")
            var connected = false

            // Blast loop (3 attempts)
            for (i in 1..3) {
                for (target in targets) {
                    try {
                        val dp = DatagramPacket(helloPacket, helloPacket.size, target.address, target.port)
                        socket?.send(dp)
                    } catch(e: Exception) {}
                }
                
                // Listen for response
                val testBuf = ByteArray(2048)
                val testPacket = DatagramPacket(testBuf, testBuf.size)
                try {
                    socket?.receive(testPacket)
                    println("DirectLink: Hole punch successful! Received packet from ${testPacket.address}:${testPacket.port}")
                    // Lock onto this address!
                    serverAddress = testPacket.address
                    targetPort = testPacket.port
                    connected = true
                    break
                } catch (e: java.net.SocketTimeoutException) {
                    // Try again
                }
            }

            if (!connected) {
                socket?.close()
                println("DirectLink: UDP Hole punch TIMED OUT. NAT is too strict.")
                return@withContext "Timeout"
            }

            socket?.soTimeout = 0 // Reset to infinite for the listener loop'''

nc_content = nc_content.replace(target, replacement)

with open(nc_path, 'w', encoding='utf-8') as f:
    f.write(nc_content)


# 2. Update MainActivity.kt
ma_path = 'android/app/src/main/java/com/directlink/client/MainActivity.kt'
with open(ma_path, 'r', encoding='utf-8') as f:
    ma_content = f.read()

target2 = '''                    val candidateStr = json.optString("candidate")
                    if (candidateStr.startsWith("udp:")) {
                        val ip = candidateStr.removePrefix("udp:")
                        // Attempt direct UDP connection on local network!
                        val err = networkClient.connectToHost(ip)'''

replacement2 = '''                    val candidateStr = json.optString("candidate")
                    val publicAddr = json.optString("serverReflexiveIp", "")
                    
                    if (candidateStr.startsWith("udp:")) {
                        val localIp = candidateStr.removePrefix("udp:")
                        
                        // Discover Android's own STUN IP and send it to PC for dual-blast hole punching
                        coroutineScope.launch(Dispatchers.IO) {
                            if (networkClient.socket == null || networkClient.socket?.isClosed == true) {
                                networkClient.socket = java.net.DatagramSocket()
                            }
                            val androidPublicAddr = StunClient.getPublicAddress(networkClient.socket!!)
                            val pubStr = androidPublicAddr?.let { "${it.address.hostAddress}:${it.port}" } ?: ""
                            signalingClient.sendCandidate(json.optString("sessionId"), "udp:0.0.0.0", pubStr)
                        }

                        // Attempt dual-target UDP hole punch!
                        val err = networkClient.connectToHost(localIp, publicAddr)'''

ma_content = ma_content.replace(target2, replacement2)

# Fix issue where sendCandidate signature in SignalingClient doesn't accept publicAddr yet
sc_path = 'android/app/src/main/java/com/directlink/client/SignalingClient.kt'
with open(sc_path, 'r', encoding='utf-8') as f:
    sc_content = f.read()

target3 = '''    fun sendCandidate(sessionId: String, candidate: String) {
        val msg = JSONObject().apply {
            put("type", "CANDIDATE")
            put("sessionId", sessionId)
            put("candidate", candidate)
            put("isHost", false)
        }
        webSocket?.send(msg.toString())
    }'''

replacement3 = '''    fun sendCandidate(sessionId: String, candidate: String, publicAddr: String = "") {
        val msg = JSONObject().apply {
            put("type", "CANDIDATE")
            put("sessionId", sessionId)
            put("candidate", candidate)
            put("isHost", false)
            if (publicAddr.isNotEmpty()) {
                put("serverReflexiveIp", publicAddr)
            }
        }
        webSocket?.send(msg.toString())
    }'''

sc_content = sc_content.replace(target3, replacement3)

with open(sc_path, 'w', encoding='utf-8') as f:
    f.write(sc_content)

with open(ma_path, 'w', encoding='utf-8') as f:
    f.write(ma_content)

print('Done!')
