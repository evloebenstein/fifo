package com.fifo.voicepipeline.pipeline.session

import com.fifo.voicepipeline.network.CloudApiClient
import com.fifo.voicepipeline.skills.FifoSkillRegistry

/**
 * Costura de cognición y diálogo (SpeechCognitionSeam).
 *
 * Desacopla la transcripción (Groq Whisper STT), el razonamiento conversacional (Groq LPU LLM)
 * y la reanudación de locución interrumpida (Barge-In) de la máquina de estados de VoiceSession.
 * 100% Groq: sin APIs de terceros ni Claude.
 */
interface SpeechCognitionAdapter {
    suspend fun transcribe(pcmWavData: ByteArray, language: String = "es"): String
    suspend fun generateDialogTurn(
        userQuery: String,
        skillRegistry: FifoSkillRegistry? = null
    ): String
    suspend fun generateNaturalResumption(interruptedSentence: String): String
}

/**
 * Adaptador de producción basado 100% en Groq LPU (Groq Whisper STT + Groq LLM).
 */
class GroqSpeechCognitionAdapter(
    private val cloudApiClient: CloudApiClient
) : SpeechCognitionAdapter {

    override suspend fun transcribe(pcmWavData: ByteArray, language: String): String {
        return cloudApiClient.transcribe(pcmWavData, language)
    }

    override suspend fun generateDialogTurn(
        userQuery: String,
        skillRegistry: FifoSkillRegistry?
    ): String {
        return cloudApiClient.chat(userQuery, skillRegistry)
    }

    override suspend fun generateNaturalResumption(interruptedSentence: String): String {
        return cloudApiClient.generateNaturalResumption(interruptedSentence)
    }
}

/**
 * Adaptador en memoria para pruebas unitarias deterministas y rápidas sin conexión de red.
 */
class InMemorySpeechCognitionAdapter : SpeechCognitionAdapter {
    var transcriptionToReturn: String = "Fifo ¿cómo estás?"
    var responseToReturn: String = "Hola, estoy muy bien, gracias por preguntar."
    var resumptionToReturn: String = "Como te estaba diciendo, podemos continuar."

    val transcribedAudios = mutableListOf<ByteArray>()
    val receivedQueries = mutableListOf<String>()
    val resumptionRequests = mutableListOf<String>()

    override suspend fun transcribe(pcmWavData: ByteArray, language: String): String {
        transcribedAudios.add(pcmWavData)
        return transcriptionToReturn
    }

    override suspend fun generateDialogTurn(
        userQuery: String,
        skillRegistry: FifoSkillRegistry?
    ): String {
        receivedQueries.add(userQuery)
        return responseToReturn
    }

    override suspend fun generateNaturalResumption(interruptedSentence: String): String {
        resumptionRequests.add(interruptedSentence)
        return resumptionToReturn
    }
}
