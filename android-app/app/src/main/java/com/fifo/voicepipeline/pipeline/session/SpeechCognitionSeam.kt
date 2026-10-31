package com.fifo.voicepipeline.pipeline.session

import com.fifo.voicepipeline.skills.FifoSkillRegistry
import com.fifo.voicepipeline.skills.SkillResult

/**
 * Proveedor desacoplado de contexto ambiental, temporal y de memoria episódica.
 * Permite inyectar contexto enriquecido en producción o fixtures limpios en pruebas unitarias.
 */
interface ConversationContextProvider {
    fun buildContextWindow(): String
}

/**
 * Compatibilidad del proveedor histórico. **Deprecado (ADR-0049).**
 *
 * La composición real vive en `ConversationContext`, que `AppGraph` construye
 * con perfil, memoria, ubicación, navegación y la política de recuperación.
 * Este envoltorio solo delega para no romper firmas antiguas.
 */
@Deprecated(
    message = "Usa AppGraph.conversationContext / ConversationContext",
    replaceWith = ReplaceWith("AppGraph.requireInstance().conversationContext")
)
class DefaultConversationContextProvider(
    @Suppress("UNUSED_PARAMETER") context: android.content.Context? = null,
    @Suppress("UNUSED_PARAMETER") navigationManager: com.fifo.voicepipeline.location.FifoNavigationManager? = null
) : ConversationContextProvider {

    override fun buildContextWindow(): String =
        com.fifo.voicepipeline.data.AppGraph.requireInstance().conversationContext.buildContextWindow()
}

/**
 * Costura de cognición y diálogo (SpeechCognitionSeam).
 *
 * Desacopla la transcripción (Groq Whisper STT), el razonamiento conversacional (Groq LPU LLM)
 * y la reanudación de locución interrumpida (Barge-In) de la máquina de estados de VoiceSession.
 * 100% Groq: sin dependencias de proveedores heredados.
 */
interface SpeechCognitionAdapter {
    suspend fun transcribe(
        wavData: ByteArray,
        language: String = "es",
        allowShortResponses: Boolean = false
    ): String

    suspend fun generateDialogTurn(
        userQuery: String,
        skillRegistry: FifoSkillRegistry? = null,
        speaker: SpeakerIdentity? = null
    ): String

    suspend fun generateNaturalResumption(interruptedSentence: String): String

    /**
     * Genera una respuesta breve que reconoce el error del turno anterior y
     * responde a la corrección del usuario. La ventana de ±1 turno ya fue
     * decidida por [ConversationRepairPolicy]; aquí solo se redacta.
     */
    suspend fun generateRepairTurn(
        correctionText: String,
        previousUserText: String,
        previousAssistantText: String,
        speaker: SpeakerIdentity? = null
    ): String {
        val prompt = buildString {
            append("El usuario está corrigiendo un turno anterior de la conversación.\n")
            append("Usuario antes: ").append(previousUserText).append("\n")
            append("Fifo respondió: ").append(previousAssistantText).append("\n")
            append("Corrección actual del usuario: ").append(correctionText).append("\n")
            append("Reconoce el error con calidez y responde a la corrección en una sola frase natural, ")
            append("sin justificarte de más y sin inventar datos nuevos.")
        }
        return generateDialogTurn(prompt, null, speaker)
    }
}

/**
 * Adaptador de producción basado 100% en Groq LPU (Groq Whisper STT + Groq LLM).
 */
class GroqSpeechCognitionAdapter(
    private val delegate: SpeechCognitionAdapter
) : SpeechCognitionAdapter {

    override suspend fun transcribe(
        wavData: ByteArray,
        language: String,
        allowShortResponses: Boolean
    ): String {
        return delegate.transcribe(wavData, language, allowShortResponses)
    }

    override suspend fun generateDialogTurn(
        userQuery: String,
        skillRegistry: FifoSkillRegistry?,
        speaker: SpeakerIdentity?
    ): String {
        return delegate.generateDialogTurn(userQuery, skillRegistry, speaker)
    }

    override suspend fun generateNaturalResumption(interruptedSentence: String): String {
        return delegate.generateNaturalResumption(interruptedSentence)
    }

    override suspend fun generateRepairTurn(
        correctionText: String,
        previousUserText: String,
        previousAssistantText: String,
        speaker: SpeakerIdentity?
    ): String {
        return delegate.generateRepairTurn(correctionText, previousUserText, previousAssistantText, speaker)
    }
}

/**
 * Adaptador en memoria para pruebas unitarias deterministas y rápidas sin conexión de red.
 * Soporta simulación de ejecución de herramientas (Skills) para verificar que Fifo interactúa
 * con hardware, llamadas y navegación en la JVM sin dispositivos físicos.
 */
class InMemorySpeechCognitionAdapter : SpeechCognitionAdapter {
    var transcriptionToReturn: String = "Fifo ¿cómo estás?"
    var responseToReturn: String = "Hola, estoy muy bien, gracias por preguntar."
    var resumptionToReturn: String = "Como te estaba diciendo, podemos continuar."
    var repairResponseToReturn: String = "Tienes razón, me equivoqué. Gracias por corregirme."

    // Simulación de invocación de herramientas (Skills) en tests
    var simulatedToolToInvoke: String? = null
    var simulatedToolArguments: Map<String, Any?> = emptyMap()
    var simulatedToolSpokenFeedback: String? = null
    var lastExecutedSkillResult: SkillResult? = null

    val transcribedAudios = mutableListOf<ByteArray>()
    val receivedQueries = mutableListOf<String>()
    val receivedSpeakers = mutableListOf<SpeakerIdentity?>()
    val resumptionRequests = mutableListOf<String>()
    val repairRequests = mutableListOf<Triple<String, String, String>>()

    override suspend fun transcribe(
        wavData: ByteArray,
        language: String,
        allowShortResponses: Boolean
    ): String {
        transcribedAudios.add(wavData)
        return transcriptionToReturn
    }

    override suspend fun generateDialogTurn(
        userQuery: String,
        skillRegistry: FifoSkillRegistry?,
        speaker: SpeakerIdentity?
    ): String {
        receivedQueries.add(userQuery)
        receivedSpeakers.add(speaker)

        // Si se configuró una herramienta a simular, la ejecutamos en el registry
        val toolName = simulatedToolToInvoke
        if (toolName != null && skillRegistry != null) {
            val result = skillRegistry.executeSkill(toolName, simulatedToolArguments)
            lastExecutedSkillResult = result
            if (result.spokenFeedback.isNotBlank()) {
                return result.spokenFeedback
            }
        }

        if (simulatedToolSpokenFeedback != null) {
            return simulatedToolSpokenFeedback!!
        }

        return responseToReturn
    }

    override suspend fun generateNaturalResumption(interruptedSentence: String): String {
        resumptionRequests.add(interruptedSentence)
        return resumptionToReturn
    }

    override suspend fun generateRepairTurn(
        correctionText: String,
        previousUserText: String,
        previousAssistantText: String,
        speaker: SpeakerIdentity?
    ): String {
        repairRequests.add(Triple(correctionText, previousUserText, previousAssistantText))
        return repairResponseToReturn
    }
}
