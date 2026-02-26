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
    external fun nativeTranscribe(samples: FloatArray, nThreads: Int, language: String): String
    /** Returns transcription progress 0-100, updated by whisper's progress callback. */
    external fun nativeGetProgress(): Int
    external fun nativeAbort()
    external fun nativeFree()
    external fun nativeIsLoaded(): Boolean
}
