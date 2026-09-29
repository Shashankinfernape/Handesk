package com.directlink.client

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer

class VideoDecoder(private val surface: Surface, width: Int, height: Int, private val onVideoSizeChanged: ((Int, Int) -> Unit)? = null) {
    private val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
    private var isRunning = false

    // IDR gate: drop all P-frames until we successfully receive a keyframe.
    // Over Tailscale, the decoder initializes slowly. Without this gate, P-frames
    // arrive before the IDR is decoded and cause a permanently corrupt/blank screen.
    private var hasReceivedIDR = false

    init {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height)
            
            // Enable Android Low-Latency Decoding (API 30+)
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            
            decoder.configure(format, surface, null, 0)
            DebugStats.decoderConfigured = true
        } catch (e: Exception) {
            DebugStats.lastError = "Config Error: ${e.message}"
        }
    }

    fun start() {
        if (!isRunning) {
            decoder.start()
            isRunning = true
        }
    }

    fun decodeNalu(data: ByteArray) {
        if (!isRunning) return
        try {
            // Detect if this frame is a keyframe (contains VPS=32, IDR_W_RADL=19, or IDR_N_LP=20)
            // NVENC always outputs VPS/SPS/PPS/IDR together in one blob for keyframes.
            val isKeyFrame = containsNaluType(data, 19) ||
                             containsNaluType(data, 20) ||
                             containsNaluType(data, 32)

            if (!hasReceivedIDR && !isKeyFrame) {
                // Drop P-frames until we have a reference keyframe.
                // P-frames without a reference = guaranteed blank/corrupted screen.
                DebugStats.framesDropped++
                return
            }

            if (isKeyFrame) {
                // Flush any corrupt state from previous P-frames before feeding the IDR
                if (hasReceivedIDR) {
                    try { decoder.flush() } catch (e: Exception) {}
                }
                hasReceivedIDR = true
            }

            // Feed the complete assembled frame to the hardware decoder
            val inIndex = decoder.dequeueInputBuffer(16_000) // 16ms timeout — OK since we're on own thread
            if (inIndex >= 0) {
                val buffer = decoder.getInputBuffer(inIndex)
                buffer?.clear()
                buffer?.put(data)
                decoder.queueInputBuffer(inIndex, 0, data.size, System.nanoTime() / 1000, 0)
            }

            // Drain all ready output frames to the surface
            val info = MediaCodec.BufferInfo()
            var outIndex = decoder.dequeueOutputBuffer(info, 0)
            while (outIndex >= 0 || outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val format = decoder.outputFormat
                    var w = format.getInteger(MediaFormat.KEY_WIDTH)
                    var h = format.getInteger(MediaFormat.KEY_HEIGHT)
                    
                    if (format.containsKey("crop-left") && format.containsKey("crop-right")) {
                        w = format.getInteger("crop-right") - format.getInteger("crop-left") + 1
                    }
                    if (format.containsKey("crop-top") && format.containsKey("crop-bottom")) {
                        h = format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
                    }
                    
                    if (w > 0 && h > 0) {
                        onVideoSizeChanged?.invoke(w, h)
                    }
                } else if (outIndex >= 0) {
                    decoder.releaseOutputBuffer(outIndex, true)
                }
                outIndex = decoder.dequeueOutputBuffer(info, 0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Scan an Annex-B H.265 bitstream for a specific NALU type
    private fun containsNaluType(data: ByteArray, targetType: Int): Boolean {
        var i = 0
        while (i < data.size - 4) {
            val is4ByteStart = data[i] == 0.toByte() && data[i+1] == 0.toByte() &&
                               data[i+2] == 0.toByte() && data[i+3] == 1.toByte()
            val is3ByteStart = data[i] == 0.toByte() && data[i+1] == 0.toByte() &&
                               data[i+2] == 1.toByte()
            if (is4ByteStart && i + 4 < data.size) {
                val naluType = (data[i + 4].toInt() and 0x7E) ushr 1
                if (naluType == targetType) return true
                i += 4
            } else if (is3ByteStart && i + 3 < data.size) {
                val naluType = (data[i + 3].toInt() and 0x7E) ushr 1
                if (naluType == targetType) return true
                i += 3
            } else {
                i++
            }
        }
        return false
    }

    fun stop() {
        if (isRunning) {
            isRunning = false
            try {
                decoder.stop()
                decoder.release()
            } catch (e: Exception) {}
        }
    }
}
