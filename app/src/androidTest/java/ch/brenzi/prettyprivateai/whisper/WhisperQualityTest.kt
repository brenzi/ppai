package ch.brenzi.prettyprivateai.whisper

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URL
import java.util.Locale

/**
 * Instrumented quality test for the whisper transcription pipeline.
 *
 * Exercises the actual app code path:
 *   AudioDecoder.decodeChunked() → 10 s chunks → WhisperNative.nativeTranscribe()
 * with accumulated-prompt context passing between chunks.
 *
 * Downloads a tiny whisper model (~31 MB) and two LibriVox recordings (public domain)
 * on first run; cached in the app's filesDir for subsequent runs.
 *
 * Run:
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=ch.brenzi.prettyprivateai.whisper.WhisperQualityTest
 */
@RunWith(AndroidJUnit4::class)
class WhisperQualityTest {

    companion object {
        private const val TAG = "WhisperQualityTest"

        private const val MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin"
        private const val EN_AUDIO_URL =
            "https://archive.org/download/short_poetry_001_librivox/road_not_taken_frost_ac.mp3"
        private const val DE_AUDIO_URL =
            "https://archive.org/download/grimm_maerchen_1_librivox/grimm_132_sterntaler.mp3"

        // Minimum word-recall thresholds (%). Conservative for tiny-q5_1 on emulator.
        private const val THRESH_EN = 65
        private const val THRESH_DE = 50

        // Reference texts — pre-normalized: lowercase, no punctuation, single-spaced.
        // English: Robert Frost — "The Road Not Taken" (~1:54)
        private const val REF_EN =
            "two roads diverged in a yellow wood and sorry i could not travel both and be one traveler long i stood and looked down one as far as i could to where it bent in the undergrowth then took the other as just as fair and having perhaps the better claim because it was grassy and wanted wear though as for that the passing there had worn them really about the same and both that morning equally lay in leaves no step had trodden black oh i kept the first for another day yet knowing how way leads on to way i doubted if i should ever come back i shall be telling this with a sigh somewhere ages and ages hence two roads diverged in a wood and i i took the one less traveled by and that has made all the difference"

        // German: Brothers Grimm — "Die Sterntaler" (~2:26)
        private const val REF_DE =
            "es war einmal ein kleines mädchen dem war vater und mutter gestorben und es war so arm dass es kein kämmerchen mehr hatte darin zu wohnen und kein bettchen mehr hatte darin zu schlafen und endlich gar nichts mehr als die kleider auf dem leib und ein stückchen brot in der hand dass ihm ein mitleidiges herz geschenkt hatte es war aber gut und fromm und weil es so von aller welt verlassen war ging es im vertrauen auf den lieben gott hinaus ins feld da begegnete ihm ein armer mann der sprach ach gib mir etwas zu essen ich bin so hungrig es reichte ihm das ganze stückchen brot und sagte gott segne dirs und ging weiter da kam ein kind das jammerte und sprach es friert mich so an meinem kopfe schenk mir etwas womit ich ihn bedecken kann da tat es seine mütze ab und gab sie ihm und als es noch eine weile gegangen war kam wieder ein kind und hatte kein leibchen an und fror da gab es ihm seins und noch weiter da bat eins um ein röcklein das gab es auch von sich hin endlich gelangte es in einen wald und es war schon dunkel geworden da kam noch eins und bat um ein hemdlein und das fromme mädchen dachte es ist dunkle nacht da sieht dich niemand du kannst wohl dein hemd weggeben und zog das hemd ab und gab es auch noch hin und wie es so stand und gar nichts mehr hatte fielen auf einmal die sterne vom himmel und waren lauter blanke taler und ob es gleich sein hemdlein weggegeben so hatte es ein neues an und das war vom allerfeinsten linnen da sammelte es sich die taler hinein und war reich für sein lebtag"

        private lateinit var dataDir: File
        private lateinit var enAudioFile: File
        private lateinit var deAudioFile: File

        @JvmStatic
        @BeforeClass
        fun setup() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            dataDir = File(context.filesDir, "whisper-test")
            dataDir.mkdirs()

            val modelFile = download(MODEL_URL, "ggml-tiny-q5_1.bin")
            enAudioFile = download(EN_AUDIO_URL, "test_en.mp3")
            deAudioFile = download(DE_AUDIO_URL, "test_de.mp3")

            assertTrue("Failed to load whisper_jni", WhisperNative.loadLibrary())
            val rc = WhisperNative.nativeInit(modelFile.absolutePath)
            assertTrue("nativeInit failed (rc=$rc)", rc == 0)
        }

