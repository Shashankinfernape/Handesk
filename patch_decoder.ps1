$file = "android\app\src\main\java\com\directlink\client\VideoDecoder.kt"
$content = Get-Content $file -Raw
$pattern = '(?s)val naluStarts = mutableListOf<Pair<Int, Int>>\(\).*?// Fast non-blocking feed to the decoder chip'
$replacement = '// Do NOT split NALUs! Android MediaCodec expects a complete access unit (frame) per queueInputBuffer call.
                // Splitting NALUs causes the decoder to treat each slice as a separate frame, resulting in green smudging!
                val isConfig = (data.size > 4 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte() && 
                               ((data[4].toInt() and 0x7E) ushr 1) in 32..34)
                val flags = if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0'
$content = [regex]::Replace($content, $pattern, $replacement)

$pattern2 = '(?s)val inIndex = decoder\.dequeueInputBuffer\(-1\)\s*if \(inIndex >= 0\) \{\s*val buffer: ByteBuffer\? = decoder\.getInputBuffer\(inIndex\)\s*buffer\?\.clear\(\)\s*buffer\?\.put\(data, start, naluSize\)\s*decoder\.queueInputBuffer\(inIndex, 0, naluSize, System\.nanoTime\(\) / 1000, flags\)\s*\}\s*\}'
$replacement2 = 'val inIndex = decoder.dequeueInputBuffer(-1)
                if (inIndex >= 0) {
                    val buffer = decoder.getInputBuffer(inIndex)
                    buffer?.clear()
                    buffer?.put(data)
                    decoder.queueInputBuffer(inIndex, 0, data.size, System.nanoTime() / 1000, flags)
                }'
$content = [regex]::Replace($content, $pattern2, $replacement2)

$content | Set-Content $file
