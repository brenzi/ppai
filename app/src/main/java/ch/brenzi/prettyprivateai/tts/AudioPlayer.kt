package ch.brenzi.prettyprivateai.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

class AudioPlayer {

    private val TAG = "AudioPlayer"
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var playing = false

    fun play(samples: FloatArray, sampleRate: Int) {
        stop()

        val shortSamples = ShortArray(samples.size) { i ->
            (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }

        val bufferSize = shortSamples.size * 2 // 2 bytes per short
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(shortSamples, 0, shortSamples.size)

        track.setNotificationMarkerPosition(shortSamples.size)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack?) {
                playing = false
                t?.release()
                if (audioTrack === t) audioTrack = null
            }
            override fun onPeriodicNotification(t: AudioTrack?) {}
        })

        audioTrack = track
        playing = true
        track.play()
        Log.i(TAG, "Playing ${samples.size} samples at ${sampleRate}Hz (${samples.size / sampleRate.toFloat()}s)")
    }

    fun stop() {
        playing = false
        audioTrack?.let { track ->
            try {
                track.stop()
                track.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping AudioTrack", e)
            }
        }
        audioTrack = null
    }

    fun isPlaying(): Boolean = playing
}
