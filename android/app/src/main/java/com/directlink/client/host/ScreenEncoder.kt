package com.directlink.client.host

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.view.Surface
import kotlinx.coroutines.*
import java.nio.ByteBuffer

class ScreenEncoder(
    private val mediaProjection: MediaProjection,
    val width: Int,
    val height: Int,
    private val dpi: Int,
    private val onFrameEncoded: (ByteArray) -> Unit
) {
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var isRunning = false
    private var drainJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var isHevc: Boolean = true
        private set

    // Cache parameter sets (VPS/SPS/PPS for HEVC, SPS/PPS for AVC)
    private var cachedCsd: ByteArray? = null

    private fun createVideoFormat(mime: String, w: Int, h: Int): MediaFormat {
        return MediaFormat.createVideoFormat(mime, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000) // 4.0 Mbps
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second GOP
        }
    }

    fun start() {
        stop()

        // Ensure dimensions are even multiples of 16
        val encWidth = (width / 16) * 16
        val encHeight = (height / 16) * 16

        var configuredCodec: MediaCodec? = null
        var configuredSurface: Surface? = null
        var usingHevc = false

        // 1. Try HEVC (H.265) first
        try {
            val format = createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, encWidth, encHeight)
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            configuredSurface = codec.createInputSurface()
            codec.start()
            configuredCodec = codec
            usingHevc = true
            println("ScreenEncoder: Configured hardware HEVC encoder (${encWidth}x${encHeight})")
        } catch (e: Exception) {
            println("ScreenEncoder: HEVC setup failed ($e), falling back to AVC/H.264")
        }

        // 2. Fallback to AVC (H.264) if HEVC is unsupported or failed
        if (configuredCodec == null) {
            try {
                val format = createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encWidth, encHeight)
                val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                configuredSurface = codec.createInputSurface()
                codec.start()
                configuredCodec = codec
                usingHevc = false
                println("ScreenEncoder: Configured hardware AVC encoder (${encWidth}x${encHeight})")
            } catch (e: Exception) {
                println("ScreenEncoder: AVC setup failed ($e)")
                return
            }
        }

        encoder = configuredCodec
        inputSurface = configuredSurface
        isHevc = usingHevc

        try {
            mediaProjection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stop()
                }
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        } catch (_: Exception) {}

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "HandeskScreenMirror",
            encWidth,
            encHeight,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null,
            null
        )

        isRunning = true
        drainJob = scope.launch {
            drainEncoder()
        }
    }

    private fun drainEncoder() {
        val codec = encoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRunning && scope.isActive) {
            val status = codec.dequeueOutputBuffer(bufferInfo, 10_000) // 10ms timeout
            if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                continue
            }
            if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = codec.outputFormat
                // Extract CSD parameter sets
                val csd0 = newFormat.getByteBuffer("csd-0")
                val csd1 = if (newFormat.containsKey("csd-1")) newFormat.getByteBuffer("csd-1") else null
                
                var totalCsdLen = (csd0?.remaining() ?: 0) + (csd1?.remaining() ?: 0)
                if (totalCsdLen > 0) {
                    val csdBytes = ByteArray(totalCsdLen)
                    var offset = 0
                    if (csd0 != null) {
                        val len = csd0.remaining()
                        csd0.get(csdBytes, offset, len)
                        csd0.rewind()
                        offset += len
                    }
                    if (csd1 != null) {
                        val len = csd1.remaining()
                        csd1.get(csdBytes, offset, len)
                        csd1.rewind()
                    }
                    cachedCsd = csdBytes
                }
                continue
            }
            if (status >= 0) {
                val outBuf = codec.getOutputBuffer(status)
                if (outBuf != null && bufferInfo.size > 0) {
                    outBuf.position(bufferInfo.offset)
                    outBuf.limit(bufferInfo.offset + bufferInfo.size)

                    val isKeyframe = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0

                    if (isConfig) {
                        val csd = ByteArray(bufferInfo.size)
                        outBuf.get(csd)
                        cachedCsd = csd
                    } else {
                        val frameBytes: ByteArray
                        if (isKeyframe && cachedCsd != null) {
                            // Prepend CSD headers (VPS/SPS/PPS) to ensure standalone decodability
                            val csd = cachedCsd!!
                            frameBytes = ByteArray(csd.size + bufferInfo.size)
                            System.arraycopy(csd, 0, frameBytes, 0, csd.size)
                            outBuf.get(frameBytes, csd.size, bufferInfo.size)
                        } else {
                            frameBytes = ByteArray(bufferInfo.size)
                            outBuf.get(frameBytes)
                        }
                        onFrameEncoded(frameBytes)
                    }
                }
                codec.releaseOutputBuffer(status, false)
            }
        }
    }

    fun requestSyncFrame() {
        try {
            val bundle = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            encoder?.setParameters(bundle)
        } catch (_: Exception) {}
    }

    fun stop() {
        isRunning = false
        drainJob?.cancel()
        drainJob = null
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {}
        virtualDisplay = null

        try {
            encoder?.stop()
            encoder?.release()
        } catch (_: Exception) {}
        encoder = null

        try {
            inputSurface?.release()
        } catch (_: Exception) {}
        inputSurface = null
    }
}
