package com.fifo.voicepipeline.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * Sintetizador que hace hablar al robot en vez de al teléfono (ADR-0055).
 *
 * No hay streaming de texto a PCM en Android: el motor nativo sintetiza la
 * frase a un WAV, se decodifica, se baja a 8 kHz mono (16 kB/s, el presupuesto
 * que deja libre el micrófono a 16 kHz) y se envía por BLE al MAX98357A.
 *
 * Si el robot no está conectado, el adaptador no usa esta clase: la voz sale
 * por el parlante del teléfono como siempre.
 */
class RobotTtsSpeaker(
    context: Context,
    private val scope: CoroutineScope,
    private val route: RobotSpeakerRoute,
    private val onStart: () -> Unit = {},
    private val onDone: () -> Unit = {},
    private val onError: (String) -> Unit = {}
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "RobotTtsSpeaker"

        private const val CHUNK_SAMPLES = 256          // 32 ms por bloque
        private const val PACING_MS = 32L              // ritmo real del audio
        private const val MAX_WRITE_RETRIES = 4
        private const val RETRY_DELAY_MS = 8L
        private const val TAIL_DRAIN_MS = 260L         // colchón del ESP32
        private const val UTTERANCE_PREFIX = "fifo_robot_"
    }

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = TextToSpeech(appContext, this)
    private var isInitialized = false
    private var utteranceCounter = 0L
    private var streamingJob: Job? = null

    @Volatile
    var isSpeaking = false
        private set

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "Fallo al inicializar TextToSpeech para el robot (código $status)")
            onError("No se pudo inicializar el sintetizador de voz")
            return
        }

        val result = tts?.setLanguage(Locale("es", "ES"))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Español (ES) no disponible, usando el locale por defecto")
            tts?.setLanguage(Locale.getDefault())
        }
        tts?.setSpeechRate(1.05f)
        tts?.setPitch(1.0f)

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                if (utteranceId == null) return
                // La síntesis terminó: ahora se transmite el WAV por BLE.
                val file = File(appContext.cacheDir, "$utteranceId.wav")
                if (!file.exists()) {
                    finishWithError("El sintetizador no generó audio")
                    return
                }
                streamingJob = scope.launch { streamToRobot(file) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                finishWithError("Error en la síntesis de voz")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                finishWithError("Error en la síntesis de voz (código $errorCode)")
            }
        })

        isInitialized = true
        Log.i(TAG, "TextToSpeech del robot inicializado")
    }

    /**
     * Sintetiza y transmite la frase por el parlante del robot.
     */
    fun speak(text: String) {
        if (!isInitialized || tts == null) {
            onError("El sintetizador de voz no está listo")
            return
        }
        if (!route.isAvailable) {
            onError("El robot no está conectado por Bluetooth")
            return
        }

        val cleanText = TextSanitizer.cleanForSpeech(text)
        if (cleanText.isBlank()) return

        streamingJob?.cancel()
        streamingJob = null
        route.flush()
        isSpeaking = true

        val utteranceId = "$UTTERANCE_PREFIX${++utteranceCounter}"
        val file = File(appContext.cacheDir, "$utteranceId.wav")
        if (file.exists()) file.delete()

        val result = tts?.synthesizeToFile(cleanText, Bundle(), file, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            isSpeaking = false
            onError("No se pudo sintetizar la voz para el robot")
        }
    }

    /**
     * Detiene la locución en curso y descarta lo que quede en la cola del robot.
     */
    fun stop() {
        isSpeaking = false
        streamingJob?.cancel()
        streamingJob = null
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error en tts.stop(): ${e.message}")
        }
        route.flush()
    }

    /**
     * Libera el motor de síntesis.
     */
    fun release() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        Log.d(TAG, "TextToSpeech del robot liberado")
    }

    private suspend fun streamToRobot(file: File) {
        var started = false
        try {
            val pcm = RobotAudioFormat.wavToPcm8k(file.readBytes())
            if (pcm == null || pcm.isEmpty()) {
                finishWithError("El audio sintetizado no tiene un formato reproducible")
                return
            }

            route.flush()
            var index = 0
            while (index < pcm.size && isSpeaking && scope.isActive) {
                if (!started) {
                    started = true
                    onStart()
                }

                val end = minOf(index + CHUNK_SAMPLES, pcm.size)
                val chunk = RobotAudioFormat.toLittleEndian(pcm, index, end)
                var sent = false
                var attempt = 0
                while (!sent && attempt < MAX_WRITE_RETRIES && isSpeaking) {
                    sent = route.sendPcm(chunk)
                    if (!sent) {
                        attempt++
                        delay(RETRY_DELAY_MS)
                    }
                }
                index = end
                delay(PACING_MS)
            }

            // El robot guarda hasta 400 ms en cola: darle tiempo a sonar.
            if (isSpeaking) delay(TAIL_DRAIN_MS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Error transmitiendo audio al robot: ${e.message}")
            finishWithError("No se pudo enviar la voz al robot")
        } finally {
            file.delete()
            if (isSpeaking) {
                isSpeaking = false
                onDone()
            }
        }
    }

    private fun finishWithError(message: String) {
        isSpeaking = false
        onError(message)
    }
}
