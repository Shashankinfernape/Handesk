package com.directlink.client

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer

class VideoDecoder(private val surface: Surface, width: Int, height: Int) {
    private val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
    private var isRunning = false

    init {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height)
            
            // QUICK WIN: Enable Android Low-Latency Decoding (API 30+)
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
            // Do NOT split NALUs! Android MediaCodec expects a complete access unit (frame) per queueInputBuffer call.
                // Splitting NALUs causes the decoder to treat each slice as a separate frame, resulting in green smudging!
                val flags = 0 // Modern MediaCodec automatically parses VPS/SPS/PPS inline!
                val inIndex = decoder.dequeueInputBuffer(-1)
                if (inIndex >= 0) {
                    val buffer = decoder.getInputBuffer(inIndex)
                    buffer?.clear()
                    buffer?.put(data)
                    decoder.queueInputBuffer(inIndex, 0, data.size, System.nanoTime() / 1000, flags)
                }

            val info = MediaCodec.BufferInfo()
            // 0ms timeout ensures we NEVER block the UDP listener thread.
            // If the frame isn't ready to render, we instantly return and catch it on the next UDP packet!
            var outIndex = decoder.dequeueOutputBuffer(info, 0)
            while (outIndex >= 0) {
                decoder.releaseOutputBuffer(outIndex, true)
                outIndex = decoder.dequeueOutputBuffer(info, 0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
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



