$file = "android\app\src\main\java\com\directlink\client\VideoDecoder.kt"
$content = Get-Content $file -Raw
$new_decode = '    fun decodeNalu(data: ByteArray) {
        if (!isRunning) return
        try {
            // Fast non-blocking feed to the decoder chip (Feed entire frame/Access Unit)
            val inIndex = decoder.dequeueInputBuffer(1000)
            if (inIndex >= 0) {
                val buffer: ByteBuffer? = decoder.getInputBuffer(inIndex)
                buffer?.clear()
                buffer?.put(data)
                decoder.queueInputBuffer(inIndex, 0, data.size, System.nanoTime() / 1000, 0)
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

$pattern = '(?s)    fun decodeNalu\(data: ByteArray\) \{.*?(?=    fun stop\(\))'
$content = [regex]::Replace($content, $pattern, "$new_decode

")
$content | Set-Content $file
