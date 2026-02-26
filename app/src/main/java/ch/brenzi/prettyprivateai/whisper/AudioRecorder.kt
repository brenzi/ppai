package ch.brenzi.prettyprivateai.whisper

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Records 16kHz mono float PCM audio using AudioRecord.
 * This is the native format whisper.cpp expects, avoiding any transcoding.
 *
 * Recording is capped at [MAX_DURATION_SECONDS] to keep transcription time
 * bounded on mobile devices.
 */
class AudioRecorder {

    companion object {
        /** Maximum recording duration in seconds. */
        const val MAX_DURATION_SECONDS = 60

        /** 20ms frame at 16 kHz — unit for silence detection. */
        private const val FRAME_SIZE = 320

        /** Mean-absolute-amplitude below this is considered silence (float PCM in [-1,1]). */
        private const val SILENCE_THRESHOLD = 0.02f

        /** Minimum consecutive silent frames to qualify as an inter-word gap (80 ms). */
        private const val MIN_GAP_FRAMES = 4

        /** Default search radius (±2 s) for [findSilenceGap]. */
        const val GAP_SEARCH_RADIUS = 32000
    }

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_FLOAT

    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        .coerceAtLeast(sampleRate / 10 * 4) // at least 100ms of float samples

    private val maxSamples = sampleRate * MAX_DURATION_SECONDS

    @Volatile
    private var recording = false

    private val lock = Any()
    private val pcmBuffer = mutableListOf<Float>()

    @Volatile
    private var finished = false

    private val _amplitudes = MutableStateFlow<List<Float>>(emptyList())
    val amplitudes: StateFlow<List<Float>> = _amplitudes.asStateFlow()

    /**
     * Blocking call — run on Dispatchers.IO.
     * Reads 100ms chunks until [stopRecording] is called or [MAX_DURATION_SECONDS] is reached.
     */
    fun startRecording() {
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize,
        )

        recorder.startRecording()
        recording = true
        finished = false
        synchronized(lock) { pcmBuffer.clear() }
        _amplitudes.value = emptyList()

        val chunkSize = sampleRate / 10 // 100ms = 1600 samples at 16kHz
        val chunk = FloatArray(chunkSize)
        var totalSamples = 0

        try {
            while (recording && totalSamples < maxSamples) {
                val read = recorder.read(chunk, 0, chunkSize, AudioRecord.READ_BLOCKING)
                if (read > 0) {
                    val remaining = maxSamples - totalSamples
                    val toAdd = read.coerceAtMost(remaining)
                    synchronized(lock) {
                        for (i in 0 until toAdd) {
                            pcmBuffer.add(chunk[i])
                        }
                    }
                    totalSamples += toAdd
                    // Compute amplitude for waveform display
                    var maxAmp = 0f
                    for (i in 0 until read) {
                        val a = abs(chunk[i])
                        if (a > maxAmp) maxAmp = a
                    }
                    val list = _amplitudes.value
                    _amplitudes.value = (list + maxAmp.coerceIn(0f, 1f)).takeLast(30)
                }
            }
        } finally {
            recorder.stop()
            recorder.release()
            recording = false
            finished = true
        }
    }

    fun stopRecording() {
        recording = false
    }

    /** Returns true once [startRecording] has fully exited. */
    fun isFinished(): Boolean = finished

    fun getSamples(): FloatArray = synchronized(lock) { pcmBuffer.toFloatArray() }

    /** Returns the current number of recorded samples (thread-safe, no copy). */
    fun getSampleCount(): Int = synchronized(lock) { pcmBuffer.size }

    /** Returns a copy of samples in `[from, to)`, clamped to buffer bounds. */
    fun getSamplesRange(from: Int, to: Int): FloatArray = synchronized(lock) {
        val start = from.coerceIn(0, pcmBuffer.size)
        val end = to.coerceIn(start, pcmBuffer.size)
        if (start == end) FloatArray(0)
        else FloatArray(end - start) { pcmBuffer[start + it] }
    }

    /**
     * Finds the best sample position for a chunk boundary near [around]
     * by locating an inter-word silence gap (≥ 80 ms of consecutive quiet frames).
     *
     * Scans 20 ms frames within `[around − searchRadius, around + searchRadius]`.
     * Priority: qualified gap (≥ [MIN_GAP_FRAMES]) closest to [around],
     * then longest sub-threshold run, then quietest single frame.
     */
    fun findSilenceGap(
        around: Int,
        searchRadius: Int = GAP_SEARCH_RADIUS,
    ): Int = synchronized(lock) {
        val bufSize = pcmBuffer.size
        val searchStart = (around - searchRadius).coerceIn(0, bufSize)
        val searchEnd = (around + searchRadius).coerceIn(searchStart, bufSize)
        if (searchEnd - searchStart < FRAME_SIZE) {
            return@synchronized around.coerceIn(0, bufSize)
        }

        val frameCount = (searchEnd - searchStart) / FRAME_SIZE

        // Compute energy per 20 ms frame
        val energies = FloatArray(frameCount)
        for (f in 0 until frameCount) {
            val frameStart = searchStart + f * FRAME_SIZE
            var sum = 0f
            for (i in 0 until FRAME_SIZE) {
                sum += abs(pcmBuffer[frameStart + i])
            }
            energies[f] = sum / FRAME_SIZE
        }

        // Collect contiguous runs of below-threshold frames
        data class Run(val start: Int, val length: Int)
        val runs = mutableListOf<Run>()
        var runStart = -1
        for (f in 0 until frameCount) {
            if (energies[f] < SILENCE_THRESHOLD) {
                if (runStart == -1) runStart = f
            } else {
                if (runStart != -1) {
                    runs.add(Run(runStart, f - runStart))
                    runStart = -1
                }
            }
        }
        if (runStart != -1) runs.add(Run(runStart, frameCount - runStart))

        fun runCenterSample(run: Run): Int {
            val midFrame = run.start + run.length / 2
            return searchStart + midFrame * FRAME_SIZE + FRAME_SIZE / 2
        }

        // 1) Prefer runs ≥ MIN_GAP_FRAMES — pick closest to target
        val qualified = runs.filter { it.length >= MIN_GAP_FRAMES }
        if (qualified.isNotEmpty()) {
            return@synchronized qualified
                .minBy { abs(runCenterSample(it) - around) }
                .let { runCenterSample(it) }
        }

        // 2) Longest sub-threshold run (even if short)
        if (runs.isNotEmpty()) {
            return@synchronized runs.maxBy { it.length }.let { runCenterSample(it) }
        }

        // 3) Quietest single frame
        var qIdx = 0
        for (f in 1 until frameCount) {
            if (energies[f] < energies[qIdx]) qIdx = f
        }
        searchStart + qIdx * FRAME_SIZE + FRAME_SIZE / 2
    }
}
