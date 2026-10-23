package com.fifo.voicepipeline.network

import android.util.Log
import com.fifo.voicepipeline.audio.AudioConfig
import com.fifo.voicepipeline.data.FifoDataRepository
import com.fifo.voicepipeline.pipeline.session.ConversationContextProvider
import com.fifo.voicepipeline.pipeline.session.SpeechCognitionAdapter
import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/**
 * Fachada de las APIs de nube del pipeline de voz.
 *
 * Conserva el contrato histórico de [SpeechCognitionAdapter] y del diálogo,
 * mientras delega STT y TTS en adaptadores especializados:
 * - [GroqSpeechToTextClient] → transcripción Whisper.
 * - [OpenAiTextToSpeechClient] → audio PCM a 16 kHz.
 *
 * El LLM, la orquestación de herramientas, el contexto y la extracción de
 * memoria siguen aquí porque comparten historial y dependen del catálogo de skills.
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

        // ── Endpoints ───────────────────────────────
        private const val GROQ_CHAT_URL = "https://api.groq.com/openai/v1/chat/completions"

        // ── Modelos ──────────────────────────────────
        // Modelo principal de Groq: Qwen 27B (activo y disponible en esta cuenta de Groq)
        const val GROQ_PRIMARY_MODEL = "qwen/qwen3.8-27b"

        // ── System prompt ultra-compacto del robot Fifo (optimizado para voz y bajo consumo de tokens) ──
        private const val SYSTEM_PROMPT = """Eres Fifo, asistente robótico y compañero empático para adultos mayores.
REGLAS:
1. Trata siempre de usted con cariño. Responde ÚNICAMENTE en 1 o 2 oraciones breves habladas por TTS. Sin emojis, sin markdown (*, #), sin "Fifo:".
2. Sin preámbulos: Ve directo a la respuesta. Si piden comercios o direcciones, da nombre, calle y distancia directamente.
3. Ubicación y navegación ('search_nearby_places', 'open_navigation_directions'):
   - Al sugerir o guiar, usa la ubicación GPS real. NUNCA respondas con el nombre de la comuna como si fuera un local.
   - Si el usuario confirma un destino sugerido ("sí", "guíame", "vamos", "dije sí"), invoca 'open_navigation_directions' con destination='confirm_pending'.
4. Celular y hardware: 'manage_phone_call' para llamadas, 'read_phone_contacts' para contactos (si piden dictar número, díctalo pausado con comas), 'control_device_hardware' para linterna/volumen/batería/hora, 'find_fifo_device' para hacer sonar el robot.
5. Memoria e internet: Usa 'recall_past_context' ante preguntas sobre charlas pasadas, qué dijo alguien (familia, doctora), recetas médicas, ropa o locales. Si hay una laguna o espacio en blanco, dilo con honestidad; si nunca hablaron de eso, admite con cariño que no lo conversaron (NUNCA inventes). Usa 'web_search' para verificar noticias, clima o si un restaurante sigue abierto.
6. CERO RESPUESTAS GENÉRICAS O ENLATADAS:
   - Al gestionar llamadas ('manage_phone_call'), habla con calidez reconociendo a la persona que llama y su relación con el usuario. Al colgar, ofrece amablemente anotar un recado o recordatorio si hablaron de algo importante.
   - Al controlar hardware ('control_device_hardware'), comprende la situación del usuario con empatía (ej: si pide luz porque no ve, o volumen porque no oye).
   - Al dar direcciones o guiar ('open_navigation_directions', 'search_nearby_places'), comprende la NECESIDAD detrás del destino (salud, comida, compras, paseo) y sugiere ideas o precauciones pertinentes sin rodeos."""

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

    /** Historial de conversación para contexto */
    private val conversationHistory = mutableListOf<Map<String, String>>()

    // ═════════════════════════════════════════════════
    //  1. SPEECH-TO-TEXT (Whisper)
    // ═════════════════════════════════════════════════

    /**
     * Transcribe audio WAV a texto usando Whisper.
     * Prioriza Groq Whisper Large V3 Turbo si hay clave de Groq (100% gratuito y veloz).
     *
     * @param wavData Audio en formato WAV (header + PCM)
     * @param language Código de idioma (ej: "es", "en")
     * @return Texto transcrito
     */
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
        skillRegistry: com.fifo.voicepipeline.skills.FifoSkillRegistry?
    ): String = chat(userQuery, skillRegistry)

    /**
     * Envía el texto del usuario al cerebro de IA (Groq LPU),
     * soportando Function Calling / Tools mediante [FifoSkillRegistry].
     */
    suspend fun chat(
        userText: String,
        skillRegistry: com.fifo.voicepipeline.skills.FifoSkillRegistry? = null
    ): String {
        conversationHistory.add(mapOf("role" to "user", "content" to userText))
        while (conversationHistory.size > 8) {
            conversationHistory.removeAt(0)
        }

        val response = if (isGroqActive) {
            chatWithGroq(userText, skillRegistry)
        } else {
            "Disculpe, la API de Groq no se encuentra configurada en este momento. Por favor ingrese su clave de Groq para poder conversar."
        }

        // Si la llamada falló con un error técnico del servidor, no dejar el turno huérfano para evitar acumulación y 429
        if (response.startsWith("Disculpe,") || response.startsWith("Disculpa,")) {
            if (conversationHistory.isNotEmpty() && conversationHistory.last()["role"] == "user") {
                conversationHistory.removeAt(conversationHistory.size - 1)
            }
        }

        return response
    }

    /** Indica si hay turnos de conversación recientes en memoria */
    fun hasRecentConversation(): Boolean = conversationHistory.isNotEmpty()

    /**
     * Registra un turno de conversación (usuario + respuesta de Fifo) en el historial de memoria,
     * asegurando contexto continuo para turnos subsiguientes.
     */
    fun recordTurn(userText: String, assistantText: String) {
        if (userText.isBlank() || assistantText.isBlank()) return
        val lastAssistant = conversationHistory.lastOrNull { it["role"] == "assistant" }
        if (lastAssistant != null && lastAssistant["content"] == assistantText) {
            return
        }
        conversationHistory.add(mapOf("role" to "user", "content" to userText.trim()))
        conversationHistory.add(mapOf("role" to "assistant", "content" to assistantText.trim()))
        while (conversationHistory.size > 8) {
            conversationHistory.removeAt(0)
        }
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

    /**
     * Construye un fragmento contextual dinámico con la hora local real, fecha y continuidad de turnos.
     */
    private fun buildTemporalAndSessionContext(): String {
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
        val fullDate = "$dayOfWeek, $dayOfMonth de $month de $year"

        val (period, correctGreeting) = when (hour) {
            in 6..11 -> "Mañana" to "Buenos días"
            in 12..19 -> "Tarde" to "Buenas tardes"
            else -> "Noche" to "Buenas noches"
        }

        val userTurnsInHistory = conversationHistory.count { it["role"] == "user" }
        val pastConvsCount = FifoDataRepository.conversations.value.size
        val firstName = FifoDataRepository.userProfile.value.fullName.split(" ").firstOrNull { it.isNotBlank() } ?: ""
        val nameExample = if (firstName.isNotBlank()) "'Dígame $firstName'" else "'Dígame'"

        return "HORA ACTUAL: $timeFormatted hrs ($period, saludo: '$correctGreeting')." +
            if (userTurnsInHistory > 1 || pastConvsCount > 0) " (Conversación en curso: responde directo y cercano sin repetir saludos formales)." else ""
    }

    /**
     * Construye el prompt de sistema enriquecido con memoria y estado temporal en tiempo real,
     * ultra-optimizado (<300 tokens) para evitar sobrecostos y errores 429 en Groq.
     */
    private fun getEnrichedSystemPrompt(): String {
        val injectedContext = contextProvider?.buildContextWindow()
        if (!injectedContext.isNullOrBlank()) {
            return buildString {
                append(SYSTEM_PROMPT).append("\n\n")
                append(injectedContext)
            }.trim()
        }

        val compactContext = FifoDataRepository.buildCompactContextWindow(maxFragments = 1)
        val temporalContext = buildTemporalAndSessionContext()
        val profile = FifoDataRepository.userProfile.value
        val gpsInfo = context?.let { com.fifo.voicepipeline.location.FifoLocationHelper.getCurrentLocation(it) }

        val locationContext = if (gpsInfo != null && gpsInfo.isGpsActive) {
            "UBICACIÓN GPS ACTUAL: ${gpsInfo.address} (${gpsInfo.city}). Coordenadas: ${gpsInfo.latitude}, ${gpsInfo.longitude}. Busca y guía siempre desde ${gpsInfo.city}."
        } else if (profile.preferredAddress.isNotBlank()) {
            "UBICACIÓN ESTIMADA: ${profile.preferredAddress} (${profile.city})."
        } else ""

        val navContext = if (gpsInfo != null) {
            @Suppress("DEPRECATION")
            com.fifo.voicepipeline.location.FifoNavigationManager.instance.buildSystemPromptContext(gpsInfo)
        } else ""

        return buildString {
            append(SYSTEM_PROMPT).append("\n\n")
            append(temporalContext).append("\n")
            if (locationContext.isNotBlank()) {
                append(locationContext).append("\n")
            }
            if (navContext.isNotBlank()) {
                append(navContext).append("\n")
            }
            if (compactContext.isNotBlank()) {
                append(compactContext).append("\n")
            }
        }.trim()
    }

    /**
     * Motor conversacional principal con Groq LPU (gratuito, ultrarrápido).
     * Utiliza Qwen 27B. Los errores de red no se reintentan con el mismo modelo
     * para evitar duplicar turnos y llamadas recursivas.
     */
    private suspend fun chatWithGroq(
        userText: String,
        skillRegistry: com.fifo.voicepipeline.skills.FifoSkillRegistry?,
        modelToUse: String = GROQ_PRIMARY_MODEL,
        retryCount: Int = 0
    ): String {
        val apiKey = effectiveGroqKey
        val enrichedSystemPrompt = getEnrichedSystemPrompt()

        val messagesList = mutableListOf<Map<String, String>>()
        messagesList.add(mapOf("role" to "system", "content" to enrichedSystemPrompt))
        // Limitar turnos previos a los últimos 2 para no rebasar el límite de ITPM 7000
        val compactHistory = conversationHistory.takeLast(2)
        messagesList.addAll(compactHistory)

        val messagesJson = gson.toJson(messagesList)
        val toolsFragment = if (skillRegistry != null) {
            """, "tools": ${skillRegistry.getCompactOpenAiToolsJson()}, "tool_choice": "auto""""
        } else {
            ""
        }

        val jsonBody = """
        {
            "model": "$modelToUse",
            "messages": $messagesJson$toolsFragment,
            "max_tokens": 384,
            "temperature": 0.6
        }
        """.trimIndent()

        val request = Request.Builder()
            .url(GROQ_CHAT_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("User-Agent", "FifoVoiceApp/1.0 (Android; okhttp)")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            val response = fifoHttpClient.newCall(request).executeSuspend()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "Groq error ($modelToUse) ${response.code}: $body")
                // Si hay rate limit 429, esperar 2 segundos y reintentar (a ~1,100 tokens, 2s libera ~230 tokens)
                if (response.code == 429 && retryCount < 2) {
                    Log.w(TAG, "Groq 429: límite de tasa alcanzado. Esperando 2000ms y reintentando...")
                    kotlinx.coroutines.delay(2000)
                    return chatWithGroq(userText, skillRegistry, modelToUse = modelToUse, retryCount = retryCount + 1)
                }

                if (response.code == 429) {
                    return "Disculpe, el servicio está procesando muchas consultas en este instante. Por favor pregúnteme nuevamente en unos segundos."
                }
                return "Disculpe, hubo un inconveniente de conexión con el motor de voz (error ${response.code})."
            }

            val json = JsonParser.parseString(body).asJsonObject
            val choices = json.getAsJsonArray("choices")
            if (choices == null || choices.size() == 0) {
                return "Disculpa, no recibí respuesta del servidor."
            }

            val messageObj = choices[0].asJsonObject.getAsJsonObject("message")
            var textReply = messageObj.get("content")?.run { if (isJsonNull) "" else asString } ?: ""
            var toolFeedback = ""

            val toolCalls = messageObj.getAsJsonArray("tool_calls")
            if (toolCalls != null && toolCalls.size() > 0 && skillRegistry != null) {
                val currentAssistantMessage = JsonParser.parseString(messageObj.toString()).asJsonObject

                val toolResultMessages = mutableListOf<Map<String, Any?>>()
                var lastSpokenFallback = ""

                for (toolElement in toolCalls) {
                    val callObj = toolElement.asJsonObject
                    val callId = callObj.get("id")?.asString ?: "call_${System.currentTimeMillis()}"
                    val funcObj = callObj.getAsJsonObject("function") ?: continue
                    val toolName = funcObj.get("name")?.asString ?: continue
                    val argsString = funcObj.get("arguments")?.asString ?: "{}"

                    val argsMap = mutableMapOf<String, Any?>()
                    try {
                        val parsedArgs = JsonParser.parseString(argsString).asJsonObject
                        parsedArgs.keySet().forEach { k ->
                            val elem = parsedArgs.get(k)
                            if (elem.isJsonPrimitive) {
                                val prim = elem.asJsonPrimitive
                                argsMap[k] = when {
                                    prim.isNumber -> prim.asInt
                                    prim.isBoolean -> prim.asBoolean
                                    else -> prim.asString
                                }
                            } else {
                                argsMap[k] = elem.toString()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parseando argumentos de tool '$toolName': ${e.message}")
                    }

                    Log.i(TAG, "Ejecutando tool desde Groq: $toolName con args=$argsMap")
                    val result = try {
                        skillRegistry.executeSkill(toolName, argsMap)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error ejecutando skill '$toolName': ${e.message}", e)
                        com.fifo.voicepipeline.skills.SkillResult(
                            success = false,
                            spokenFeedback = "No se pudo completar la acción en este momento."
                        )
                    }

                    if (result.spokenFeedback.isNotBlank()) {
                        lastSpokenFallback = result.spokenFeedback
                    }

                    val compactData = result.data?.filterKeys {
                        it in listOf(
                            "destination", "distance_meters", "walking_minutes",
                            "driving_minutes", "cardinalDirection", "title",
                            "summary", "heading", "screen_opened", "success",
                            "is_plural", "nearby_places",
                            // Contexto de telefonía y relaciones
                            "action", "status", "caller", "caller_name", "phone",
                            "relationship", "recent_context", "suggested_followup", "notes",
                            // Contexto de control de hardware
                            "feature", "state", "battery_level", "is_charging", "time_formatted", "volumePercent",
                            // Contexto y necesidad de navegación
                            "street", "road", "turn", "relative_turn", "purpose_type",
                            "contextual_notes", "contextual_recommendations", "spatial_awareness"
                        )
                    }
                    val toolPayload = mapOf(
                        "success" to result.success,
                        "spoken_feedback" to result.spokenFeedback,
                        "data" to compactData
                    )
                    toolResultMessages.add(
                        mapOf(
                            "role" to "tool",
                            "tool_call_id" to callId,
                            "name" to toolName,
                            "content" to gson.toJson(toolPayload)
                        )
                    )
                }

                // Follow-up a Groq con los resultados de las herramientas para que formule una respuesta inteligente, concisa y contextual
                val followUpMessages = mutableListOf<Any>()
                followUpMessages.add(mapOf("role" to "system", "content" to enrichedSystemPrompt))
                // Limitar turnos previos a los últimos 2 para ahorrar tokens y no superar el límite de 7000 ITPM
                val compactFollowUpHistory = conversationHistory.takeLast(2)
                followUpMessages.addAll(compactFollowUpHistory)
                followUpMessages.add(currentAssistantMessage)
                followUpMessages.addAll(toolResultMessages)

                try {
                    val followUpBody = """
                    {
                        "model": "$modelToUse",
                        "messages": ${gson.toJson(followUpMessages)},
                        "max_tokens": 256,
                        "temperature": 0.5
                    }
                    """.trimIndent()

                    val followUpReq = Request.Builder()
                        .url(GROQ_CHAT_URL)
                        .header("Authorization", "Bearer $apiKey")
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "FifoVoiceApp/1.0 (Android; okhttp)")
                        .post(followUpBody.toRequestBody("application/json".toMediaType()))
                        .build()

                    val followUpResp = fifoHttpClient.newCall(followUpReq).executeSuspend()
                    val followUpBodyStr = followUpResp.body?.string() ?: ""
                    if (followUpResp.isSuccessful && followUpBodyStr.isNotBlank()) {
                        val followUpJson = JsonParser.parseString(followUpBodyStr).asJsonObject
                        val followUpChoice = followUpJson.getAsJsonArray("choices")?.get(0)?.asJsonObject
                        val followUpMsg = followUpChoice?.getAsJsonObject("message")

                        // ¿El modelo quiso encadenar una segunda herramienta (ej: recall_past_context -> search_nearby_places)?
                        val secondToolCalls = followUpMsg?.getAsJsonArray("tool_calls")
                        if (secondToolCalls != null && secondToolCalls.size() > 0) {
                            val secondAssistantMsg = JsonParser.parseString(followUpMsg.toString()).asJsonObject
                            val secondToolResults = mutableListOf<Map<String, Any?>>()
                            for (elem in secondToolCalls) {
                                val callObj = elem.asJsonObject
                                val callId = callObj.get("id")?.asString ?: "call_2_${System.currentTimeMillis()}"
                                val funcObj = callObj.getAsJsonObject("function") ?: continue
                                val toolName = funcObj.get("name")?.asString ?: continue
                                val argsString = funcObj.get("arguments")?.asString ?: "{}"
                                val argsMap = mutableMapOf<String, Any?>()
                                try {
                                    val parsed = JsonParser.parseString(argsString).asJsonObject
                                    parsed.keySet().forEach { k ->
                                        val item = parsed.get(k)
                                        if (item.isJsonPrimitive) {
                                            val p = item.asJsonPrimitive
                                            argsMap[k] = when {
                                                p.isNumber -> p.asInt
                                                p.isBoolean -> p.asBoolean
                                                else -> p.asString
                                            }
                                        } else argsMap[k] = item.toString()
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error parseando args segunda tool: ${e.message}")
                                }
                                val res = skillRegistry.executeSkill(toolName, argsMap)
                                if (res.spokenFeedback.isNotBlank()) lastSpokenFallback = res.spokenFeedback
                                secondToolResults.add(
                                    mapOf(
                                        "role" to "tool",
                                        "tool_call_id" to callId,
                                        "name" to toolName,
                                        "content" to gson.toJson(mapOf("success" to res.success, "spoken_feedback" to res.spokenFeedback, "data" to res.data))
                                    )
                                )
                            }
                            val finalMessages = mutableListOf<Any>()
                            finalMessages.addAll(followUpMessages)
                            finalMessages.add(secondAssistantMsg)
                            finalMessages.addAll(secondToolResults)

                            val finalBody = """
                            {
                                "model": "$modelToUse",
                                "messages": ${gson.toJson(finalMessages)},
                                "max_tokens": 256,
                                "temperature": 0.5
                            }
                            """.trimIndent()
                            val finalReq = Request.Builder()
                                .url(GROQ_CHAT_URL)
                                .header("Authorization", "Bearer $apiKey")
                                .header("Content-Type", "application/json")
                                .header("User-Agent", "FifoVoiceApp/1.0 (Android; okhttp)")
                                .post(finalBody.toRequestBody("application/json".toMediaType()))
                                .build()
                            val finalResp = fifoHttpClient.newCall(finalReq).executeSuspend()
                            val finalBodyStr = finalResp.body?.string() ?: ""
                            if (finalResp.isSuccessful && finalBodyStr.isNotBlank()) {
                                val fJson = JsonParser.parseString(finalBodyStr).asJsonObject
                                val fChoice = fJson.getAsJsonArray("choices")?.get(0)?.asJsonObject
                                val fMsg = fChoice?.getAsJsonObject("message")
                                val text = fMsg?.get("content")?.run { if (isJsonNull) "" else asString }?.trim() ?: ""
                                if (text.isNotBlank()) {
                                    val reply = com.fifo.voicepipeline.audio.TextSanitizer.cleanForSpeech(text)
                                    conversationHistory.add(mapOf("role" to "assistant", "content" to reply))
                                    return reply
                                }
                            }
                        } else {
                            val finalContent = followUpMsg?.get("content")?.run { if (isJsonNull) "" else asString }?.trim() ?: ""
                            if (finalContent.isNotBlank()) {
                                val reply = com.fifo.voicepipeline.audio.TextSanitizer.cleanForSpeech(finalContent)
                                conversationHistory.add(mapOf("role" to "assistant", "content" to reply))
                                return reply
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error en follow-up con Groq después de tools: ${e.message}")
                }

                if (lastSpokenFallback.isNotBlank()) {
                    val reply = com.fifo.voicepipeline.audio.TextSanitizer.cleanForSpeech(lastSpokenFallback)
                    conversationHistory.add(mapOf("role" to "assistant", "content" to reply))
                    return reply
                }
            }

            val reply = com.fifo.voicepipeline.audio.TextSanitizer.cleanForSpeech(textReply.ifBlank { "Listo, he registrado los cambios." })
            conversationHistory.add(mapOf("role" to "assistant", "content" to reply))
            reply
        } catch (e: Exception) {
            Log.e(TAG, "Error llamando a Groq: ${e.message}", e)
            "Disculpe, hubo un problema de conexión con la API de Groq. Por favor intente nuevamente en unos instantes."
        }
    }

    /**
     * Genera dinámicamente una frase de reanudación cálida, variada y 100% natural
     * cuando el usuario interrumpió diciendo "Fifo" pero luego se quedó en silencio o no dijo una orden.
     */
    override suspend fun generateNaturalResumption(interruptedSentence: String): String {
        val apiKey = effectiveGroqKey
        if (apiKey.isNotBlank() && isGroqActive) {
            try {
                val prompt = """Eres Fifo, asistente afectuoso. Estabas diciendo esto en voz alta:
"$interruptedSentence"
El usuario te interrumpió diciendo "Fifo", te callaste de inmediato para escucharle con atención, pero él no dijo nada más (se quedó en silencio).
Reanuda el habla de manera muy natural, espontánea y cálida (por ejemplo notando amablemente que parecía querer decir algo o preguntar, y retomando tu idea donde quedó).
Genera ÚNICAMENTE 1 oración concisa para hablar en voz alta por voz, sin introducciones ni comillas ni formato."""

                val jsonBody = """
                {
                    "model": "$GROQ_PRIMARY_MODEL",
                    "messages": [
                        {"role": "system", "content": "Eres un asistente de voz humano y espontáneo. Responde solo el texto a decir."},
                        {"role": "user", "content": ${gson.toJson(prompt)}}
                    ],
                    "max_tokens": 80,
                    "temperature": 0.7
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
                if (response.isSuccessful && body.isNotBlank()) {
                    val root = JsonParser.parseString(body).asJsonObject
                    val text = root.getAsJsonArray("choices")?.get(0)?.asJsonObject
                        ?.getAsJsonObject("message")?.get("content")?.asString?.trim() ?: ""
                    if (text.isNotBlank()) {
                        return com.fifo.voicepipeline.audio.TextSanitizer.cleanForSpeech(text)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error generando reanudación natural con LLM: ${e.message}")
            }
        }

        // Generador dinámico conversacional de respaldo con conectores naturales en español
        val connectors = listOf(
            "Vi que quería decir algo... Pero bueno, como le decía: $interruptedSentence",
            "Pensé que me iba a comentar algo. En todo caso, como le iba diciendo: $interruptedSentence",
            "Creí que tenía una duda... Bueno, como le estaba comentando: $interruptedSentence",
            "Sentí que iba a decirme algo, no se preocupe... Como le decía: $interruptedSentence",
            "Ah, creí que me quería preguntar algo. De todos modos, continuando con lo que le decía: $interruptedSentence"
        )
        return connectors.random()
    }

    /**
     * Prueba la conexión del cerebro de IA configurado (Groq LPU).
     */
    suspend fun testConnection(overrideKey: String? = null): Pair<Boolean, String> {
        val key = (overrideKey ?: effectiveGroqKey).trim()
        return testGroqConnection(key)
    }

    /**
     * Prueba la validez de la clave de Groq realizando una consulta mínima de prueba.
     * Retorna Pair(éxito, mensaje descriptivo).
     */
    suspend fun testGroqConnection(overrideKey: String? = null): Pair<Boolean, String> {
        val key = (overrideKey ?: effectiveGroqKey).trim()
        if (key.isEmpty()) {
            return Pair(false, "La clave de Groq no puede estar vacía.")
        }
        if (!key.startsWith("gsk_")) {
            return Pair(false, "La clave de Groq debe comenzar con 'gsk_'.")
        }

        val testBody = """
        {
            "model": "$GROQ_PRIMARY_MODEL",
            "max_tokens": 10,
            "messages": [{"role": "user", "content": "Hola"}]
        }
        """.trimIndent()

        val request = Request.Builder()
            .url(GROQ_CHAT_URL)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .header("User-Agent", "FifoVoiceApp/1.0 (Android; okhttp)")
            .post(testBody.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            val response = fifoHttpClient.newCall(request).executeSuspend()
            val body = response.body?.string() ?: ""
            if (response.isSuccessful) {
                Pair(true, "¡Conexión exitosa con Groq! (Cerebro LPU activo: Qwen 27B)")
            } else {
                val errorMsg = try {
                    val json = JsonParser.parseString(body).asJsonObject
                    json.getAsJsonObject("error")?.get("message")?.asString ?: "Error ${response.code}"
                } catch (e: Exception) {
                    "Error HTTP ${response.code}"
                }
                Pair(false, "Groq rechazó la clave ($errorMsg)")
            }
        } catch (e: Exception) {
            Pair(false, "Fallo de red al conectar con Groq: ${e.message}")
        }
    }

    /**
     * Envía el texto del usuario al cerebro de IA y recibe la respuesta en streaming.
     */
    fun chatStreaming(
        userText: String,
        onChunk: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        if (isGroqActive) {
            chatStreamingGroq(userText, onChunk, onComplete, onError)
        } else {
            onError(IllegalStateException("No hay clave de Groq activa configurada."))
        }
    }

    private fun chatStreamingGroq(
        userText: String,
        onChunk: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        conversationHistory.add(mapOf("role" to "user", "content" to userText))

        val enrichedSystemPrompt = getEnrichedSystemPrompt()

        val messagesList = mutableListOf<Map<String, String>>()
        messagesList.add(mapOf("role" to "system", "content" to enrichedSystemPrompt))
        messagesList.addAll(conversationHistory)
        val messagesJson = gson.toJson(messagesList)

        val jsonBody = """
        {
            "model": "$GROQ_PRIMARY_MODEL",
            "messages": $messagesJson,
            "max_tokens": 512,
            "temperature": 0.6,
            "stream": true
        }
        """.trimIndent()

        val request = Request.Builder()
            .url(GROQ_CHAT_URL)
            .header("Authorization", "Bearer $effectiveGroqKey")
            .header("Content-Type", "application/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        val fullResponse = StringBuilder()
        val sentenceBuffer = StringBuilder()

        val factory = EventSources.createFactory(fifoHttpClient)
        factory.newEventSource(request, object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data == "[DONE]") {
                    if (sentenceBuffer.isNotEmpty()) {
                        onChunk(sentenceBuffer.toString().trim())
                        sentenceBuffer.clear()
                    }
                    val response = fullResponse.toString()
                    conversationHistory.add(mapOf("role" to "assistant", "content" to response))
                    onComplete(response)
                    return
                }

                try {
                    val json = JsonParser.parseString(data).asJsonObject
                    val choices = json.getAsJsonArray("choices") ?: return
                    if (choices.size() == 0) return
                    val delta = choices[0].asJsonObject.getAsJsonObject("delta") ?: return

                    // Solo hablar el contenido real (ignorar razonamiento interno del modelo)
                    val text = delta.get("content")?.run { if (isJsonNull) null else asString } ?: return
                    if (text.isEmpty()) return

                    fullResponse.append(text)
                    sentenceBuffer.append(text)

                    val bufferStr = sentenceBuffer.toString()
                    if (shouldFlush(bufferStr)) {
                        onChunk(bufferStr.trim())
                        sentenceBuffer.clear()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parseando SSE Groq: ${e.message}")
                }
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                val error = t ?: Exception("Error desconocido en stream Groq (HTTP ${response?.code})")
                Log.e(TAG, "Error streaming Groq: ${error.message}")
                onError(error as Exception)
            }
        })
    }

    /**
     * Determina si el buffer de texto debe enviarse al TTS.
     * Busca delimitadores naturales de frase para prosodia correcta.
     */
    private fun shouldFlush(text: String): Boolean {
        if (text.isBlank()) return false
        val trimmed = text.trim()
        // Delimitadores naturales de frase
        if (trimmed.endsWith('.') || trimmed.endsWith(',') ||
            trimmed.endsWith('?') || trimmed.endsWith('!') ||
            trimmed.endsWith(':') || trimmed.endsWith(';') ||
            trimmed.endsWith('…')) {
            return true
        }
        // Si acumulamos muchas palabras sin delimitador, forzar flush
        return trimmed.split("\\s+".toRegex()).size >= 12
    }

    // ═════════════════════════════════════════════════
    //  3. TEXT-TO-SPEECH (OpenAI TTS)
    // ═════════════════════════════════════════════════

    /**
     * Convierte texto a audio PCM usando OpenAI TTS.
     * Retorna PCM crudo (sin header) a 16 kHz, listo para el parlante o el ESP32.
     *
     * @param text Texto a sintetizar
     * @return ByteArray con PCM crudo, o null si falla
     */
    suspend fun textToSpeech(text: String): ByteArray? = speechSynthesis.synthesize(text)

    /** Limpia el historial de conversación. */
    fun clearHistory() {
        conversationHistory.clear()
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
