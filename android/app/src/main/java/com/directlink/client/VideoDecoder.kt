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
            val naluStarts = mutableListOf<Pair<Int, Int>>()
            var i = 0
            while (i < data.size - 2) {
                if (i < data.size - 3 && data[i] == 0.toByte() && data[i+1] == 0.toByte() && data[i+2] == 0.toByte() && data[i+3] == 1.toByte()) {
                    naluStarts.add(Pair(i, 4))
                    i += 4
                } else if (data[i] == 0.toByte() && data[i+1] == 0.toByte() && data[i+2] == 1.toByte()) {
                    naluStarts.add(Pair(i, 3))
                    i += 3
                } else {
                    i++
                }
            }
            if (naluStarts.isEmpty()) return

            for (j in 0 until naluStarts.size) {
                val start = naluStarts[j].first
                val startCodeLen = naluStarts[j].second
                val end = if (j == naluStarts.size - 1) data.size else naluStarts[j+1].first
                val naluSize = end - start
                
                // HEVC (H.265) NAL Unit Type Parsing
                val type = (data[start + startCodeLen].toInt() and 0x7E) ushr 1
                
                // VPS (32), SPS (33), PPS (34) are Codec Config frames
                val flags = if (type == 32 || type == 33 || type == 34) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0

                // Fast non-blocking feed to the decoder chip
                val inIndex = decoder.dequeueInputBuffer(1000)
                if (inIndex >= 0) {
                    val buffer: ByteBuffer? = decoder.getInputBuffer(inIndex)
                    buffer?.clear()
                    buffer?.put(data, start, naluSize)
                    decoder.queueInputBuffer(inIndex, 0, naluSize, System.nanoTime() / 1000, flags)
                }
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
