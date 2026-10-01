package com.fifo.voicepipeline.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Reconocedor de voz nativo de Android (Google Speech Recognition).
 *
 * Ventajas:
 * 1. 100% GRATUITO y preinstalado en todos los dispositivos Android.
 * 2. CERO claves de API requeridas (no necesita OpenAI, Whisper ni Groq).
 * 3. Detección automática de inicio y fin de voz con soporte completo en español.
 * 4. Envía el texto transcrito directamente a Claude usando la clave de Anthropic.
 */
class NativeSpeechRecognizer(
    private val context: Context,
    private val onReady: () -> Unit = {},
    private val onRmsChanged: (Float) -> Unit = {},
    private val onPartialResult: (String) -> Unit = {},
    private val onResult: (String) -> Unit = {},
    private val onError: (Int, String) -> Unit = { _, _ -> }
) {
    companion object {
        private const val TAG = "NativeSpeechRecognizer"
    }

    private val callbackReady = onReady
    private val callbackRmsChanged = onRmsChanged
    private val callbackPartialResult = onPartialResult
    private val callbackResult = onResult
    private val callbackError = onError

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isListening = false
    private var shouldKeepListening = false

    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    fun startContinuousListening() {
        shouldKeepListening = true
        mainHandler.post {
            startInternal()
        }
    }

    fun stop() {
        shouldKeepListening = false
        mainHandler.post {
            try {
                isListening = false
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "Error deteniendo recognizer: ${e.message}")
            }
        }
    }

    fun release() {
        shouldKeepListening = false
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null
                isListening = false
            } catch (e: Exception) {
                Log.w(TAG, "Error liberando recognizer: ${e.message}")
            }
        }
    }

    private fun startInternal() {
        if (!shouldKeepListening) return

        try {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(createListener())
                }
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }

            speechRecognizer?.startListening(intent)
            isListening = true
            Log.d(TAG, "SpeechRecognizer iniciado escuchando...")
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando SpeechRecognizer: ${e.message}", e)
            restartAfterDelay(1500L)
        }
    }

    private fun restartAfterDelay(delayMs: Long) {
        if (!shouldKeepListening) return
        mainHandler.postDelayed({
            if (shouldKeepListening && !isListening) {
                startInternal()
            }
        }, delayMs)
    }

    private fun createListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                callbackReady()
            }

            override fun onBeginningOfSpeech() {
                // Usuario hablando
            }

            override fun onRmsChanged(rmsdB: Float) {
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                callbackRmsChanged(normalized)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                isListening = false
            }

            override fun onError(error: Int) {
                isListening = false
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "No se detectaron palabras"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Tiempo de espera agotado"
                    SpeechRecognizer.ERROR_AUDIO -> "Error de audio"
                    SpeechRecognizer.ERROR_SERVER -> "Error de servidor de Google"
                    SpeechRecognizer.ERROR_NETWORK -> "Error de red"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Tiempo de red agotado"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Reconocedor ocupado"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permiso de micrófono faltante"
                    else -> "Error de reconocimiento ($error)"
                }
                Log.d(TAG, "SpeechRecognizer onError: $errorMsg ($error)")
                callbackError(error, errorMsg)

                // Si está en modo continuo, reiniciar tras breve pausa
                if (shouldKeepListening) {
                    restartAfterDelay(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1200L else 600L)
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                Log.i(TAG, "Candidatos reconocidos por Google: $matches")

                // Si entre las interpretaciones acústicas aparece "fifo", "fifa", "fito", etc., priorizarla
                val wakeWords = listOf("fifo", "fifa", "fito", "feefo", "fido", "vivo", "filo", "fijo", "pipo", "kiko", "sifo", "fio")
                val bestMatch = matches.firstOrNull { candidate ->
                    val lower = candidate.lowercase()
                    wakeWords.any { lower.contains(it) }
                } ?: matches.firstOrNull() ?: ""

                if (bestMatch.isNotBlank()) {
                    callbackResult(bestMatch)
                }

                // Reiniciar escucha continua si corresponde
                if (shouldKeepListening) {
                    restartAfterDelay(350L)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                val wakeWords = listOf("fifo", "fifa", "fito", "feefo")
                val bestPartial = matches.firstOrNull { candidate ->
                    val lower = candidate.lowercase()
                    wakeWords.any { lower.contains(it) }
                } ?: matches.firstOrNull() ?: ""

                if (bestPartial.isNotBlank()) {
                    callbackPartialResult(bestPartial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }
}
