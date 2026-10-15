package com.directlink.client

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.jaredmdobson.concentus.OpusDecoder

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

    // Opus decoder — 48kHz stereo, 2 channels
    private val opusDecoder = OpusDecoder(48000, 2)
    // PCM output buffer — max 120ms frame (5760 samples * 2 channels)
    private val pcmBuffer = ShortArray(5760 * 2)

    var onStatusUpdateListener: ((Int, Int) -> Unit)? = null
    var onResolutionUpdateListener: ((Int, Int) -> Unit)? = null
    var onTextFocusUpdateListener: ((Boolean, Int, Int, Int, Int) -> Unit)? = null

    // Called from C++ JNI when an 0x09 Status packet arrives
    fun onStatusUpdate(fps: Int, bitrate: Int) {
        onStatusUpdateListener?.invoke(fps, bitrate)
    }

    // Called from C++ JNI when an 0x0D Resolution packet arrives
    fun onResolutionUpdate(width: Int, height: Int) {
        onResolutionUpdateListener?.invoke(width, height)
    }

    // Called from C++ JNI when an 0x0E Text Focus packet arrives
    fun onTextFocusUpdate(focused: Boolean, x: Int, y: Int, w: Int, h: Int) {
        onTextFocusUpdateListener?.invoke(focused, x, y, w, h)
    }

    // Called from C++ JNI when an 0x08 Opus packet arrives
    fun onAudioData(opusData: ByteArray) {
        try {
            // Decode Opus → PCM i16 stereo
            val samplesDecoded = opusDecoder.decode(opusData, 0, opusData.size, pcmBuffer, 0, 5760, false)
            if (samplesDecoded > 0) {
                // Write directly using the ShortArray (non-blocking) so we don't stall the UDP video receiver!
                audioTrack.write(pcmBuffer, 0, samplesDecoded * 2, AudioTrack.WRITE_NON_BLOCKING)
            }
        } catch (e: Exception) {
            android.util.Log.e("NativeClient", "Opus decode error: ${e.message}")
        }
    }

    suspend fun connectToHost(localIp: String, publicAddr: String = ""): String? = withContext(Dispatchers.IO) {
        activeIp = localIp.trim()
        isConnected = true
        null
    }

    fun startNative(surface: Surface) {
        connectNative(activeIp, surface)
    }

    fun updateSurface(surface: Surface?) {
        if (isConnected) {
            updateSurfaceNative(surface)
        }
    }

    fun disconnect() {
        disconnectNative()
        isConnected = false
    }

    private fun sendInputPacket(payload: ByteArray) {
        if (!isConnected) return
        val fullPacket = ByteArray(5 + payload.size)
        fullPacket[0] = 'D'.code.toByte()
        fullPacket[1] = 'L'.code.toByte()
        fullPacket[2] = 'P'.code.toByte()
        fullPacket[3] = '1'.code.toByte()
        fullPacket[4] = 0x07
        System.arraycopy(payload, 0, fullPacket, 5, payload.size)
        sendInputNative(fullPacket)
    }

    fun sendTextCommit(text: String) {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(1 + textBytes.size)
        payload[0] = 0x05 // Sub-type 0x05: Unicode Text Commit
        System.arraycopy(textBytes, 0, payload, 1, textBytes.size)
        sendInputPacket(payload)
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

    fun sendMouseHScroll(delta: Int) {
        val payload = ByteArray(3)
        payload[0] = 0x06
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
        payload[0] = 0x10
        payload[1] = level.toByte()
        sendInputPacket(payload)
    }

    fun sendFpsChange(fps: Int) {
        val payload = ByteArray(2)
        payload[0] = 0x11
        payload[1] = fps.toByte()
        sendInputPacket(payload)
    }

    val isTailscale: Boolean
        get() = activeIp.startsWith("100.") || activeIp.contains("ts.net")

    fun sendSettings(fps: Int, bitrate: Int, isAuto: Boolean = false) {
        if (isConnected) {
            sendSettingsNative(fps, bitrate, isAuto)
        }
    }

    fun togglePcMute() {
        // Send VK_VOLUME_MUTE (173) down and up
        sendKeyEvent(173, true)
        sendKeyEvent(173, false)
    }

    // JNI External hooks
    private external fun connectNative(ip: String, surface: Surface)
    private external fun updateSurfaceNative(surface: Surface?)
    private external fun disconnectNative()
    private external fun sendInputNative(packet: ByteArray)
    private external fun sendSettingsNative(fps: Int, bitrate: Int, isAuto: Boolean)
}
