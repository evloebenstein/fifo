package com.fifo.voicepipeline.pipeline.session

import com.fifo.voicepipeline.audio.TextSanitizer

/**
 * Submódulo profundo que gobierna la síntesis de voz y la locución:
 * - Depura el texto con TextSanitizer (elimina asteriscos, markdown, emojis y tecnicismos).
 * - Delega la emisión de audio al adaptador de salida (AudioOutboundAdapter).
 * - Notifica la modulación RMS para sincronizar la boca del rostro de Fifo (FifoFace).
 * - Provee cancelación inmediata ante interrupciones de usuario (Barge-In).
 */
class SpeechOutboundEngine(
    private val outboundAdapter: AudioOutboundAdapter
) {
    /** Guarda la última frase completa que se envió a locución */
    var lastSpokenSentence: String = ""
        private set

    /** Indica si actualmente hay locución en curso */
    val isSpeaking: Boolean
        get() = outboundAdapter.isSpeaking

    /**
     * Limpia y reproduce un texto para el usuario.
     * @param rawText Texto recibido del modelo de lenguaje o del sistema.
     * @param onRmsUpdate Callback para animar la boca de Fifo con el volumen RMS.
     * @param onFinish Callback invocado cuando termina la locución de forma natural.
     */
    fun speak(
        rawText: String,
        onRmsUpdate: ((Double) -> Unit)? = null,
        onFinish: (() -> Unit)? = null
    ) {
        val cleanText = TextSanitizer.cleanForSpeech(rawText)
        if (cleanText.isBlank()) {
            onFinish?.invoke()
            return
        }

        lastSpokenSentence = cleanText
        outboundAdapter.speak(
            text = cleanText,
            onRmsUpdate = onRmsUpdate,
            onFinish = onFinish
        )
    }

    /**
     * Interrumpe inmediatamente la locución actual (Barge-In).
     * @return La frase que estaba siendo hablada al momento de la interrupción.
     */
    fun stopAndCaptureInterrupted(): String {
        val sentence = lastSpokenSentence
        outboundAdapter.stop()
        return sentence
    }
}
