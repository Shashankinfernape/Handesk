package com.directlink.client

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.InetAddress

class NetworkClient {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    var socket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null
    private var targetPort = 21118
    private var listenerJob: Job? = null

    val isConnected: Boolean
        get() = socket != null

    var inputPacketSender: ((ByteArray) -> Unit)? = null
    var videoFrameCallback: ((ByteArray) -> Unit)? = null

    // Decoder channel: UDP listener drops assembled frames here, decoder coroutine pulls them.
    // Capacity 16 with DROP_LATEST: if channel fills, NEW frames are dropped, preserving the IDR
    // already in the queue. IDR frames must never be evicted or the screen stays blank forever.
    private val decoderChannel = Channel<ByteArray>(capacity = 16, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_LATEST)

    // FrameId -> Pair(expectedChunks, MutableMap<ChunkIdx, ByteArray>)
    // Also track arrival time to evict stale incomplete frames
    private val frameBuffers = mutableMapOf<Int, Triple<Int, MutableMap<Int, ByteArray>, Long>>()

    var tcpSocket: java.net.Socket? = null
    private var hasReceivedIDR = false

    suspend fun connectToHostTcp(ip: String): String? = withContext(Dispatchers.IO) {
        try {
            val host = if (ip.contains(":")) ip.substringBefore(":") else ip
            println("DirectLink: Connecting TCP FLAWLESS MODE to $host:21118")
            
            tcpSocket = java.net.Socket()
            tcpSocket?.tcpNoDelay = true
            tcpSocket?.connect(InetSocketAddress(host, 21118), 3000)
            
            val input = tcpSocket!!.getInputStream()
            val output = tcpSocket!!.getOutputStream()
            
            inputPacketSender = { packet ->
                try {
                    // Send directly over TCP. We don't even need length headers for input 
                    // since our Windows reader is just reading chunks and passing to input handler!
                    // Wait, Windows expects UDP, so it reads packet by packet.
                    // For TCP, we can just write the 15-byte packet directly!
                    output.write(packet)
                    output.flush()
                } catch(e: Exception){}
            }
            
            // We are connected! Start reading frames directly from TCP (no chunking, no drops!)
            decoderJob?.cancel()
            decoderJob = launch(Dispatchers.Default) {
                for (frameData in decoderChannel) {
                    try {
                        videoFrameCallback?.invoke(frameData)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            listenerJob?.cancel()
            listenerJob = launch(Dispatchers.IO) {
                try {
                    while (isActive) {
                        // Read 4-byte length
                        val lenBytes = ByteArray(4)
                        var read = 0
                        while (read < 4) {
                            val r = input.read(lenBytes, read, 4 - read)
                            if (r == -1) throw Exception("TCP Disconnected")
                            read += r
                        }
                        
                        val len = (lenBytes[0].toInt() and 0xFF) or
                                  ((lenBytes[1].toInt() and 0xFF) shl 8) or
                                  ((lenBytes[2].toInt() and 0xFF) shl 16) or
                                  ((lenBytes[3].toInt() and 0xFF) shl 24)
                                  
                        if (len > 5 * 1024 * 1024) throw Exception("Frame too large: $len") // Sanity check

                        val frameData = ByteArray(len)
                        read = 0
                        while (read < len) {
                            val r = input.read(frameData, read, len - read)
                            if (r == -1) throw Exception("TCP Disconnected")
                            read += r
                        }
                        
                        // IDR check so we don't start on a P-frame
                        if (!hasReceivedIDR) {
                            var isKeyFrame = false
                            // Simplistic IDR check for H.265 (NAL type 19, 20, 32)
                            if (frameData.size > 5) {
                                val naluType = (frameData[4].toInt() and 0x7E) shr 1
                                if (naluType == 19 || naluType == 20 || naluType == 32) isKeyFrame = true
                            }
                            if (!isKeyFrame) continue
                            hasReceivedIDR = true
                        }
                        
                        DebugStats.framesReceived++
                        DebugStats.framesCompleted++
                        decoderChannel.trySend(frameData)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            null // Success
        } catch (e: Exception) {
            e.printStackTrace()
            e.message ?: "TCP Connect Error"
        }
    }

    suspend fun connectToHost(localIp: String, publicAddr: String = ""): String? = withContext(Dispatchers.IO) {
        try {
            if (socket == null || socket?.isClosed == true) {
                socket = DatagramSocket() // Bind to any local port
            }
            
            // CRITICAL UDP FIX: The default Android UDP socket buffer is only ~128KB.
            // When NVENC bursts a keyframe or a complex P-frame (e.g. 50+ packets instantly),
            // the Android OS network stack drops them before our app can even read them!
            // Increasing this to 4MB guarantees the OS will hold the packets until we parse them.
            try { socket?.receiveBufferSize = 1024 * 1024 * 4 } catch(e: Exception){}
            
            socket?.soTimeout = 1500 // Increased timeout for PC init for volley
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
            for (i in 1..6) {
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
                } catch (e: Exception) {    kotlinx.coroutines.delay(500)
                }
            }

            if (!connected) {
                socket?.close()
                println("DirectLink: UDP Hole punch TIMED OUT. NAT is too strict.")
                return@withContext "Timeout"
            }

            socket?.soTimeout = 0 // Reset to infinite for the listener loop

            // Start Listening Loop
            startUdpVideoListener()
            null // Success
        } catch (e: Exception) {
            e.printStackTrace()
            e.message ?: "Unknown error"
        }
    }

    private fun startUdpVideoListener() {
        listenerJob?.cancel()
        listenerJob = scope.launch {
            // CRITICAL: Boost thread priority so OS never pauses our UDP packet receiver
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)

            val buf = ByteArray(2048)
            val packet = DatagramPacket(buf, buf.size)

            // Launch the DECODER on a completely separate coroutine/thread.
            // The UDP listener must NEVER call blocking decoder methods (dequeueInputBuffer etc.)
            // or the socket receive buffer overflows and we lose packets -> P-frame corruption!
            val decoderJob = launch(Dispatchers.Default) {
                for (frameData in decoderChannel) {
                    try {
                        videoFrameCallback?.invoke(frameData)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            while (isActive) {
                try {
                    socket?.receive(packet)
                    val data = packet.data
                    val len = packet.length

                    if (len < 15) continue
                    if (data[0] != 'D'.code.toByte() || data[1] != 'L'.code.toByte() || data[2] != 'P'.code.toByte() || data[3] != '1'.code.toByte()) continue
                    
                    val packetType = data[4].toInt()
                    if (packetType != 0x06) continue // Must be VIDEO_FRAME

                    DebugStats.packetsReceived++

                    val frameId = ((data[8].toInt() and 0xFF) shl 24) or
                                  ((data[7].toInt() and 0xFF) shl 16) or
                                  ((data[6].toInt() and 0xFF) shl 8)  or
                                  (data[5].toInt() and 0xFF)
                    
                    val chunkIdx = ((data[10].toInt() and 0xFF) shl 8) or (data[9].toInt() and 0xFF)
                    val totalChunks = ((data[12].toInt() and 0xFF) shl 8) or (data[11].toInt() and 0xFF)
                    val chunkLen = ((data[14].toInt() and 0xFF) shl 8) or (data[13].toInt() and 0xFF)

                    if (len < 15 + chunkLen) continue

                    val payload = data.copyOfRange(15, 15 + chunkLen)
                    val nowMs = System.currentTimeMillis()

                    val staleKeys = frameBuffers.entries.filter { nowMs - it.value.third > 300 }.map { it.key }
                    for (staleId in staleKeys) {
                        val entry = frameBuffers[staleId] ?: continue
                        // ASSEMBLE STALE FRAME EXACTLY AT OFFSETS
                        var maxOffset = 0
                        for ((idx, chunk) in entry.second) {
                            maxOffset = maxOf(maxOffset, (idx * 1200) + chunk.size)
                        }
                        if (maxOffset > 0) {
                            val frameData = ByteArray(maxOffset)
                            for ((idx, chunk) in entry.second) {
                                System.arraycopy(chunk, 0, frameData, idx * 1200, chunk.size)
                            }
                            decoderChannel.trySend(frameData)
                        }
                        DebugStats.framesDropped++ 
                        frameBuffers.remove(staleId)
                    }

                    val frameEntry = frameBuffers.getOrPut(frameId) { Triple(totalChunks, mutableMapOf(), nowMs) }
                    frameEntry.second[chunkIdx] = payload

                    if (frameEntry.second.size == frameEntry.first) {
                        // All chunks received — assemble exactly
                        var maxOffset = 0
                        for ((idx, chunk) in frameEntry.second) {
                            maxOffset = maxOf(maxOffset, (idx * 1200) + chunk.size)
                        }
                        val frameData = ByteArray(maxOffset)
                        for ((idx, chunk) in frameEntry.second) {
                            System.arraycopy(chunk, 0, frameData, idx * 1200, chunk.size)
                        }

                        val oldKeys = frameBuffers.keys.filter { it <= frameId }
                        DebugStats.framesDropped += (oldKeys.size - 1).coerceAtLeast(0)
                        oldKeys.forEach { frameBuffers.remove(it) }

                        DebugStats.framesCompleted++
                        decoderChannel.trySend(frameData)
                    }
                } catch (e: Exception) {
                    // Socket closed or timeout
                    if (isActive) e.printStackTrace()
                }
            }
            decoderJob.cancel()
        }
    }

    // --- Input (Sent over UDP) ---
    private fun sendInputPacket(payload: ByteArray) {
        scope.launch {
            try {
                // Prepend MAGIC (4) + INPUT TYPE (0x07)
                val fullPacket = ByteArray(5 + payload.size)
                fullPacket[0] = 'D'.code.toByte()
                fullPacket[1] = 'L'.code.toByte()
                fullPacket[2] = 'P'.code.toByte()
                fullPacket[3] = '1'.code.toByte()
                fullPacket[4] = 0x07
                System.arraycopy(payload, 0, fullPacket, 5, payload.size)

                if (inputPacketSender != null) { 
                    inputPacketSender?.invoke(fullPacket) 
                } else if (socket != null && serverAddress != null) {
                    val dp = DatagramPacket(fullPacket, fullPacket.size, serverAddress, targetPort)
                    socket?.send(dp)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun sendMouseMove(normX: Int, normY: Int) {
        val payload = ByteArray(5)
        payload[0] = 0x01
        payload[1] = (normX ushr 8).toByte()
        payload[2] = normX.toByte()
        payload[3] = (normY ushr 8).toByte()
        payload[4] = normY.toByte()
        sendInputPacket(payload)
    }

    fun sendMouseButton(button: Int, down: Boolean) {
        val payload = ByteArray(3)
        payload[0] = 0x02
        payload[1] = button.toByte()
        payload[2] = if (down) 1 else 0
        sendInputPacket(payload)
    }

    fun sendMouseScroll(delta: Int) {
        val payload = ByteArray(3)
        payload[0] = 0x03
        payload[1] = (delta ushr 8).toByte()
        payload[2] = delta.toByte()
        sendInputPacket(payload)
    }

    fun sendKeyEvent(vkCode: Int, down: Boolean) {
        val payload = ByteArray(3)
        payload[0] = 0x04
        payload[1] = vkCode.toByte()
        payload[2] = if (down) 1 else 0
        sendInputPacket(payload)
    }

    fun disconnect() {
        listenerJob?.cancel()
        listenerJob = null
        decoderChannel.close()
        socket?.close()
        socket = null
    }
    suspend fun connectToRelay(relayIp: String, relayPort: Int, sessionId: String): String? = withContext(Dispatchers.IO) {
        try {
            val cleanIp = relayIp.trim()
            serverAddress = InetAddress.getByName(cleanIp)
            targetPort = relayPort
            socket = DatagramSocket() // Bind to any local port

            // Send BIND packet: BIND:<sessionId>:CLIENT
            val bindStr = "BIND:$sessionId:CLIENT"
            val bindPacket = bindStr.toByteArray()
            val dp = DatagramPacket(bindPacket, bindPacket.size, serverAddress, targetPort)
            
            socket?.send(dp)
            println("DirectLink: Sent UDP BIND to Relay $cleanIp:$targetPort")

            // Wait a brief moment for relay to register
            delay(100)
            
            // Send HELLO packet (DLP1 + 0x01) so the Rust Host gets it through the relay!
            val helloPacket = byteArrayOf('D'.code.toByte(), 'L'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte(), 0x01)
            val dp2 = DatagramPacket(helloPacket, helloPacket.size, serverAddress, targetPort)
            socket?.send(dp2)

            // Start Listening Loop
            startUdpVideoListener()
            null // Success
        } catch (e: Exception) {
            e.printStackTrace()
            e.message ?: "Unknown error"
        }
    }

}
