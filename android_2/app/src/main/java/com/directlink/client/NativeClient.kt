package com.directlink.client

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NativeClient {
    init {
        System.loadLibrary("native-lib")
    }

    var isConnected: Boolean = false
        private set

    private var activeIp: String = ""
    
    private val audioTrack = AudioTrack(
        AudioManager.STREAM_MUSIC,
        48000,
        AudioFormat.CHANNEL_OUT_STEREO,
        AudioFormat.ENCODING_PCM_16BIT,
        AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT) * 4,
        AudioTrack.MODE_STREAM
    ).apply { play() }

    // Called from C++ JNI when an 0x08 packet arrives
    fun onAudioData(pcmData: ByteArray) {
        audioTrack.write(pcmData, 0, pcmData.size)
    }

    suspend fun connectToHost(localIp: String, publicAddr: String = ""): String? = withContext(Dispatchers.IO) {
        val host = if (localIp.contains(":")) localIp.substringBefore(":") else localIp
        activeIp = host
        isConnected = true
        null
    }

    fun startNative(surface: Surface) {
        connectNative(activeIp, surface)
    }

    fun disconnect() {
        disconnectNative()
        isConnected = false
    }

    private fun sendInputPacket(payload: ByteArray) {
        if (!isConnected) return
        val fullPacket = ByteArray(15)
        fullPacket[0] = 'D'.code.toByte()
        fullPacket[1] = 'L'.code.toByte()
        fullPacket[2] = 'P'.code.toByte()
        fullPacket[3] = '1'.code.toByte()
        fullPacket[4] = 0x07
        System.arraycopy(payload, 0, fullPacket, 5, payload.size)
        sendInputNative(fullPacket)
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

    fun sendQualityChange(level: Int) {
        val payload = ByteArray(2)
        payload[0] = 0x05
        payload[1] = level.toByte()
        sendInputPacket(payload)
    }

    fun togglePcMute() {
        // Send VK_VOLUME_MUTE (173) down and up
        sendKeyEvent(173, true)
        sendKeyEvent(173, false)
    }

    // JNI External hooks
    private external fun connectNative(ip: String, surface: Surface)
    private external fun disconnectNative()
    private external fun sendInputNative(packet: ByteArray)
}
