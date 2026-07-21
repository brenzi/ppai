package ch.brenzi.prettyprivateai.whisper

/**
 * JNI bridge to the whisper.cpp library.
 * Mirrors the NativeProxy pattern for the Go proxy bridge.
 */
object WhisperNative {

    private var loaded = false

    @Synchronized
    fun loadLibrary(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("whisper_jni")
            loaded = true
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    fun isLoaded(): Boolean = loaded

    external fun nativeInit(modelPath: String): Int
    external fun nativeTranscribe(samples: FloatArray, nThreads: Int, language: String, prompt: String?): String
    /** Returns transcription progress 0-100, updated by whisper's progress callback. */
    external fun nativeGetProgress(): Int
    external fun nativeAbort()
    external fun nativeFree()
    external fun nativeIsLoaded(): Boolean
    /** Language detected by the most recent transcription ("en", "de", ...), or "". */
    external fun nativeGetDetectedLanguage(): String
    external fun nativeVadInit(modelPath: String): Int
    /** Speech segments as [t0, t1, ...] in centiseconds, or null if VAD unavailable. */
    external fun nativeVadSegments(samples: FloatArray): FloatArray?
    external fun nativeVadFree()
}
