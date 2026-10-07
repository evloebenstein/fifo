package com.fifo.voicepipeline.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Sintetizador de voz usando el motor nativo de Android (TextToSpeech).
 *
 * Reproduce las respuestas del agente directamente por el parlante del celular
 * sin necesidad de claves de API externas (como OpenAI), sin latencia de red
 * y de forma completamente gratuita.
 */
class AndroidTtsSpeaker(
    context: Context,
    private val onStart: () -> Unit = {},
    private val onDone: () -> Unit = {},
    private val onError: (String) -> Unit = {}
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "AndroidTtsSpeaker"
        private const val UTTERANCE_PREFIX = "fifo_utterance_"
    }

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isInitialized = false
    private var utteranceCounter = 0L

    @Volatile
    var isSpeaking = false
        private set

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("es", "ES"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Español (ES) no disponible, intentando locale por defecto")
                tts?.setLanguage(Locale.getDefault())
            }

            tts?.setSpeechRate(1.05f) // Velocidad natural y fluida
            tts?.setPitch(1.0f)

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isSpeaking = true
                    Log.d(TAG, "TTS inició reproducción: $utteranceId")
                    onStart()
                }

                override fun onDone(utteranceId: String?) {
                    isSpeaking = false
                    Log.d(TAG, "TTS completó reproducción: $utteranceId")
                    onDone()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    isSpeaking = false
                    Log.e(TAG, "Error en TTS: $utteranceId")
                    onError("Error en reproducción de voz")
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    isSpeaking = false
                    Log.e(TAG, "Error en TTS ($errorCode): $utteranceId")
                    onError("Error en reproducción de voz (código $errorCode)")
                }
            })

            isInitialized = true
            Log.i(TAG, "TextToSpeech nativo de Android inicializado correctamente")
        } else {
            Log.e(TAG, "Fallo al inicializar TextToSpeech nativo (código $status)")
            onError("No se pudo inicializar el sintetizador de voz")
        }
    }

    /**
     * Pronuncia un fragmento o frase de texto por el parlante del celular.
     *
     * @param text Texto a decir.
     * @param queueMode TextToSpeech.QUEUE_FLUSH por defecto para evitar respuestas duplicadas, o QUEUE_ADD si se desea encadenar frases explícitamente.
     */
    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        if (!isInitialized || tts == null || text.isBlank()) return

        val cleanText = TextSanitizer.cleanForSpeech(text)
        if (cleanText.isBlank()) return

        val utteranceId = "$UTTERANCE_PREFIX${++utteranceCounter}"
        tts?.speak(cleanText, queueMode, null, utteranceId)
    }

    /**
     * Detiene inmediatamente cualquier locución en curso.
     */
    fun stop() {
        if (isInitialized) {
            tts?.stop()
            isSpeaking = false
        }
    }

    /**
     * Libera los recursos del motor de TTS.
     */
    fun release() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        Log.d(TAG, "TextToSpeech liberado")
    }
}
