package com.directlink.client

import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class NetworkClient {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var socket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null
    private val SERVER_PORT = 21118
    private var listenerJob: Job? = null

    val isConnected: Boolean
        get() = socket != null

    var videoFrameCallback: ((ByteArray) -> Unit)? = null

    // FrameId -> Pair(expectedChunks, MutableMap<ChunkIdx, ByteArray>)
    private val frameBuffers = mutableMapOf<Int, Pair<Int, MutableMap<Int, ByteArray>>>()

    suspend fun connectToHost(hostIp: String): String? = withContext(Dispatchers.IO) {
        try {
            val cleanIp = hostIp.trim()
            serverAddress = InetAddress.getByName(cleanIp)
            socket = DatagramSocket() // Bind to any local port

            // Send HELLO packet (DLP1 + 0x01)
            val helloPacket = byteArrayOf('D'.code.toByte(), 'L'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte(), 0x01)
            val dp = DatagramPacket(helloPacket, helloPacket.size, serverAddress, SERVER_PORT)
            socket?.send(dp)
            println("DirectLink: Sent UDP HELLO to $cleanIp:$SERVER_PORT")

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
            val buf = ByteArray(2048)
            val packet = DatagramPacket(buf, buf.size)

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

                    val frameEntry = frameBuffers.getOrPut(frameId) { Pair(totalChunks, mutableMapOf()) }
                    frameEntry.second[chunkIdx] = payload

                    if (frameEntry.second.size == frameEntry.first) {
                        // All chunks received ?" assemble frame
                        var assembledSize = 0
                        for (i in 0 until frameEntry.first) assembledSize += frameEntry.second[i]?.size ?: 0

                        val frameData = ByteArray(assembledSize)
                        var offset = 0
                        for (i in 0 until frameEntry.first) {
                            val chunk = frameEntry.second[i] ?: continue
                            System.arraycopy(chunk, 0, frameData, offset, chunk.size)
                            offset += chunk.size
                        }

                        // Clean up old frames (UDP drops frames naturally if packets are lost!)
                        val oldKeys = frameBuffers.keys.filter { it < frameId }
                        DebugStats.framesDropped += oldKeys.size
                        oldKeys.forEach { frameBuffers.remove(it) }
                        frameBuffers.remove(frameId)

                        DebugStats.framesCompleted++
                        videoFrameCallback?.invoke(frameData)
                    }
                } catch (e: Exception) {
                    // Socket closed or timeout
                    if (isActive) e.printStackTrace()
                }
            }
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

                if (socket != null && serverAddress != null) {
                    val dp = DatagramPacket(fullPacket, fullPacket.size, serverAddress, SERVER_PORT)
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
        socket?.close()
        socket = null
    }
}
