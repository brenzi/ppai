package ch.brenzi.prettyprivateai.whisper

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

sealed class WhisperModelState {
    data object NotDownloaded : WhisperModelState()
    data class Downloading(val progress: Float) : WhisperModelState()
    data object Ready : WhisperModelState()
    data class Error(val message: String) : WhisperModelState()
}

enum class WhisperModelSize(
    val label: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeMb: Int,
) {
    TINY(
        label = "Tiny",
        fileName = "ggml-tiny-q5_1.bin",
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin",
        sizeMb = 31,
    ),
    SMALL(
        label = "Small",
        fileName = "ggml-small-q5_1.bin",
        downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
        sizeMb = 105,
    );

    companion object {
        fun fromString(value: String): WhisperModelSize =
            entries.find { it.name == value } ?: SMALL
    }
}

class WhisperManager(private val context: Context) {

    private val TAG = "WhisperManager"
    private val MODEL_DIR = "whisper"

    /** Serializes initialize/download — prevents concurrent model loading and duplicate downloads. */
    private val modelMutex = Mutex()

    var modelSize: WhisperModelSize = WhisperModelSize.SMALL
        private set

    private val _modelState = MutableStateFlow<WhisperModelState>(WhisperModelState.NotDownloaded)
    val modelState: StateFlow<WhisperModelState> = _modelState.asStateFlow()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // per-read: only fires if zero bytes for 60s (stalled)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun modelDir(): File = File(context.filesDir, MODEL_DIR)
    private fun modelFile(): File = File(modelDir(), modelSize.fileName)

    fun setModelSize(size: WhisperModelSize) {
        if (size == modelSize) return
        if (WhisperNative.isLoaded()) WhisperNative.nativeFree()
        modelSize = size
        // Don't set Ready here — nativeFree was called, so initialize() must reload
        _modelState.value = WhisperModelState.NotDownloaded
    }

    suspend fun initialize() = withContext(Dispatchers.IO) {
        modelMutex.withLock {
            if (_modelState.value is WhisperModelState.Ready) return@withContext
            if (!WhisperNative.loadLibrary()) {
                _modelState.value = WhisperModelState.Error("Failed to load native library")
                return@withContext
            }
            val file = modelFile()
            if (file.exists()) {
                Log.i(TAG, "Loading model from ${file.absolutePath} (${file.length() / 1024 / 1024}MB)")
                val result = WhisperNative.nativeInit(file.absolutePath)
                _modelState.value = if (result == 0) {
                    Log.i(TAG, "Model loaded successfully")
                    WhisperModelState.Ready
                } else {
                    Log.e(TAG, "nativeInit returned $result")
                    WhisperModelState.Error("Failed to load model")
                }
            } else {
                _modelState.value = WhisperModelState.NotDownloaded
            }
        }
    }

