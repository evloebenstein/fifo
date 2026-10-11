package com.fifo.voicepipeline.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
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
    private val context: android.content.Context? = null,
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
    private var lastOnChunkCallback: ((ByteArray) -> Unit)? = null

    @Volatile
    var isRecording: Boolean = false
        private set

    /**
     * Reconfigura la fuente de grabación para modo llamada (VOICE_COMMUNICATION con AEC de hardware)
     * o modo normal, manteniendo la captura activa si ya estaba grabando.
     */
    fun switchToCallMode(inCall: Boolean, onChunk: ((ByteArray) -> Unit)? = null) {
        val callback = onChunk ?: lastOnChunkCallback
        if (callback == null) return
        Log.i(TAG, "Reconfigurando captura de audio de celular para inCall=$inCall...")
        stop()
        start(forceCallSource = inCall, onChunk = callback)
    }

    fun start(onChunk: (ByteArray) -> Unit): Boolean = start(forceCallSource = null, onChunk = onChunk)

    /**
     * Inicia la captura continua de audio desde el micrófono del celular.
     * Requiere el permiso android.permission.RECORD_AUDIO.
     *
     * @param forceCallSource Si es true, prioriza VOICE_COMMUNICATION para llamadas activas/video llamadas.
     * @param onChunk Callback invocado con cada buffer PCM capturado.
     */
    @SuppressLint("MissingPermission")
    fun start(forceCallSource: Boolean? = null, onChunk: (ByteArray) -> Unit): Boolean {
        lastOnChunkCallback = onChunk
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

            val audioManager = context?.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager
            val inCallMode = forceCallSource ?: (
                audioManager?.mode == AudioManager.MODE_IN_CALL ||
                audioManager?.mode == AudioManager.MODE_IN_COMMUNICATION ||
                com.fifo.voicepipeline.data.FifoDataRepository.incomingCall.value != null
            )

            var targetDevice: AudioDeviceInfo? = null
            if (inCallMode) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val commDevices = audioManager?.availableCommunicationDevices ?: emptyList()
                    targetDevice = commDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                    }
                    // Solo enrutar globalmente si Fifo maneja directamente una llamada celular SIM
                    if (com.fifo.voicepipeline.data.FifoDataRepository.incomingCall.value != null && targetDevice != null) {
                        val setOk = audioManager?.setCommunicationDevice(targetDevice)
                        Log.i(TAG, "Audio de llamada celular enrutado a ${targetDevice.productName} (éxito: $setOk)")
                    }
                } else if (com.fifo.voicepipeline.data.FifoDataRepository.incomingCall.value != null) {
                    try {
                        audioManager?.startBluetoothSco()
                        audioManager?.isBluetoothScoOn = true
                        Log.i(TAG, "Bluetooth SCO iniciado para llamada celular")
                    } catch (e: Exception) {
                        Log.w(TAG, "No se pudo iniciar Bluetooth SCO legacy: ${e.message}")
                    }
                }
            }

            // Cuando una llamada telefónica o VoIP está activa en Android,
            // VOICE_COMMUNICATION es silenciado exclusivamente por AudioPolicy para apps secundarias (entregando buffers de ceros).
            // MediaRecorder.AudioSource.VOICE_RECOGNITION y MIC son las fuentes designadas por Android para captura concurrente.
            val audioSources = if (inCallMode) {
                Log.i(TAG, "Modo llamada detectado: priorizando MediaRecorder.AudioSource.VOICE_RECOGNITION para captura concurrente")
                listOf(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.DEFAULT,
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    MediaRecorder.AudioSource.UNPROCESSED
                )
            } else {
                listOf(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.DEFAULT,
                    MediaRecorder.AudioSource.CAMCORDER,
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION
                )
            }

            var initializedRecord: AudioRecord? = null
            for (source in audioSources) {
                try {
                    val record = AudioRecord(
                        source,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize
                    )
                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && targetDevice != null) {
                            try {
                                val prefOk = record.setPreferredDevice(targetDevice)
                                Log.i(TAG, "Dispositivo de entrada preferido configurado: ${targetDevice.productName} (éxito=$prefOk)")
                            } catch (e: Exception) {
                                Log.w(TAG, "No se pudo configurar preferredDevice: ${e.message}")
                            }
                        }
                        initializedRecord = record
                        Log.i(TAG, "AudioRecord inicializado exitosamente con fuente de audio: $source (inCallMode=$inCallMode)")
                        break
                    } else {
                        record.release()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo inicializar AudioRecord con fuente $source: ${e.message}")
                }
            }

            if (initializedRecord == null) {
                Log.e(TAG, "No se pudo inicializar AudioRecord con ninguna fuente de audio")
                return false
            }

            audioRecord = initializedRecord

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
                var chunkCount = 0
                while (isActive && isRecording) {
                    var bytesRead = 0
                    while (bytesRead < chunkBytes && isActive && isRecording) {
                        val read = audioRecord?.read(buffer, bytesRead, chunkBytes - bytesRead) ?: -1
                        if (read > 0) {
                            bytesRead += read
                        } else if (read < 0) {
                            Log.e(TAG, "Error leyendo AudioRecord: $read")
                            delay(50)
                            break
                        } else {
                            delay(10)
                        }
                    }

                    if (bytesRead == chunkBytes) {
                        chunkCount++
                        if (chunkCount % 25 == 0) {
                            var sum = 0.0
                            for (i in 0 until chunkBytes step 2) {
                                val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                                val shortSample = sample.toShort()
                                sum += shortSample * shortSample
                            }
                            val rms = Math.sqrt(sum / (chunkBytes / 2))
                            Log.d(TAG, "Audio capturado celular [chunk $chunkCount]: RMS=${String.format("%.1f", rms)}, inCallMode=$inCallMode, src=${audioRecord?.audioSource}")
                        }
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
                try {
                    if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        stop()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "AudioRecord ya detenido o inactivo: ${e.message}")
                }
                try {
                    release()
                } catch (e: Exception) {
                    Log.w(TAG, "AudioRecord release error: ${e.message}")
                }
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
