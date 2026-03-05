package ch.brenzi.prettyprivateai.tts

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
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

sealed class TtsModelState {
    data object NotDownloaded : TtsModelState()
    data class Downloading(val progress: Float) : TtsModelState()
    data object Ready : TtsModelState()
    data class Error(val message: String) : TtsModelState()
}

enum class TtsVoice(
    val label: String,
    val modelName: String,
    val onnxFileName: String,
    val sizeMb: Int,
) {
    AMY(
        label = "Amy (EN)",
        modelName = "vits-piper-en_US-amy-low",
        onnxFileName = "en_US-amy-low.onnx",
        sizeMb = 30,
    ),
    LESSAC(
        label = "Lessac (EN)",
        modelName = "vits-piper-en_US-lessac-medium",
        onnxFileName = "en_US-lessac-medium.onnx",
        sizeMb = 60,
    ),
    THORSTEN(
        label = "Thorsten (DE)",
        modelName = "vits-piper-de_DE-thorsten-medium",
        onnxFileName = "de_DE-thorsten-medium.onnx",
        sizeMb = 60,
    ),
    KERSTIN(
        label = "Kerstin (DE)",
        modelName = "vits-piper-de_DE-kerstin-low",
        onnxFileName = "de_DE-kerstin-low.onnx",
        sizeMb = 63,
    );

    companion object {
        fun fromString(value: String): TtsVoice =
            entries.find { it.name == value } ?: AMY
    }
}

class TtsManager(private val context: Context) {

    private val TAG = "TtsManager"
    private val TTS_DIR = "tts"
    private val ESPEAK_DIR = "sherpa-onnx-espeak-ng-data"

    private val modelMutex = Mutex()

    var voice: TtsVoice = TtsVoice.AMY
        private set

    private var tts: OfflineTts? = null

    private val _modelState = MutableStateFlow<TtsModelState>(TtsModelState.NotDownloaded)
    val modelState: StateFlow<TtsModelState> = _modelState.asStateFlow()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun voiceDir(): File = File(File(context.filesDir, TTS_DIR), voice.modelName)
    private fun modelFile(): File = File(voiceDir(), "model.onnx")
    private fun tokensFile(): File = File(voiceDir(), "tokens.txt")
    private fun espeakDataDir(): File = File(context.filesDir, ESPEAK_DIR)

    fun setVoice(newVoice: TtsVoice) {
        if (newVoice == voice && tts != null) return
        tts?.release()
        tts = null
        voice = newVoice
        _modelState.value = if (modelFile().exists() && tokensFile().exists()) {
            TtsModelState.NotDownloaded // Files exist but engine not loaded — initialize() will fix
        } else {
            TtsModelState.NotDownloaded
        }
    }

    suspend fun initialize() = withContext(Dispatchers.IO) {
        modelMutex.withLock {
            if (_modelState.value is TtsModelState.Ready) return@withContext

            Log.i(TAG, "initialize() voice=${voice.modelName}")
            extractEspeakData()

            val modelExists = modelFile().exists()
            val tokensExists = tokensFile().exists()
            val espeakReady = espeakDataReady()
            Log.i(TAG, "Files: model=$modelExists (${modelFile().absolutePath}), " +
                    "tokens=$tokensExists, espeakReady=$espeakReady")

            if (!modelExists || !tokensExists || !espeakReady) {
                Log.i(TAG, "Model files missing, staying NotDownloaded")
                _modelState.value = TtsModelState.NotDownloaded
                return@withContext
            }

            try {
                Log.i(TAG, "Creating OfflineTts config...")
                val vitsConfig = OfflineTtsVitsModelConfig.builder()
                    .setModel(modelFile().absolutePath)
                    .setTokens(tokensFile().absolutePath)
                    .setDataDir(espeakDataDir().absolutePath)
                    .build()

                val modelConfig = OfflineTtsModelConfig.builder()
                    .setVits(vitsConfig)
                    .setNumThreads(2)
                    .setDebug(true)
                    .setProvider("cpu")
                    .build()

                val config = OfflineTtsConfig.builder()
                    .setModel(modelConfig)
                    .build()

                Log.i(TAG, "Creating OfflineTts instance...")
                tts = OfflineTts(config)
                _modelState.value = TtsModelState.Ready
                Log.i(TAG, "TTS engine initialized: ${voice.modelName}, " +
                        "sampleRate=${tts?.sampleRate}, speakers=${tts?.numSpeakers}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize TTS engine", e)
                _modelState.value = TtsModelState.Error("Failed to load TTS model: ${e.message}")
            }
        }
    }