    suspend fun downloadModel() = withContext(Dispatchers.IO) {
        modelMutex.withLock {
            val state = _modelState.value
            if (state is WhisperModelState.Downloading || state is WhisperModelState.Ready) return@withContext

            val dir = modelDir()
            if (!dir.exists()) dir.mkdirs()
            val file = modelFile()
            val tmpFile = File(dir, "${modelSize.fileName}.tmp")
            val maxRetries = 5

            for (attempt in 1..maxRetries) {
                try {
                    // Resume from partial download if tmp file exists
                    val existingBytes = if (tmpFile.exists()) tmpFile.length() else 0L
                    _modelState.value = WhisperModelState.Downloading(
                        if (existingBytes > 0) existingBytes.toFloat() / (modelSize.sizeMb * 1_048_576f) else 0f
                    )

                    val requestBuilder = Request.Builder().url(modelSize.downloadUrl)
                    if (existingBytes > 0) {
                        requestBuilder.header("Range", "bytes=$existingBytes-")
                        Log.i(TAG, "Resuming download from ${existingBytes / 1024}KB (attempt $attempt)")
                    }
                    val response = downloadClient.newCall(requestBuilder.build()).execute()

                    // 416 = range not satisfiable — file is already complete
                    if (response.code == 416) {
                        response.close()
                        tmpFile.renameTo(file)
                        break
                    }

                    if (!response.isSuccessful && response.code != 206) {
                        response.close()
                        _modelState.value = WhisperModelState.Error("Download failed: ${response.code}")
                        return@withContext
                    }

                    val body = response.body ?: run {
                        response.close()
                        _modelState.value = WhisperModelState.Error("Empty response")
                        return@withContext
                    }

                    // Total size: for resumed downloads, add existing bytes
                    val totalSize = if (response.code == 206) {
                        existingBytes + body.contentLength()
                    } else {
                        body.contentLength()
                    }
                    var bytesWritten = existingBytes

                    body.byteStream().use { input ->
                        // Append if resuming, truncate if fresh start
                        FileOutputStream(tmpFile, response.code == 206).use { output ->
                            val buffer = ByteArray(65536)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                bytesWritten += read
                                if (totalSize > 0) {
                                    _modelState.value = WhisperModelState.Downloading(
                                        bytesWritten.toFloat() / totalSize
                                    )
                                }
                            }
                        }
                    }

                    tmpFile.renameTo(file)
                    break // success
                } catch (e: Exception) {
                    Log.e(TAG, "Download attempt $attempt/$maxRetries failed", e)
                    if (attempt == maxRetries) {
                        _modelState.value = WhisperModelState.Error("Download interrupted — tap Retry")
                        return@withContext
                    }
                    // Exponential backoff: 10s, 20s, 40s, 80s — gives time for app to return to foreground
                    kotlinx.coroutines.delay(10_000L * (1L shl (attempt - 1)))
                }
            }

            if (!WhisperNative.isLoaded()) {
                WhisperNative.loadLibrary()
            }
            val result = WhisperNative.nativeInit(file.absolutePath)
            _modelState.value = if (result == 0) {
                WhisperModelState.Ready
            } else {
                WhisperModelState.Error("Failed to load model")
            }
        }
    }

    fun deleteModel() {
        if (WhisperNative.isLoaded()) WhisperNative.nativeFree()
        // Delete all model variants
        val dir = modelDir()
        WhisperModelSize.entries.forEach { size ->
            File(dir, size.fileName).delete()
        }
        _modelState.value = WhisperModelState.NotDownloaded
    }

    /** Abort a running transcription. Safe to call from any thread. */
    fun abortTranscription() {
        Log.i(TAG, "abortTranscription requested")
        WhisperNative.nativeAbort()
    }

    fun transcribe(samples: FloatArray, language: String = "auto"): String {
        // On ARM big.LITTLE SoCs (Pixel 6 Tensor G1, etc.) whisper.cpp creates
        // a disposable ggml threadpool for every graph compute.  With >2 threads,
        // the OS scheduler scatters them across big/LITTLE core clusters causing
        // cross-cluster spin-barrier stalls.  2 threads keeps them on one cluster.
        // On x86_64 emulators ggml's barrier deadlocks, so use 1 thread.
        val isEmulator = android.os.Build.HARDWARE.contains("ranchu") ||
            android.os.Build.HARDWARE.contains("goldfish") ||
            android.os.Build.FINGERPRINT.contains("generic")
        val threads = if (isEmulator) 1 else 4
        Log.i(TAG, "transcribe: ${samples.size} samples (${samples.size / 16000f}s), $threads threads, lang=$language (emulator=$isEmulator)")
        val start = System.currentTimeMillis()
        val result = WhisperNative.nativeTranscribe(samples, threads, language)
        Log.i(TAG, "transcribe done in ${System.currentTimeMillis() - start}ms: \"${result.take(100)}\"")
        return result
    }

    fun isReady(): Boolean = _modelState.value is WhisperModelState.Ready
}
