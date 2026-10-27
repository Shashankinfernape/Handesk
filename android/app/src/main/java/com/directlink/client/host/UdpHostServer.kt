package com.directlink.client.host

import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.atomic.AtomicInteger

class UdpHostServer(
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val isHevc: Boolean = true,
    private val onIdrRequested: () -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var socket: DatagramSocket? = null
    private var listenerJob: Job? = null
    
    @Volatile
    private var activeClient: SocketAddress? = null
    private val videoSequence = AtomicInteger(0)

    val isClientConnected: Boolean
        get() = activeClient != null

    fun start() {
        stop()
        try {
            socket = DatagramSocket(21118)
            try { socket?.sendBufferSize = 1024 * 1024 * 4 } catch (_: Exception) {}
            try { socket?.receiveBufferSize = 1024 * 1024 * 2 } catch (_: Exception) {}

            println("HandeskHost: UDP Server listening on 0.0.0.0:21118")

            listenerJob = scope.launch {
                val recvBuf = ByteArray(2048)
                val packet = DatagramPacket(recvBuf, recvBuf.size)

                while (isActive) {
                    try {
                        socket?.receive(packet)
                        val len = packet.length
                        if (len < 5) continue
                        val data = packet.data

                        // Verify DLP1 Magic
                        if (data[0] != 'D'.code.toByte() || data[1] != 'L'.code.toByte() ||
                            data[2] != 'P'.code.toByte() || data[3] != '1'.code.toByte()) {
                            continue
                        }

                        val packetType = data[4].toInt() and 0xFF
                        val sender = packet.socketAddress

                        when (packetType) {
                            0x01 -> { // PACKET_HELLO
                                activeClient = sender
                                println("HandeskHost: Client registered: $sender")
                                sendResolution(sender)
                                onIdrRequested()
                            }
                            0x0A -> { // PACKET_REQUEST_IDR
                                onIdrRequested()
                            }
                            0x0B -> { // PACKET_PING
                                if (len >= 13) {
                                    val pong = ByteArray(13)
                                    pong[0] = 'D'.code.toByte()
                                    pong[1] = 'L'.code.toByte()
                                    pong[2] = 'P'.code.toByte()
                                    pong[3] = '1'.code.toByte()
                                    pong[4] = 0x0C // PACKET_PONG
                                    System.arraycopy(data, 5, pong, 5, 8)
                                    val pongPacket = DatagramPacket(pong, pong.size, sender)
                                    socket?.send(pongPacket)
                                }
                            }
                            0x07 -> { // PACKET_INPUT
                                handleInputPacket(data, len)
                            }
                        }
                    } catch (e: Exception) {
                        if (!isActive) break
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private var currentTouchX = 0f
    private var currentTouchY = 0f

    private fun handleInputPacket(data: ByteArray, len: Int) {
        if (len < 6) return
        val inputType = data[5].toInt() and 0xFF
        val service = HandeskAccessibilityService.instance

        when (inputType) {
            0x01 -> { // Mouse / Touch Move
                if (len >= 10) {
                    val normX = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
                    val normY = ((data[8].toInt() and 0xFF) shl 8) or (data[9].toInt() and 0xFF)
                    currentTouchX = (normX.toFloat() / 65535f) * screenWidth
                    currentTouchY = (normY.toFloat() / 65535f) * screenHeight
                    service?.injectTouchMove(currentTouchX, currentTouchY)
                }
            }
            0x02 -> { // Mouse Button / Touch Down & Up
                if (len >= 8) {
                    val button = data[6].toInt() and 0xFF
                    val isDown = data[7].toInt() != 0
                    when (button) {
                        1 -> { // Left Click / Primary Touch
                            if (isDown) {
                                service?.injectTouchDown(currentTouchX, currentTouchY)
                            } else {
                                service?.injectTouchUp(currentTouchX, currentTouchY)
                            }
                        }
                        2 -> { // Right Click -> Android Back
                            if (isDown) {
                                service?.injectBack()
                            }
                        }
                        3 -> { // Middle Click -> Android Home
                            if (isDown) {
                                service?.injectHome()
                            }
                        }
                    }
                }
            }
            0x03 -> { // Mouse Scroll
                if (len >= 8) {
                    val delta = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
                    val signedDelta = delta.toShort()
                    service?.injectScroll(currentTouchX, currentTouchY, signedDelta.toFloat() * 5f)
                }
            }
            0x04 -> { // Key Event
                if (len >= 8) {
                    val vkCode = data[6].toInt() and 0xFF
                    val isDown = data[7].toInt() != 0
                    if (isDown) {
                        when (vkCode) {
                            0x1B -> service?.injectBack() // ESC -> Back
                            0x24 -> service?.injectHome() // HOME -> Home
                            0x09 -> service?.injectRecents() // TAB -> Recents
                        }
                    }
                }
            }
        }
    }

    private fun sendResolution(client: SocketAddress) {
        val resPacket = ByteArray(14)
        resPacket[0] = 'D'.code.toByte()
        resPacket[1] = 'L'.code.toByte()
        resPacket[2] = 'P'.code.toByte()
        resPacket[3] = '1'.code.toByte()
        resPacket[4] = 0x0D // PACKET_RESOLUTION

        // Width LE (4 bytes)
        resPacket[5] = (screenWidth and 0xFF).toByte()
        resPacket[6] = ((screenWidth ushr 8) and 0xFF).toByte()
        resPacket[7] = ((screenWidth ushr 16) and 0xFF).toByte()
        resPacket[8] = ((screenWidth ushr 24) and 0xFF).toByte()

        // Height LE (4 bytes)
        resPacket[9] = (screenHeight and 0xFF).toByte()
        resPacket[10] = ((screenHeight ushr 8) and 0xFF).toByte()
        resPacket[11] = ((screenHeight ushr 16) and 0xFF).toByte()
        resPacket[12] = ((screenHeight ushr 24) and 0xFF).toByte()

        // Codec Type (1 byte): 0x01 = HEVC, 0x02 = AVC
        resPacket[13] = if (isHevc) 0x01 else 0x02

        try {
            val dp = DatagramPacket(resPacket, resPacket.size, client)
            socket?.send(dp)
        } catch (_: Exception) {}
    }

    fun broadcastVideoFrame(nalu: ByteArray) {
        val client = activeClient ?: return
        val sock = socket ?: return

        val maxPayload = 1000
        val totalChunks = ((nalu.size + maxPayload - 1) / maxPayload)
        val seq = videoSequence.getAndIncrement()

        val packetBuf = ByteArray(15 + maxPayload)
        packetBuf[0] = 'D'.code.toByte()
        packetBuf[1] = 'L'.code.toByte()
        packetBuf[2] = 'P'.code.toByte()
        packetBuf[3] = '1'.code.toByte()
        packetBuf[4] = 0x06 // PACKET_VIDEO

        // Sequence number LE (4 bytes)
        packetBuf[5] = (seq and 0xFF).toByte()
        packetBuf[6] = ((seq ushr 8) and 0xFF).toByte()
        packetBuf[7] = ((seq ushr 16) and 0xFF).toByte()
        packetBuf[8] = ((seq ushr 24) and 0xFF).toByte()

        // Total chunks LE (2 bytes)
        packetBuf[11] = (totalChunks and 0xFF).toByte()
        packetBuf[12] = ((totalChunks ushr 8) and 0xFF).toByte()

        var offset = 0
        for (chunkIndex in 0 until totalChunks) {
            val chunkLen = Math.min(maxPayload, nalu.size - offset)

            // Chunk Index LE (2 bytes)
            packetBuf[9] = (chunkIndex and 0xFF).toByte()
            packetBuf[10] = ((chunkIndex ushr 8) and 0xFF).toByte()

            // Chunk Length LE (2 bytes)
            packetBuf[13] = (chunkLen and 0xFF).toByte()
            packetBuf[14] = ((chunkLen ushr 8) and 0xFF).toByte()

            System.arraycopy(nalu, offset, packetBuf, 15, chunkLen)
            offset += chunkLen

            val totalPacketLen = 15 + chunkLen
            try {
                val dp = DatagramPacket(packetBuf, totalPacketLen, client)
                sock.send(dp)

                if (totalChunks > 4 && chunkIndex > 0) {
                    // Pacing delay to avoid Tailscale / buffer drops
                    Thread.sleep(0, 50_000) // 50 microseconds
                }
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        listenerJob?.cancel()
        listenerJob = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        activeClient = null
    }
}