    suspend fun downloadVoice() = withContext(Dispatchers.IO) {
        modelMutex.withLock {
            val state = _modelState.value
            if (state is TtsModelState.Downloading || state is TtsModelState.Ready) return@withContext

            val dir = voiceDir()
            if (!dir.exists()) dir.mkdirs()

            val totalSize = voice.sizeMb * 1_048_576L
            var totalDownloaded = 0L

            val baseUrl = "https://huggingface.co/csukuangfj/${voice.modelName}/resolve/main"
            val files = listOf(
                voice.onnxFileName to modelFile(),
                "tokens.txt" to tokensFile(),
            )

            val maxRetries = 5

            for ((remoteName, localFile) in files) {
                val url = "$baseUrl/$remoteName"
                val tmpFile = File(dir, "${localFile.name}.tmp")

                for (attempt in 1..maxRetries) {
                    try {
                        val existingBytes = if (tmpFile.exists()) tmpFile.length() else 0L
                        _modelState.value = TtsModelState.Downloading(
                            (totalDownloaded + existingBytes).toFloat() / totalSize
                        )

                        val requestBuilder = Request.Builder().url(url)
                        if (existingBytes > 0) {
                            requestBuilder.header("Range", "bytes=$existingBytes-")
                            Log.i(TAG, "Resuming $remoteName from ${existingBytes / 1024}KB (attempt $attempt)")
                        }
                        val response = downloadClient.newCall(requestBuilder.build()).execute()

                        if (response.code == 416) {
                            response.close()
                            tmpFile.renameTo(localFile)
                            break
                        }

                        if (!response.isSuccessful && response.code != 206) {
                            response.close()
                            _modelState.value = TtsModelState.Error("Download failed: ${response.code}")
                            return@withContext
                        }

                        val body = response.body ?: run {
                            response.close()
                            _modelState.value = TtsModelState.Error("Empty response")
                            return@withContext
                        }

                        var bytesWritten = existingBytes
                        body.byteStream().use { input ->
                            FileOutputStream(tmpFile, response.code == 206).use { output ->
                                val buffer = ByteArray(65536)
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    output.write(buffer, 0, read)
                                    bytesWritten += read
                                    _modelState.value = TtsModelState.Downloading(
                                        (totalDownloaded + bytesWritten).toFloat() / totalSize
                                    )
                                }
                            }
                        }

                        totalDownloaded += bytesWritten
                        tmpFile.renameTo(localFile)
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Download attempt $attempt/$maxRetries failed for $remoteName", e)
                        if (attempt == maxRetries) {
                            _modelState.value = TtsModelState.Error("Download interrupted — tap Retry")
                            return@withContext
                        }
                        kotlinx.coroutines.delay(10_000L * (1L shl (attempt - 1)))
                    }
                }
            }
        }

        // Initialize after download (outside mutex — initialize() acquires it)
        initialize()
    }

    fun synthesize(text: String, speakerId: Int = 0, speed: Float = 1.0f): GeneratedAudio? {
        val engine = tts ?: run {
            Log.w(TAG, "synthesize() called but engine is null")
            return null
        }
        Log.i(TAG, "synthesize(): ${text.length} chars, sid=$speakerId, speed=$speed")
        val audio = engine.generate(text, speakerId, speed)
        Log.i(TAG, "synthesize() done: ${audio.samples.size} samples, rate=${audio.sampleRate}")
        return audio
    }

    fun deleteVoice() {
        tts?.release()
        tts = null
        val dir = voiceDir()
        TtsVoice.entries.forEach { v ->
            val vDir = File(File(context.filesDir, TTS_DIR), v.modelName)
            vDir.deleteRecursively()
        }
        _modelState.value = TtsModelState.NotDownloaded
    }

    fun isReady(): Boolean = _modelState.value is TtsModelState.Ready

    fun getSampleRate(): Int = tts?.sampleRate ?: 22050

    /** espeak-ng-data is ready when phontab exists (the key compiled binary file). */
    private fun espeakDataReady(): Boolean =
        File(espeakDataDir(), "phontab").exists()

    /** Extract espeak-ng-data from APK assets to filesDir (one-time). */
    private fun extractEspeakData() {
        if (espeakDataReady()) {
            Log.i(TAG, "espeak-ng-data already extracted at ${espeakDataDir().absolutePath}")
            return
        }

        Log.i(TAG, "Extracting espeak-ng-data from assets...")
        try {
            val assetEntries = context.assets.list(ESPEAK_DIR) ?: emptyArray()
            Log.i(TAG, "Asset entries in $ESPEAK_DIR: ${assetEntries.size} (${assetEntries.take(5).joinToString()})")
            val dest = espeakDataDir()
            copyAssetDir(context.assets, ESPEAK_DIR, dest)
            val extracted = dest.list() ?: emptyArray()
            Log.i(TAG, "espeak-ng-data extracted: ${extracted.size} entries, " +
                    "phontab=${File(dest, "phontab").exists()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract espeak-ng-data", e)
        }
    }

    private fun copyAssetDir(assets: AssetManager, srcPath: String, destDir: File) {
        val entries = assets.list(srcPath) ?: return
        if (entries.isEmpty()) {
            // It's a file
            assets.open(srcPath).use { input ->
                destDir.parentFile?.mkdirs()
                FileOutputStream(destDir).use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            // It's a directory
            destDir.mkdirs()
            for (entry in entries) {
                copyAssetDir(assets, "$srcPath/$entry", File(destDir, entry))
            }
        }
    }
}
