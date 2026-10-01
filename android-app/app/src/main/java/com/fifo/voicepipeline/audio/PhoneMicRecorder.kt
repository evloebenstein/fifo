package com.fifo.voicepipeline.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.*

/**
 * Grabador de audio usando el micrófono integrado del celular con supresión de ruido hardware.
 *
 * Funciona como fallback o alternativa de alta claridad para espacios concurridos.
 * Utiliza NoiseSuppressor y AcousticEchoCanceler por hardware si el dispositivo los soporta.
 * Captura audio PCM 16-bit mono a 16kHz y emite chunks del mismo tamaño
 * que los que enviaría el ESP32 (1024 bytes = 512 muestras = 32ms).
 */
class PhoneMicRecorder(
    private val sampleRate: Int = AudioConfig.SAMPLE_RATE,
    private val chunkBytes: Int = AudioConfig.CHUNK_BYTES
) {
    companion object {
        private const val TAG = "PhoneMicRecorder"
    }

    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var gainControl: AutomaticGainControl? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    var isRecording: Boolean = false
        private set

    /**
     * Inicia la captura continua de audio desde el micrófono del celular.
     * Requiere el permiso android.permission.RECORD_AUDIO.
     *
     * @param onChunk Callback invocado con cada buffer PCM capturado.
     */
    @SuppressLint("MissingPermission")
    fun start(onChunk: (ByteArray) -> Unit): Boolean {
        if (isRecording) {
            Log.w(TAG, "La grabación ya está en curso")
            return true
        }

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val bufferSize = maxOf(minBufferSize, chunkBytes * 4)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "No se pudo inicializar AudioRecord")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            val sessionId = audioRecord?.audioSessionId ?: 0
            if (sessionId != 0) {
                if (NoiseSuppressor.isAvailable()) {
                    try {
                        noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                        Log.i(TAG, "Hardware NoiseSuppressor activado para suprimir ruido de fondo")
                    } catch (e: Exception) {
                        Log.w(TAG, "No se pudo activar NoiseSuppressor: ${e.message}")
                    }
                }
                if (AcousticEchoCanceler.isAvailable()) {
                    try {
                        echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                        Log.i(TAG, "Hardware AcousticEchoCanceler activado")
                    } catch (e: Exception) {
                        Log.w(TAG, "No se pudo activar AcousticEchoCanceler: ${e.message}")
                    }
                }
                if (AutomaticGainControl.isAvailable()) {
                    try {
                        gainControl = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
                        Log.i(TAG, "Hardware AutomaticGainControl activado")
                    } catch (e: Exception) {
                        Log.w(TAG, "No se pudo activar AGC: ${e.message}")
                    }
                }
            }

            audioRecord?.startRecording()
            isRecording = true
            Log.i(TAG, "Grabación desde micrófono del celular iniciada con cancelación de ruido")

            recordingJob = scope.launch {
                val buffer = ByteArray(chunkBytes)
                while (isActive && isRecording) {
                    var bytesRead = 0
                    while (bytesRead < chunkBytes && isActive && isRecording) {
                        val read = audioRecord?.read(buffer, bytesRead, chunkBytes - bytesRead) ?: -1
                        if (read > 0) {
                            bytesRead += read
                        } else if (read < 0) {
                            Log.e(TAG, "Error leyendo AudioRecord: $read")
                            break
                        }
                    }

                    if (bytesRead == chunkBytes) {
                        onChunk(buffer.copyOf())
                    }
                }
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando grabación: ${e.message}", e)
            stop()
            return false
        }
    }

    /**
     * Detiene la captura de audio y libera el AudioRecord y los efectos de hardware.
     */
    fun stop() {
        if (!isRecording && audioRecord == null) return

        isRecording = false
        recordingJob?.cancel()
        recordingJob = null

        try {
            noiseSuppressor?.apply {
                enabled = false
                release()
            }
            noiseSuppressor = null

            echoCanceler?.apply {
                enabled = false
                release()
            }
            echoCanceler = null

            gainControl?.apply {
                enabled = false
                release()
            }
            gainControl = null

            audioRecord?.apply {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al detener AudioRecord: ${e.message}", e)
        } finally {
            audioRecord = null
            Log.i(TAG, "Grabación desde micrófono del celular detenida")
        }
    }

    /**
     * Libera completamente los recursos y scopes.
     */
    fun release() {
        stop()
        scope.cancel()
    }
}
