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
 * Proveedor de contexto por defecto en producción que consulta el repositorio de datos local,
 * hora/fecha real y ubicación GPS.
 */
class DefaultConversationContextProvider(
    private val context: android.content.Context? = null
) : ConversationContextProvider {

    override fun buildContextWindow(): String {
        return buildString {
            // Contexto temporal
            append(buildTemporalContext()).append("\n")

            // Ubicación GPS real
            val gpsInfo = context?.let { com.fifo.voicepipeline.location.FifoLocationHelper.getCurrentLocation(it) }
            val profile = com.fifo.voicepipeline.data.FifoDataRepository.userProfile.value
            if (gpsInfo != null && gpsInfo.isGpsActive) {
                append("UBICACIÓN GPS ACTUAL: ${gpsInfo.address} (${gpsInfo.city}). Coordenadas: ${gpsInfo.latitude}, ${gpsInfo.longitude}. Busca y guía siempre desde ${gpsInfo.city}.\n")
            } else if (profile.preferredAddress.isNotBlank()) {
                append("UBICACIÓN ESTIMADA: ${profile.preferredAddress} (${profile.city}).\n")
            }

            // Contexto de navegación activa
            if (gpsInfo != null) {
                @Suppress("DEPRECATION")
                val navContext = com.fifo.voicepipeline.location.FifoNavigationManager.instance.buildSystemPromptContext(gpsInfo)
                if (navContext.isNotBlank()) {
                    append(navContext).append("\n")
                }
            }

            // Memoria episódica compacta de conversaciones pasadas
            val compactMemory = com.fifo.voicepipeline.data.FifoDataRepository.buildCompactContextWindow(maxFragments = 1)
            if (compactMemory.isNotBlank()) {
                append(compactMemory).append("\n")
            }
        }.trim()
    }

    private fun buildTemporalContext(): String {
        val now = java.util.Calendar.getInstance()
        val hour = now.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = now.get(java.util.Calendar.MINUTE)
        val timeFormatted = String.format(java.util.Locale.US, "%02d:%02d", hour, minute)
        val dayOfWeek = when (now.get(java.util.Calendar.DAY_OF_WEEK)) {
            java.util.Calendar.MONDAY -> "Lunes"
            java.util.Calendar.TUESDAY -> "Martes"
            java.util.Calendar.WEDNESDAY -> "Miércoles"
            java.util.Calendar.THURSDAY -> "Jueves"
            java.util.Calendar.FRIDAY -> "Viernes"
            java.util.Calendar.SATURDAY -> "Sábado"
            else -> "Domingo"
        }
        val dayOfMonth = now.get(java.util.Calendar.DAY_OF_MONTH)
        val month = when (now.get(java.util.Calendar.MONTH)) {
            0 -> "Enero"; 1 -> "Febrero"; 2 -> "Marzo"; 3 -> "Abril"; 4 -> "Mayo"; 5 -> "Junio"
            6 -> "Julio"; 7 -> "Agosto"; 8 -> "Septiembre"; 9 -> "Octubre"; 10 -> "Noviembre"; else -> "Diciembre"
        }
        val year = now.get(java.util.Calendar.YEAR)
        val (period, correctGreeting) = when (hour) {
            in 6..11 -> "Mañana" to "Buenos días"
            in 12..19 -> "Tarde" to "Buenas tardes"
            else -> "Noche" to "Buenas noches"
        }
        return "HORA ACTUAL: $timeFormatted hrs ($period, saludo: '$correctGreeting'). Hoy es $dayOfWeek, $dayOfMonth de $month de $year."
    }
}

/**
 * Costura de cognición y diálogo (SpeechCognitionSeam).
 *
 * Desacopla la transcripción (Groq Whisper STT), el razonamiento conversacional (Groq LPU LLM)
 * y la reanudación de locución interrumpida (Barge-In) de la máquina de estados de VoiceSession.
 * 100% Groq: sin dependencias legacy ni Claude.
 */
interface SpeechCognitionAdapter {
    suspend fun transcribe(
        wavData: ByteArray,
        language: String = "es",
        allowShortResponses: Boolean = false
    ): String

    suspend fun generateDialogTurn(
        userQuery: String,
        skillRegistry: FifoSkillRegistry? = null
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
        previousAssistantText: String
    ): String {
        val prompt = buildString {
            append("El usuario está corrigiendo un turno anterior de la conversación.\n")
            append("Usuario antes: ").append(previousUserText).append("\n")
            append("Fifo respondió: ").append(previousAssistantText).append("\n")
            append("Corrección actual del usuario: ").append(correctionText).append("\n")
            append("Reconoce el error con calidez y responde a la corrección en una sola frase natural, ")
            append("sin justificarte de más y sin inventar datos nuevos.")
        }
        return generateDialogTurn(prompt, null)
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
        skillRegistry: FifoSkillRegistry?
    ): String {
        return delegate.generateDialogTurn(userQuery, skillRegistry)
    }

    override suspend fun generateNaturalResumption(interruptedSentence: String): String {
        return delegate.generateNaturalResumption(interruptedSentence)
    }

    override suspend fun generateRepairTurn(
        correctionText: String,
        previousUserText: String,
        previousAssistantText: String
    ): String {
        return delegate.generateRepairTurn(correctionText, previousUserText, previousAssistantText)
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
        skillRegistry: FifoSkillRegistry?
    ): String {
        receivedQueries.add(userQuery)

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
        previousAssistantText: String
    ): String {
        repairRequests.add(Triple(correctionText, previousUserText, previousAssistantText))
        return repairResponseToReturn
    }
}
