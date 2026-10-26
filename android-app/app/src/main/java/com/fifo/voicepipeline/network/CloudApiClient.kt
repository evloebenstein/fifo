package com.fifo.voicepipeline.network

import com.fifo.voicepipeline.data.memory.SessionMemoryConsolidation
import com.fifo.voicepipeline.pipeline.session.ConversationContextProvider
import com.fifo.voicepipeline.pipeline.session.SpeechCognitionAdapter
import com.fifo.voicepipeline.skills.FifoSkillRegistry

/**
 * Fachada de las APIs de nube del pipeline de voz.
 *
 * Conserva el contrato histórico de [SpeechCognitionAdapter] y del diálogo,
 * mientras delega cada responsabilidad en una pieza especializada:
 * - [GroqSpeechToTextClient] → transcripción Whisper.
 * - [GroqDialogClient] → LLM, herramientas, contexto e historial.
 * - [GroqSessionMemoryExtractor] → consolidación de memoria de sesión.
 * - [OpenAiTextToSpeechClient] → audio PCM a 16 kHz.
 */
class CloudApiClient(
    var groqApiKey: String = "",
    var openAiApiKey: String = "",
    var context: android.content.Context? = null,
    var contextProvider: ConversationContextProvider? = null,
    var navigationManager: com.fifo.voicepipeline.location.FifoNavigationManager? = null
) : SpeechCognitionAdapter {
    /**
     * Clave efectiva para Groq: prioriza `groqApiKey` y acepta una clave `gsk_`
     * guardada en el campo de STT por compatibilidad.
     */
    val effectiveGroqKey: String
        get() = when {
            groqApiKey.trim().startsWith("gsk_") -> groqApiKey.trim()
            openAiApiKey.trim().startsWith("gsk_") -> openAiApiKey.trim()
            groqApiKey.isNotBlank() -> groqApiKey.trim()
            else -> ""
        }

    val isGroqActive: Boolean
        get() = effectiveGroqKey.isNotBlank()

    companion object {
        // Modelo principal de Groq: Qwen 27B (activo y disponible en esta cuenta de Groq)
        const val GROQ_PRIMARY_MODEL = "qwen/qwen3.8-27b"

        /**
         * Detecta alucinaciones de Whisper ante silencio o ruido ambiental.
         *
         * @param rawText     Texto transcrito por Whisper.
         * @param allowShortResponses Si es true (Fifo esperaba una respuesta de follow-up),
         *   palabras breves como "sí", "no", "ok", "gracias" se tratan como respuestas
         *   conversacionales reales y NO se descartan como alucinaciones.
         */
        fun isWhisperHallucination(rawText: String, allowShortResponses: Boolean = false): Boolean =
            WhisperHallucinationFilter.isHallucination(rawText, allowShortResponses)
    }

    private val speechToText = GroqSpeechToTextClient(
        groqKeyProvider = { effectiveGroqKey },
        openAiKeyProvider = { openAiApiKey }
    )
    private val speechSynthesis = OpenAiTextToSpeechClient(
        apiKeyProvider = { openAiApiKey }
    )
    private val dialog = GroqDialogClient(
        apiKeyProvider = { effectiveGroqKey },
        conversationContext = { contextProvider },
        androidContext = { context },
        navigationManager = { navigationManager }
    )
    private val memoryExtractor = GroqSessionMemoryExtractor(
        apiKeyProvider = { effectiveGroqKey }
    )

    // ═════════════════════════════════════════════════
    //  1. SPEECH-TO-TEXT (Whisper)
    // ═════════════════════════════════════════════════

    override suspend fun transcribe(
        wavData: ByteArray,
        language: String,
        allowShortResponses: Boolean
    ): String = speechToText.transcribe(wavData, language, allowShortResponses)

    // ═════════════════════════════════════════════════
    //  2. LLM AGENT (Groq LPU - Qwen 27B)
    // ═════════════════════════════════════════════════

    override suspend fun generateDialogTurn(
        userQuery: String,
        skillRegistry: FifoSkillRegistry?
    ): String = dialog.chat(userQuery, skillRegistry)

    suspend fun chat(
        userText: String,
        skillRegistry: FifoSkillRegistry? = null
    ): String = dialog.chat(userText, skillRegistry)

    fun hasRecentConversation(): Boolean = dialog.hasRecentConversation()

    /**
     * Redacta un aviso autónomo de navegación con el contexto espacial y de
     * sesión. Devuelve `null` si el LLM no está disponible: el llamador usa
     * entonces el fallback estático del aviso.
     */
    suspend fun narrateNavigationNotice(
        notice: com.fifo.voicepipeline.location.NavigationNotice
    ): String? = dialog.narrateNavigationNotice(notice)

    fun recordTurn(userText: String, assistantText: String) {
        dialog.recordTurn(userText, assistantText)
    }

    // ═════════════════════════════════════════════════
    //  3. MEMORIA DE SESIÓN
    // ═════════════════════════════════════════════════

    /**
     * Extrae un resumen estructurado y datos para la memoria de Fifo (SQLite) al finalizar la sesión.
     */
    suspend fun extractSessionMemory(turns: List<Pair<String, String>>): SessionMemoryConsolidation? =
        memoryExtractor.extract(turns, failOnError = false)

    /**
     * Variante para la cola durable: distingue "no había nada que recordar"
     * (null) de un fallo de red/parseo (excepción), para que el coordinador
     * pueda reintentar con backoff en lugar de descartar el turno.
     */
    suspend fun extractSessionMemoryChecked(turns: List<Pair<String, String>>): SessionMemoryConsolidation? =
        memoryExtractor.extract(turns, failOnError = true)

    // ═════════════════════════════════════════════════
    //  4. TEXT-TO-SPEECH (OpenAI TTS)
    // ═════════════════════════════════════════════════

    suspend fun textToSpeech(text: String): ByteArray? = speechSynthesis.synthesize(text)

    // ═════════════════════════════════════════════════
    //  5. CONTRATO DE DIÁLOGO / STREAMING
    // ═════════════════════════════════════════════════

    override suspend fun generateNaturalResumption(interruptedSentence: String): String =
        dialog.generateNaturalResumption(interruptedSentence)

    suspend fun testConnection(overrideKey: String? = null): Pair<Boolean, String> =
        dialog.testConnection(overrideKey)

    suspend fun testGroqConnection(overrideKey: String? = null): Pair<Boolean, String> =
        dialog.testGroqConnection(overrideKey)

    fun chatStreaming(
        userText: String,
        onChunk: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        dialog.chatStreaming(userText, onChunk, onComplete, onError)
    }

    fun clearHistory() {
        dialog.clearHistory()
    }
}