        @JvmStatic
        @AfterClass
        fun teardown() {
            if (WhisperNative.isLoaded()) WhisperNative.nativeFree()
        }

        private fun download(url: String, filename: String): File {
            val file = File(dataDir, filename)
            if (file.exists()) return file
            Log.i(TAG, "Downloading $filename...")
            val tmp = File(dataDir, "$filename.tmp")
            URL(url).openStream().use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            tmp.renameTo(file)
            Log.i(TAG, "Downloaded $filename (${file.length() / 1024} KB)")
            return file
        }
    }

    @Test
    fun englishChunkedTranscriptionQuality() {
        val result = transcribeChunked(enAudioFile, "en")
        val recall = wordRecall(REF_EN, result)
        Log.i(TAG, "[en] recall=${"%.1f".format(recall)}% threshold=$THRESH_EN%")
        Log.i(TAG, "[en] transcript: $result")
        assertTrue(
            "English recall ${"%.1f".format(recall)}% < $THRESH_EN%",
            recall >= THRESH_EN,
        )
    }

    @Test
    fun germanChunkedTranscriptionQuality() {
        val result = transcribeChunked(deAudioFile, "de")
        val recall = wordRecall(REF_DE, result)
        Log.i(TAG, "[de] recall=${"%.1f".format(recall)}% threshold=$THRESH_DE%")
        Log.i(TAG, "[de] transcript: $result")
        assertTrue(
            "German recall ${"%.1f".format(recall)}% < $THRESH_DE%",
            recall >= THRESH_DE,
        )
    }

    /** Decode audio → 10 s chunks → transcribe with prompt, mirroring the app pipeline. */
    private fun transcribeChunked(audioFile: File, language: String): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = Uri.fromFile(audioFile)
        val accumulated = StringBuilder()

        AudioDecoder.decodeChunked(context, uri) { samples, chunkIndex, totalChunks ->
            val prompt = accumulated.toString().ifEmpty { null }
            Log.i(TAG, "[$language] chunk $chunkIndex/$totalChunks: ${samples.size} samples, prompt=${prompt?.length ?: 0} chars")

            // 1 thread on emulator (matches WhisperManager's emulator detection)
            val text = WhisperNative.nativeTranscribe(samples, 1, language, prompt).trim()
            if (text.isNotEmpty()) {
                if (accumulated.isNotEmpty()) accumulated.append(" ")
                accumulated.append(text)
            }
        }

        return accumulated.toString()
    }

    /** Bag-of-words recall: fraction of reference words found in hypothesis. */
    private fun wordRecall(reference: String, hypothesis: String): Double {
        val refWords = normalize(reference)
        val hypWords = normalize(hypothesis)
        val refCounts = refWords.groupingBy { it }.eachCount()
        val hypCounts = hypWords.groupingBy { it }.eachCount()
        var hits = 0
        for ((word, count) in hypCounts) {
            hits += minOf(count, refCounts[word] ?: 0)
        }
        return if (refWords.isEmpty()) 0.0 else hits.toDouble() / refWords.size * 100
    }

    private fun normalize(text: String): List<String> {
        return text.lowercase(Locale.ROOT)
            .replace(Regex("[^a-zäöüßàáâèéêìíîòóôùúûñ0-9 ]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
    }
}
