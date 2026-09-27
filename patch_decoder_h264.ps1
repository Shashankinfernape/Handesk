$file = "android\app\src\main\java\com\directlink\client\VideoDecoder.kt"
$content = Get-Content $file -Raw
$new_decode = '    private val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private var isRunning = false

    init {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
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
                
                val type = data[start + startCodeLen].toInt() and 0x1F
                val flags = if (type == 7 || type == 8) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0

                val inIndex = decoder.dequeueInputBuffer(1000)
                if (inIndex >= 0) {
                    val buffer: ByteBuffer? = decoder.getInputBuffer(inIndex)
                    buffer?.clear()
                    buffer?.put(data, start, naluSize)
                    decoder.queueInputBuffer(inIndex, 0, naluSize, System.nanoTime() / 1000, flags)
                }
            }

            val info = MediaCodec.BufferInfo()
            var outIndex = decoder.dequeueOutputBuffer(info, 0)
            while (outIndex >= 0) {
                decoder.releaseOutputBuffer(outIndex, true)
                outIndex = decoder.dequeueOutputBuffer(info, 0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }'

$pattern = '(?s)    private val decoder = MediaCodec\.createDecoderByType\(MediaFormat\.MIMETYPE_VIDEO_HEVC\).*?(?=    fun stop\(\))'
$content = [regex]::Replace($content, $pattern, "$new_decode
")
$content | Set-Content $file
