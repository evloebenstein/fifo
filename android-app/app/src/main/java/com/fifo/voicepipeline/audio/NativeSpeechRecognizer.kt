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
    private val onError: (Int, String) -> Unit = { _, _ -> },
    private val onRepeatedFailure: (() -> Unit)? = null
) {
    companion object {
        private const val TAG = "NativeSpeechRecognizer"
    }

    private val callbackReady = onReady
    private val callbackRmsChanged = onRmsChanged
    private val callbackPartialResult = onPartialResult
    private val callbackResult = onResult
    private val callbackError = onError
    private val callbackRepeatedFailure = onRepeatedFailure

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isListening = false
    private var shouldKeepListening = false
    private var currentGeneration = 0
    private var consecutiveErrors = 0

    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    fun startContinuousListening() {
        shouldKeepListening = true
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post {
            startInternal()
        }
    }

    fun stop() {
        shouldKeepListening = false
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post {
            try {
                isListening = false
                speechRecognizer?.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "Error cancelando recognizer: ${e.message}")
            }
        }
    }

    fun release() {
        shouldKeepListening = false
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post {
            try {
                isListening = false
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (e: Exception) {
                Log.w(TAG, "Error liberando recognizer: ${e.message}")
            }
        }
    }

    private fun buildRecognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            val defaultLocale = java.util.Locale.getDefault()
            val isSpanish = defaultLocale.language.equals("es", ignoreCase = true)
            val langTag = if (isSpanish) defaultLocale.toLanguageTag() else "es-419"
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, langTag)
            // Permitir español y también inglés de respaldo para evitar error 12 en emuladores o entornos sin pack descargado
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("es-419", "es-ES", "es-US", "es-CL", "es", "en-US"))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
    }

    private fun startInternal() {
        if (!shouldKeepListening) return

        if (!isAvailable()) {
            Log.w(TAG, "SpeechRecognizer no está disponible en este dispositivo/emulador")
            callbackRepeatedFailure?.invoke()
            return
        }

        try {
            if (speechRecognizer == null) {
                val generation = ++currentGeneration
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(createListener(generation))
                }
            }

            speechRecognizer?.startListening(buildRecognizerIntent())
            isListening = true
            Log.d(TAG, "SpeechRecognizer startListening llamado...")
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando SpeechRecognizer: ${e.message}", e)
            consecutiveErrors++
            if (consecutiveErrors >= 3) {
                callbackRepeatedFailure?.invoke()
            }
            destroyAndRecreateAfterDelay(1500L)
        }
    }

    private fun restartListening(delayMs: Long = 400L) {
        if (!shouldKeepListening) return
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (shouldKeepListening && !isListening) {
                try {
                    if (speechRecognizer == null) {
                        startInternal()
                    } else {
                        speechRecognizer?.startListening(buildRecognizerIntent())
                        isListening = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error reiniciando recognizer existente: ${e.message}, recreando...")
                    consecutiveErrors++
                    if (consecutiveErrors >= 3) {
                        callbackRepeatedFailure?.invoke()
                    }
                    destroyAndRecreateAfterDelay(1200L)
                }
            }
        }, delayMs)
    }

    private fun destroyAndRecreateAfterDelay(delayMs: Long) {
        if (!shouldKeepListening) return
        mainHandler.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
        isListening = false

        mainHandler.postDelayed({
            if (shouldKeepListening && !isListening) {
                startInternal()
            }
        }, delayMs)
    }

    private fun createListener(generation: Int): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (generation != currentGeneration) return
                isListening = true
                consecutiveErrors = 0
                callbackReady()
            }

            override fun onBeginningOfSpeech() {
                if (generation != currentGeneration) return
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (generation != currentGeneration) return
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                callbackRmsChanged(normalized)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                if (generation != currentGeneration) return
                isListening = false
            }

            override fun onError(error: Int) {
                if (generation != currentGeneration) return
                isListening = false
                consecutiveErrors++

                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "No se detectaron palabras"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Tiempo de espera agotado"
                    SpeechRecognizer.ERROR_AUDIO -> "Error de audio (micrófono ocupado)"
                    SpeechRecognizer.ERROR_SERVER -> "Error de servidor de Google"
                    SpeechRecognizer.ERROR_NETWORK -> "Error de red"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Tiempo de red agotado"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Reconocedor ocupado"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permiso de micrófono faltante"
                    SpeechRecognizer.ERROR_CLIENT -> "Error de cliente"
                    13 -> "Idioma no disponible"
                    else -> "Error de reconocimiento ($error)"
                }
                Log.d(TAG, "SpeechRecognizer onError: $errorMsg ($error) [Consecutivos: $consecutiveErrors]")
                callbackError(error, errorMsg)

                if (!shouldKeepListening) return

                // Si se acumulan 2 o más errores consecutivos (ej. silencio de usuario o timeout en reposo):
                // DETENERSE para no producir el molesto sonido repetitivo de Google ("prende y apaga")
                if (consecutiveErrors >= 2) {
                    Log.i(TAG, "Pausando SpeechRecognizer por silencio prolongado ($consecutiveErrors). Deteniendo bucle de sonidos.")
                    shouldKeepListening = false
                    try {
                        speechRecognizer?.cancel()
                    } catch (_: Exception) {}
                    callbackRepeatedFailure?.invoke()
                    return
                }

                when (error) {
                    // Silencio normal o timeout: pausar suavemente una única vez
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        restartListening(1000L)
                    }
                    // Idioma no descargado sin conexión: reintentar con delay
                    13 -> {
                        restartListening(1500L)
                    }
                    // Error de audio durante llamadas o bloqueo de mic por otra app:
                    SpeechRecognizer.ERROR_AUDIO -> {
                        shouldKeepListening = false
                        callbackRepeatedFailure?.invoke()
                    }
                    // Errores recuperables con pausa breve
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER -> {
                        restartListening(2000L)
                    }
                    // Errores de cliente o busy: destruir y recrear limpiamente con delay
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    SpeechRecognizer.ERROR_CLIENT -> {
                        destroyAndRecreateAfterDelay(1500L)
                    }
                    else -> {
                        restartListening(1000L)
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                if (generation != currentGeneration) return
                isListening = false
                consecutiveErrors = 0
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                Log.i(TAG, "Candidatos reconocidos por Google: $matches")

                val wakeWords = listOf(
                    "fifo", "fio", "fío", "fifa", "fito", "feefo", "fido", "vivo", "filo", "fijo", "pipo", "kiko", "sifo",
                    "fee for", "fit for", "people", "free for", "feed for", "bebo", "feefa", "fefa", "phifo", "vibo"
                )

                val bestMatch = matches.firstOrNull { candidate ->
                    val lower = candidate.lowercase()
                    wakeWords.any { lower.contains(it) }
                } ?: matches.firstOrNull() ?: ""

                if (bestMatch.isNotBlank()) {
                    callbackResult(bestMatch)
                }

                if (shouldKeepListening) {
                    restartListening(100L)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (generation != currentGeneration) return
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                val wakeWords = listOf(
                    "fifo", "fifa", "fito", "feefo", "fido", "vivo", "filo", "fijo", "pipo",
                    "fee for", "fit for", "feefa", "fefa", "phifo"
                )
                val callKeywords = listOf(
                    "contesta", "cuelga", "rechaza", "atiende", "corta", "colgar", "contestar", "finaliza", "acepta"
                )

                val bestPartial = matches.firstOrNull { candidate ->
                    val lower = candidate.lowercase()
                    wakeWords.any { lower.contains(it) } || callKeywords.any { lower.contains(it) }
                } ?: matches.firstOrNull() ?: ""

                if (bestPartial.isNotBlank()) {
                    callbackPartialResult(bestPartial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }
}
