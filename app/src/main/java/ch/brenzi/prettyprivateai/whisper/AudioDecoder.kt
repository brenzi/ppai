package ch.brenzi.prettyprivateai.whisper

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteOrder

/**
 * Decodes audio files (MP3, AAC, OGG, FLAC, WAV, etc.) to 16 kHz mono float PCM
 * in fixed-size chunks, suitable for streaming whisper.cpp transcription.
 *
 * Uses Android's MediaExtractor + MediaCodec pipeline — no external dependencies.
 * Memory-efficient: holds at most one chunk (~2 MB) in memory at a time.
 */
object AudioDecoder {

    private const val TAG = "AudioDecoder"
    private const val TARGET_SAMPLE_RATE = 16000
    private const val CHUNK_SECONDS = 10

    /**
     * Decodes the audio at [uri] in ~10 s chunks, calling [onChunk] for each chunk
     * of 16 kHz mono float PCM. The callback blocks the decode thread, so
     * transcription can run inside it without extra memory.
     */
    fun decodeChunked(
        context: Context,
        uri: Uri,
        onChunk: (samples: FloatArray, chunkIndex: Int, estimatedTotalChunks: Int) -> Unit,
    ) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)

            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("No audio track found")

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            val estimatedTotalChunks = if (durationUs > 0) {
                ((durationUs / 1_000_000.0) / CHUNK_SECONDS).toInt().coerceAtLeast(1)
            } else 1
            Log.i(TAG, "Audio track: mime=$mime rate=$sampleRate ch=$channels duration=${durationUs/1000}ms chunks~$estimatedTotalChunks")

            val bufferCapacity = CHUNK_SECONDS * sampleRate * channels
            val buffer = ShortArray(bufferCapacity)
            var bufferPos = 0
            var chunkIndex = 0

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false

            try {
                while (true) {
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)!!
                            val read = extractor.readSampleData(buf, 0)
                            if (read < 0) {
                                codec.queueInputBuffer(
                                    inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, read, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    if (outIdx >= 0) {
                        val buf = codec.getOutputBuffer(outIdx)!!
                        buf.order(ByteOrder.LITTLE_ENDIAN)
                        val shortBuf = buf.asShortBuffer()
                        val count = bufferInfo.size / 2

                        var srcPos = 0
                        while (srcPos < count) {
                            val space = bufferCapacity - bufferPos
                            val toCopy = minOf(space, count - srcPos)
                            for (i in 0 until toCopy) {
                                buffer[bufferPos + i] = shortBuf.get(srcPos + i)
                            }
                            bufferPos += toCopy
                            srcPos += toCopy

                            if (bufferPos >= bufferCapacity) {
                                emitChunk(buffer, bufferPos, channels, sampleRate, chunkIndex, estimatedTotalChunks, onChunk)
                                chunkIndex++
                                bufferPos = 0
                            }
                        }

                        codec.releaseOutputBuffer(outIdx, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                        break
                    }
                }

                // Flush remaining samples
                if (bufferPos > 0) {
                    emitChunk(buffer, bufferPos, channels, sampleRate, chunkIndex, estimatedTotalChunks, onChunk)
                }
            } finally {
                codec.stop()
                codec.release()
            }

            Log.i(TAG, "Decoded ${chunkIndex + if (bufferPos > 0) 1 else 0} chunks total")
        } finally {
            extractor.release()
        }
    }

    private fun emitChunk(
        buffer: ShortArray,
        count: Int,
        channels: Int,
        sampleRate: Int,
        chunkIndex: Int,
        estimatedTotalChunks: Int,
        onChunk: (FloatArray, Int, Int) -> Unit,
    ) {
        val raw = if (count < buffer.size) buffer.copyOfRange(0, count) else buffer
        val mono = if (channels > 1) mixToMono(raw, channels) else raw
        val resampled = if (sampleRate != TARGET_SAMPLE_RATE) {
            resample(mono, sampleRate, TARGET_SAMPLE_RATE)
        } else {
            mono
        }
        val floats = FloatArray(resampled.size) { resampled[it].toFloat() / 32768f }
        Log.i(TAG, "Chunk $chunkIndex: ${floats.size} samples (${floats.size / 16000f}s)")
        onChunk(floats, chunkIndex, estimatedTotalChunks)
    }

    private fun mixToMono(samples: ShortArray, channels: Int): ShortArray {
        val mono = ShortArray(samples.size / channels)
        for (i in mono.indices) {
            var sum = 0L
            for (ch in 0 until channels) {
                sum += samples[i * channels + ch]
            }
            mono[i] = (sum / channels).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return mono
    }

    private fun resample(samples: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        val ratio = fromRate.toDouble() / toRate
        val outLen = (samples.size / ratio).toInt()
        val out = ShortArray(outLen)
        for (i in out.indices) {
            val srcPos = i * ratio
            val idx = srcPos.toInt().coerceAtMost(samples.size - 1)
            out[i] = samples[idx]
        }
        return out
    }
}
