package com.fifo.voicepipeline.network

import android.util.Log
import com.fifo.voicepipeline.pipeline.session.ConversationContextProvider
import com.fifo.voicepipeline.pipeline.session.SpeechCognitionAdapter
import com.fifo.voicepipeline.skills.FifoSkillRegistry
import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Fachada de las APIs de nube del pipeline de voz.
 *
 * Conserva el contrato histórico de [SpeechCognitionAdapter] y del diálogo,
 * mientras delega cada responsabilidad en una pieza especializada:
 * - [GroqSpeechToTextClient] → transcripción Whisper.
 * - [GroqDialogClient] → LLM, herramientas, contexto e historial.
 * - [OpenAiTextToSpeechClient] → audio PCM a 16 kHz.
 *
 * La extracción de memoria de sesión permanece aquí porque es un contrato de
 * consolidación consumido por el repositorio, no una responsabilidad de la sesión.
 */
class CloudApiClient(
    var groqApiKey: String = "",
    var openAiApiKey: String = "",
    var context: android.content.Context? = null,
    var contextProvider: ConversationContextProvider? = null
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
        private const val TAG = "CloudApiClient"
        private const val GROQ_CHAT_URL = "https://api.groq.com/openai/v1/chat/completions"

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

    private val gson = Gson()

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
        androidContext = { context }
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

    fun recordTurn(userText: String, assistantText: String) {
        dialog.recordTurn(userText, assistantText)
    }

    /**
     * Extrae un resumen estructurado y datos para la memoria de Fifo (SQLite) al finalizar la sesión.
     */
    suspend fun extractSessionMemory(turns: List<Pair<String, String>>): SessionMemoryConsolidation? =
        extractSessionMemoryInternal(turns, failOnError = false)

    /**
     * Variante para la cola durable: distingue "no había nada que recordar"
     * (null) de un fallo de red/parseo (excepción), para que el coordinador
     * pueda reintentar con backoff en lugar de descartar el turno.
     */
    suspend fun extractSessionMemoryChecked(turns: List<Pair<String, String>>): SessionMemoryConsolidation? =
        extractSessionMemoryInternal(turns, failOnError = true)

    private suspend fun extractSessionMemoryInternal(
        turns: List<Pair<String, String>>,
        failOnError: Boolean
    ): SessionMemoryConsolidation? {
        if (turns.isEmpty()) return null
        val conversationText = turns.joinToString("\n") { (user, fifo) ->
            "Usuario: $user\nFifo: $fifo"
        }

        val prompt = """
Eres el módulo de consolidación de memoria del asistente Fifo.
Analiza la siguiente conversación de voz reciente entre el usuario y Fifo:

$conversationText

Extrae la información clave para persistirla en la base de datos de memoria densa del asistente, prestando especial atención a:
1. Locales comerciales, tiendas, calles, comidas, gustos específicos o lugares visitados o consultados.
2. PERSONAS Y VÍNCULOS MENCIONADOS: Familiares, nietos, hijos, amigos, vecinos, conocidos o doctores hablados en la charla, su parentesco o rol, qué se contó de ellos y la valencia emocional.

Responde ÚNICAMENTE un objeto JSON válido con los siguientes campos:
{
  "primaryTag": "categoría temática corta (ej: Lugares, Comida, Deportes, Rutina, Familia, Salud, General)",
  "keyTopics": ["tema1", "tema2", "comidas o productos"],
  "namedEntities": ["locales, tiendas, personas, calles o lugares específicos"],
  "detectedMood": "ánimo detectado (ej: motivado, tranquilo, contento, pensativo, neutral)",
  "compactSummary": "resumen denso de 1 o 2 oraciones incluyendo nombres exactos de personas, locales, lugares o temas hablados",
  "conversationTitle": "título conciso de la charla (ej: Visita de nieto Tomás, Charla sobre orquídeas)",
  "memoryTitle": "título de un recuerdo nuevo sobre el usuario (o null si solo fue saludo o charla trivial)",
  "memoryDetail": "detalle específico denso del recuerdo aprendido sobre el usuario, locales o gustos (o null si no aplica)",
  "memoryEmoji": "emoji representativo (ej: 📍, 🥐, 🥋, ⭐, 💊, 🏃)",
  "newTaste": "nuevo gusto, comida o interés del usuario mencionado (o null si no hubo)",
  "mentionedPersons": [
    {
      "name": "nombre de la persona mencionada (ej: Tomás, Carmen, Rosa, Pedro)",
      "inferredRole": "parentesco o relación deducida (ej: Nieto, Hija, Vecina, Amiga, Sobrino, Médico)",
      "contextSnippet": "qué contó o qué pasó con esta persona (ej: vino a almorzar el domingo, la llamó por teléfono, la ayudó con las plantas)",
      "emotionalValence": "afectuoso, preocupacion, gratitud, respeto, neutral",
      "closenessDelta": 0.05
    }
  ]
}
""".trimIndent()

        val jsonResponse = if (isGroqActive) {
            try {
                val apiKey = effectiveGroqKey
                val jsonBody = """
                {
                    "model": "$GROQ_PRIMARY_MODEL",
                    "messages": [
                        {"role": "system", "content": "Eres un extractor de memoria estructurada en formato JSON estricto."},
                        {"role": "user", "content": ${gson.toJson(prompt)}}
                    ],
                    "temperature": 0.1,
                    "max_tokens": 512,
                    "response_format": {"type": "json_object"}
                }
                """.trimIndent()

                val request = Request.Builder()
                    .url(GROQ_CHAT_URL)
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .post(jsonBody.toRequestBody("application/json".toMediaType()))
                    .build()

                val response = fifoHttpClient.newCall(request).executeSuspend()
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val root = JsonParser.parseString(body).asJsonObject
                    root.getAsJsonArray("choices")?.get(0)?.asJsonObject
                        ?.getAsJsonObject("message")?.get("content")?.asString
                } else null
            } catch (e: Exception) {
                Log.w(TAG, "Error extrayendo memoria de sesión en Groq: ${e.message}")
                if (failOnError) throw e
                null
            }
        } else null

        if (jsonResponse.isNullOrBlank()) {
            if (failOnError && isGroqActive) {
                throw java.io.IOException("Groq no devolvió memoria de sesión")
            }
            return null
        }

        return try {
            val obj = JsonParser.parseString(jsonResponse).asJsonObject
            val primaryTag = obj.get("primaryTag")?.asString ?: "General"
            val keyTopics = mutableListOf<String>()
            obj.getAsJsonArray("keyTopics")?.forEach { elem ->
                if (!elem.isJsonNull) keyTopics.add(elem.asString)
            }
            val namedEntities = mutableListOf<String>()
            obj.getAsJsonArray("namedEntities")?.forEach { elem ->
                if (!elem.isJsonNull) namedEntities.add(elem.asString)
            }
            val detectedMood = obj.get("detectedMood")?.asString ?: "tranquilo"
            val compactSummary = obj.get("compactSummary")?.asString ?: ""
            val conversationTitle = obj.get("conversationTitle")?.asString ?: "Charla con Fifo"
            val memoryTitle = obj.get("memoryTitle")?.run { if (isJsonNull || asString.equals("null", true)) null else asString }
            val memoryDetail = obj.get("memoryDetail")?.run { if (isJsonNull || asString.equals("null", true)) null else asString }
            val memoryEmoji = obj.get("memoryEmoji")?.run { if (isJsonNull || asString.equals("null", true)) null else asString }
            val newTaste = obj.get("newTaste")?.run { if (isJsonNull || asString.equals("null", true)) null else asString }

            val mentionedPersons = mutableListOf<MentionedPersonExtraction>()
            obj.getAsJsonArray("mentionedPersons")?.forEach { elem ->
                if (elem.isJsonObject) {
                    val pObj = elem.asJsonObject
                    val name = pObj.get("name")?.run { if (!isJsonNull) asString else "" } ?: ""
                    if (name.isNotBlank()) {
                        val inferredRole = pObj.get("inferredRole")?.run { if (!isJsonNull && !asString.equals("null", true)) asString else null }
                        val snippet = pObj.get("contextSnippet")?.run { if (!isJsonNull) asString else "" } ?: ""
                        val valence = pObj.get("emotionalValence")?.run { if (!isJsonNull) asString else "afectuoso" } ?: "afectuoso"
                        val delta = pObj.get("closenessDelta")?.run { if (!isJsonNull && isJsonPrimitive && asJsonPrimitive.isNumber) asFloat else 0.05f } ?: 0.05f
                        mentionedPersons.add(
                            MentionedPersonExtraction(
                                name = name.trim(),
                                inferredRole = inferredRole?.trim(),
                                contextSnippet = snippet.trim(),
                                emotionalValence = valence,
                                closenessDelta = delta
                            )
                        )
                    }
                }
            }

            SessionMemoryConsolidation(
                primaryTag = primaryTag,
                keyTopics = keyTopics,
                namedEntities = namedEntities,
                detectedMood = detectedMood,
                compactSummary = compactSummary,
                conversationTitle = conversationTitle,
                memoryTitle = memoryTitle,
                memoryDetail = memoryDetail,
                memoryEmoji = memoryEmoji,
                newTaste = newTaste,
                mentionedPersons = mentionedPersons
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parseando JSON de consolidación: ${e.message}", e)
            if (failOnError) throw e
            null
        }
    }

    // ═════════════════════════════════════════════════
    //  3. TEXT-TO-SPEECH (OpenAI TTS)
    // ═════════════════════════════════════════════════

    suspend fun textToSpeech(text: String): ByteArray? = speechSynthesis.synthesize(text)

    // ═════════════════════════════════════════════════
    //  4. CONTRATO DE DIÁLOGO / STREAMING
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

/**
 * Entidad de persona mencionada en una conversación para el Grafo Relacional.
 */
data class MentionedPersonExtraction(
    val name: String,
    val inferredRole: String? = null,
    val contextSnippet: String = "",
    val emotionalValence: String = "afectuoso",
    val closenessDelta: Float = 0.05f
)

/**
 * Modelo de consolidación de sesión de conversación para extracción y guardado en SQLite.
 */
data class SessionMemoryConsolidation(
    val primaryTag: String = "General",
    val keyTopics: List<String> = emptyList(),
    val namedEntities: List<String> = emptyList(),
    val detectedMood: String = "tranquilo",
    val compactSummary: String = "",
    val conversationTitle: String = "Charla con Fifo",
    val memoryTitle: String? = null,
    val memoryDetail: String? = null,
    val memoryEmoji: String? = null,
    val newTaste: String? = null,
    val mentionedPersons: List<MentionedPersonExtraction> = emptyList()
)
