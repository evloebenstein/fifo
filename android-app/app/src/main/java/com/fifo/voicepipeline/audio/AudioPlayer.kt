package com.fifo.voicepipeline.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Reproductor de audio PCM usando el parlante del celular.
 *
 * Recibe chunks de PCM 16-bit mono y los reproduce en streaming
 * vía AudioTrack. Diseñado para reproducir las respuestas TTS
 * en tiempo real, chunk por chunk.
 */
class AudioPlayer(
    private val sampleRate: Int = AudioConfig.SAMPLE_RATE
) {
    companion object {
        private const val TAG = "AudioPlayer"
    }

    private var audioTrack: AudioTrack? = null
    private var isPlaying = false

    /**
     * Inicializa el AudioTrack para reproducción streaming.
     */
    fun initialize() {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer * 4) // Buffer grande para evitar underruns
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        Log.i(TAG, "AudioTrack inicializado: ${sampleRate}Hz, mono, 16-bit, buffer=${minBuffer * 4}")
    }

    /**
     * Inicia la reproducción. Llamar antes del primer write().
     */
    fun start() {
        audioTrack?.let {
            if (it.state == AudioTrack.STATE_INITIALIZED) {
                it.play()
                isPlaying = true
                Log.d(TAG, "Reproducción iniciada")
            }
        }
    }

    /**
     * Escribe un chunk de audio PCM al parlante.
     * Bloquea hasta que el chunk se escribe al buffer.
     *
     * @param pcmData Bytes PCM 16-bit mono
     * @return Número de bytes escritos
     */
    fun write(pcmData: ByteArray): Int {
        if (!isPlaying) start()

        return audioTrack?.write(pcmData, 0, pcmData.size) ?: 0
    }

    /**
     * Detiene la reproducción y limpia el buffer.
     */
    fun stop() {
        audioTrack?.let {
            if (isPlaying) {
                it.stop()
                it.flush()
                isPlaying = false
                Log.d(TAG, "Reproducción detenida")
            }
        }
    }

    /**
     * Libera todos los recursos del AudioTrack.
     */
    fun release() {
        stop()
        audioTrack?.release()
        audioTrack = null
        Log.d(TAG, "AudioTrack liberado")
    }
}
