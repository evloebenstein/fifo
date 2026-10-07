package com.fifo.voicepipeline.network

import android.util.Log
import com.fifo.voicepipeline.audio.AudioConfig
import com.fifo.voicepipeline.data.FifoDataRepository
import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Cliente para las APIs de nube del pipeline de voz:
 * - Whisper (OpenAI) → Speech-to-Text
 * - Claude (Anthropic) → LLM Agent
 * - OpenAI TTS → Text-to-Speech
 *
 * Soporta streaming para minimizar latencia.
 */
class CloudApiClient(
    var groqApiKey: String = "",
    var anthropicApiKey: String = "",
    var openAiApiKey: String = ""
) {
    /**
     * Clave efectiva para Groq: verifica groqApiKey o si openAiApiKey / anthropicApiKey inician con 'gsk_'.
     */
    val effectiveGroqKey: String
        get() = when {
            groqApiKey.trim().startsWith("gsk_") -> groqApiKey.trim()
            openAiApiKey.trim().startsWith("gsk_") -> openAiApiKey.trim()
            anthropicApiKey.trim().startsWith("gsk_") -> anthropicApiKey.trim()
            groqApiKey.isNotBlank() -> groqApiKey.trim()
            else -> ""
        }

    val isGroqActive: Boolean
        get() = effectiveGroqKey.isNotBlank()

    companion object {
        private const val TAG = "CloudApiClient"

        // ── Endpoints ───────────────────────────────
        private const val GROQ_CHAT_URL = "https://api.groq.com/openai/v1/chat/completions"
        private const val GROQ_WHISPER_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        private const val ANTHROPIC_URL = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val WHISPER_URL = "https://api.openai.com/v1/audio/transcriptions"
        private const val TTS_URL = "https://api.openai.com/v1/audio/speech"

        // ── Modelos ──────────────────────────────────
        // Modelo principal de Groq: Qwen 27B (ultra-rápido ~200ms, tools precisas sin token overhead)
        const val GROQ_PRIMARY_MODEL = "qwen/qwen3.8-27b"
        // Modelo de respaldo si hay rate limit: OpenAI GPT-OSS 20B (ligero, veloz ~800ms y con límites altos de RPM)
        const val GROQ_FALLBACK_MODEL = "openai/gpt-oss-20b"
        const val GROQ_WHISPER_MODEL = "whisper-large-v3-turbo"

        private const val CLAUDE_MODEL = "claude-haiku-4-5-20251001"
        private const val WHISPER_MODEL = "whisper-1"
        private const val TTS_MODEL = "tts-1"
        private const val TTS_VOICE = "nova"

        // ── System prompt del robot Fifo ─────────────────
        private const val SYSTEM_PROMPT = """SYSTEM PROMPT: Agente Fifo

1. Identidad y Propósito:
Eres Fifo, especialista en envejecimiento activo y bienestar para adultos mayores. Tu misión es brindarles compañía empática, motivación, apoyo en hobbies y facilitación en su vida diaria.

2. Tono y Personalidad:
- Trata siempre al usuario de "usted" con paciencia, empatía y cariño.
- Usa lenguaje directo, claro y accesible, sin jerga técnica ni palabras complejas.
- Respuestas CONCISAS: Máximo 1 a 2 párrafos breves, diseñados para ser escuchados por voz.
- Una sola pregunta a la vez. No hagas interrogatorios ni monólogos.
- No eres médico: si mencionan dolor o problemas graves, sugiere consultar con su médico.

3. Formato Estricto para Síntesis de Voz (TTS):
- NUNCA uses emojis (😊, 👋, ❤️), markdown (*, **, #, -), ni acotaciones escénicas (*sonríe*, *pausa*).
- NUNCA uses encabezados de diálogo como "Fifo:". Responde únicamente con las palabras que hablarás en voz alta.

4. Privacidad y Seguridad:
- Solo registra intereses, gustos y recuerdos positivos.
- Nunca divulgues datos confidenciales (salud, finanzas). Prohibido contenido de índole sexual.
- Ante abuso o emergencia, escucha con empatía y ofrece contactar a un familiar o al SAMU 131.

5. Memoria Conversacional:
- Los fragmentos de contexto previo son antecedentes: NO menciones temas pasados de la nada si el usuario no los mencionó en la charla actual.
- Responde directamente a la consulta del usuario. Para buscar anécdotas o datos profundos pasados, usa 'recall_past_context'.

6. Ubicación, Navegación y Lugares Cercanos ('search_nearby_places'):
- Guía 100% verbal: Fifo orienta por voz indicando el nombre del local real que está en el mapa, dirección, metros, cuadras y minutos caminando.
- NUNCA abras la pantalla a menos que el usuario lo pida expresamente (ej: "muéstrame en mi celular"). Por defecto, 'open_screen_map' DEBE ser false tanto en 'search_nearby_places' como en 'open_navigation_directions'.
- Consulta los comercios y locales reales del entorno. Si un local figura históricamente registrado como OK Market, aclara que actualmente en Chile opera como OXXO.

7. Saludos, Horarios y Continuidad:
- Adapta tu saludo estrictamente a la hora real del contexto temporal. Jamás digas 'Buenos días' en la tarde o noche.
- Si ya has conversado previamente con el usuario hoy o en esta sesión, NO repitas saludos formales como si recién despertaras; responde de forma directa, cálida y natural (ej: 'Dígame Lucía', 'Aquí estoy', 'Hola de nuevo').

8. Dispositivo Fifo, Teléfono y Hardware:
- Si el usuario perdió el robot o pide que emita un sonido, usa 'find_fifo_device'.
- Para llamadas telefónicas (contestar, colgar, consultar), usa 'manage_phone_call'.
- Para revisar o buscar contactos del celular, usa 'read_phone_contacts'.
- Para controlar hardware (linterna, volumen, batería, hora), usa 'control_device_hardware'.
- Si el usuario pide que sigas escuchando sin decir 'Fifo', confirma con afecto que permanecerás atento en modo continuo.

9. Búsqueda en Internet en Tiempo Real ('web_search'):
- Dispones de la herramienta 'web_search' para consultar internet en vivo (noticias de última hora, clima actual, resultados, hechos de hoy o datos que requieran información reciente).
- Úsala SIEMPRE que el usuario pregunte por noticias de hoy, acontecimientos recientes, clima o cuando solicite buscar/averiguar algo en la web.
- Sintetiza los datos encontrados de manera clara, humana y concisa para ser escuchados por voz.
"""

        fun isWhisperHallucination(rawText: String): Boolean {
            val norm = java.text.Normalizer.normalize(rawText.lowercase().trim(), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            if (norm.isBlank() || norm.length <= 1) return true

            val knownHallucinations = setOf(
                "gracias", "muchas gracias", "gracias por ver", "gracias por ver el video",
                "gracias por ver este video", "gracias por mirar", "gracias por su atencion",
                "subtitulos realizados por la comunidad de amara org",
                "subtitulos por la comunidad de amara org", "amara org", "amara",
                "suscribete", "suscribete al canal", "suscribanse", "dale like y suscribete",
                "dale like", "compartir", "comenta",
                "thank you", "thank you for watching", "thanks for watching",
                "chao", "adios", "bye", "ok", "okay"
            )
            return knownHallucinations.contains(norm)
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

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
    suspend fun transcribe(wavData: ByteArray, language: String = "es"): String {
        val groqKey = effectiveGroqKey
        val openKey = openAiApiKey.trim()

        val (authKey, endpoint, model) = when {
            groqKey.isNotBlank() -> Triple(groqKey, GROQ_WHISPER_URL, GROQ_WHISPER_MODEL)
            openKey.isNotBlank() && !openKey.startsWith("sk-ant-") -> {
                if (openKey.startsWith("gsk_")) {
                    Triple(openKey, GROQ_WHISPER_URL, GROQ_WHISPER_MODEL)
                } else {
                    Triple(openKey, WHISPER_URL, WHISPER_MODEL)
                }
            }
            else -> {
                Log.w(TAG, "No hay API key válida para Whisper (Groq u OpenAI).")
                return "[KEY_STT_FALTANTE]"
            }
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "audio.wav",
                wavData.toRequestBody("audio/wav".toMediaType())
            )
            .addFormDataPart("model", model)
            .addFormDataPart("language", language)
            .addFormDataPart(
                "prompt",
                "Transcripción en español en un ambiente concurrido y ruidoso con gente hablando de fondo. Ignorar murmullos y conversaciones de terceros. Transcribir únicamente la voz principal que habla de cerca al micrófono."
            )
            .build()

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $authKey")
            .post(requestBody)
            .build()

        return try {
            val response = httpClient.newCall(request).executeSuspend()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "Whisper error ($model) ${response.code}: $body")
                return "[Error de transcripción]"
            }

            val json = JsonParser.parseString(body).asJsonObject
            val text = json.get("text")?.asString?.trim() ?: ""
            Log.i(TAG, "Transcripción ($model): '$text'")
            if (isWhisperHallucination(text)) {
                Log.w(TAG, "Alucinación de silencio/ruido de Whisper descartada: '$text'")
                return ""
            }
            text
        } catch (e: Exception) {
            Log.e(TAG, "Error en transcripción: ${e.message}", e)
            "[Error de conexión]"
        }
    }

    // ═════════════════════════════════════════════════
    //  2. LLM AGENT (Groq LPU / Claude)
    // ═════════════════════════════════════════════════

    /**
     * Envía el texto del usuario al cerebro de IA (Groq si está activo, o Claude como alternativa),
     * soportando Function Calling / Tools mediante [FifoSkillRegistry].
     */
    suspend fun chat(
        userText: String,
        skillRegistry: com.fifo.voicepipeline.skills.FifoSkillRegistry? = null
    ): String {
        conversationHistory.add(mapOf("role" to "user", "content" to userText))
        while (conversationHistory.size > 20) {
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
        while (conversationHistory.size > 20) {
            conversationHistory.removeAt(0)
        }
    }

    /**
     * Extrae un resumen estructurado y datos para la memoria de Fifo (SQLite) al finalizar la sesión.
     */
    suspend fun extractSessionMemory(turns: List<Pair<String, String>>): SessionMemoryConsolidation? {
        if (turns.isEmpty()) return null
        val conversationText = turns.joinToString("\n") { (user, fifo) ->
            "Usuario: $user\nFifo: $fifo"
        }

        val prompt = """
Eres el módulo de consolidación de memoria del asistente Fifo.
Analiza la siguiente conversación de voz reciente entre el usuario y Fifo:

$conversationText

Extrae la información clave para persistirla en la base de datos de memoria del asistente.
Responde ÚNICAMENTE un objeto JSON válido con los siguientes campos:
{
  "primaryTag": "categoría temática corta (ej: Deportes, Lugares, Rutina, Familia, Salud, General)",
  "keyTopics": ["tema1", "tema2"],
  "namedEntities": ["entidades, nombres o lugares relevantes"],
  "detectedMood": "ánimo detectado (ej: motivado, tranquilo, contento, pensativo, neutral)",
  "compactSummary": "resumen breve de 1 o 2 oraciones de lo que se habló",
  "conversationTitle": "título conciso de la charla (ej: Plan de jiujitsu semanal, Búsqueda de OXXO cercano)",
  "memoryTitle": "título de un recuerdo nuevo sobre el usuario (o null si solo fue saludo o charla trivial)",
  "memoryDetail": "detalle específico del recuerdo aprendido sobre el usuario (o null si no aplica)",
  "memoryEmoji": "emoji representativo (ej: 🥋, 📍, ⭐, 💊, 🏃)",
  "newTaste": "nuevo gusto o interés del usuario mencionado (ej: jiujitsu, panadería, caminata) o null si no hubo"
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

                val response = httpClient.newCall(request).executeSuspend()
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val root = JsonParser.parseString(body).asJsonObject
                    root.getAsJsonArray("choices")?.get(0)?.asJsonObject
                        ?.getAsJsonObject("message")?.get("content")?.asString
                } else null
            } catch (e: Exception) {
                Log.w(TAG, "Error extrayendo memoria de sesión en Groq: ${e.message}")
                null
            }
        } else null

        if (jsonResponse.isNullOrBlank()) {
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
                newTaste = newTaste
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parseando JSON de consolidación: ${e.message}", e)
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

        return """
        === CONTEXTO TEMPORAL Y DINÁMICA DE CONVERSACIÓN ===
        - HORA LOCAL EXACTA: $timeFormatted hrs ($period).
        - FECHA ACTUAL: $fullDate.
        - SALUDO ADECUADO POR HORARIO: "$correctGreeting".
        - REGLA ESTRICTA DE SALUDO: NUNCA saludes diciendo "Buenos días" si la hora actual es tarde o noche ($timeFormatted hrs).
        - CONTINUIDAD CONVERSACIONAL: ${if (userTurnsInHistory <= 1 && pastConvsCount == 0) "Es el inicio del contacto con el usuario." else "Ya has hablado $userTurnsInHistory veces con el usuario en esta interacción continua (y tienen $pastConvsCount charlas previas). NO te presentes de nuevo ni repitas un saludo formal en cada turno. Responde de forma cercana, directa y natural (ej: 'Dígame Lucía', 'Aquí estoy', 'Por supuesto', o respondiendo directamente a la consulta)."}
        """.trimIndent()
    }

    /**
     * Construye el prompt de sistema enriquecido con memoria y estado temporal en tiempo real.
     */
    private fun getEnrichedSystemPrompt(): String {
        val compactContext = FifoDataRepository.buildCompactContextWindow(maxFragments = 5)
        val temporalContext = buildTemporalAndSessionContext()
        return buildString {
            appendLine(SYSTEM_PROMPT)
            appendLine()
            appendLine(temporalContext)
            if (compactContext.isNotBlank()) {
                appendLine()
                appendLine(compactContext)
            }
        }.trim()
    }

    /**
     * Motor conversacional principal con Groq LPU (gratuito, ultrarrápido).
     * Utiliza Qwen 27B con failover automático a GPT-OSS 120B y Claude.
     */
    private suspend fun chatWithGroq(
        userText: String,
        skillRegistry: com.fifo.voicepipeline.skills.FifoSkillRegistry?,
        modelToUse: String = GROQ_PRIMARY_MODEL
    ): String {
        val apiKey = effectiveGroqKey
        val enrichedSystemPrompt = getEnrichedSystemPrompt()

        val messagesList = mutableListOf<Map<String, String>>()
        messagesList.add(mapOf("role" to "system", "content" to enrichedSystemPrompt))
        messagesList.addAll(conversationHistory)

        val messagesJson = gson.toJson(messagesList)
        val toolsFragment = if (skillRegistry != null) {
            """, "tools": ${skillRegistry.getOpenAiToolsJson()}, "tool_choice": "auto""""
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
            val response = httpClient.newCall(request).executeSuspend()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "Groq error ($modelToUse) ${response.code}: $body")
                // Si el modelo principal falla por rate-limit (429) o error temporal (5xx), intentar con el modelo de respaldo de Groq
                if (modelToUse == GROQ_PRIMARY_MODEL) {
                    Log.w(TAG, "Groq: Activando fallback a $GROQ_FALLBACK_MODEL...")
                    if (response.code == 429) {
                        kotlinx.coroutines.delay(1200)
                    }
                    return chatWithGroq(userText, skillRegistry, modelToUse = GROQ_FALLBACK_MODEL)
                }
                // Si el modelo de respaldo de Groq también falla, intentar failover a Claude si hay clave disponible
                if (anthropicApiKey.isNotBlank() && !anthropicApiKey.startsWith("gsk_")) {
                    Log.w(TAG, "Groq falló con código ${response.code}, intentando failover a Claude...")
                    return chatWithClaude(userText, skillRegistry)
                }
                if (response.code == 429) {
                    return "Disculpe, el servicio de voz está recibiendo muchas consultas en este instante. Por favor espere un momento e intente preguntarme de nuevo."
                }
                return "Disculpe, hubo un inconveniente con la API de Groq (error ${response.code}). Por favor intente nuevamente en unos instantes."
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
                for (toolElement in toolCalls) {
                    val callObj = toolElement.asJsonObject
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
                            spokenFeedback = "He registrado la acción en su teléfono."
                        )
                    }
                    if (result.spokenFeedback.isNotBlank()) {
                        toolFeedback = if (toolFeedback.isBlank()) result.spokenFeedback else "$toolFeedback ${result.spokenFeedback}"
                    }
                }
            }

            val combinedReply = when {
                toolFeedback.isNotBlank() && textReply.isNotBlank() -> {
                    val tLower = textReply.lowercase().trim()
                    val fLower = toolFeedback.lowercase().trim()
                    if (fLower.contains(tLower) || tLower.contains(fLower) || toolFeedback.length > 25 ||
                        tLower.contains("celular") || tLower.contains("pantalla") || tLower.contains("mapa") || tLower.contains("direcciones")) {
                        toolFeedback.trim()
                    } else {
                        "$textReply $toolFeedback".trim()
                    }
                }
                toolFeedback.isNotBlank() -> toolFeedback.trim()
                textReply.isNotBlank() -> textReply.trim()
                else -> "Listo, he registrado los cambios."
            }

            val reply = com.fifo.voicepipeline.audio.TextSanitizer.cleanForSpeech(combinedReply)
            conversationHistory.add(mapOf("role" to "assistant", "content" to reply))
            reply
        } catch (e: Exception) {
            Log.e(TAG, "Error llamando a Groq: ${e.message}", e)
            if (modelToUse == GROQ_PRIMARY_MODEL) {
                Log.w(TAG, "Groq: Error de red con $modelToUse, probando fallback a $GROQ_FALLBACK_MODEL...")
                return chatWithGroq(userText, skillRegistry, modelToUse = GROQ_FALLBACK_MODEL)
            }
            if (anthropicApiKey.isNotBlank() && !anthropicApiKey.startsWith("gsk_")) {
                Log.w(TAG, "Groq falló por excepción, intentando failover a Claude...")
                return chatWithClaude(userText, skillRegistry)
            }
            "Disculpe, hubo un problema de conexión con la API de Groq. Por favor intente nuevamente en unos instantes."
        }
    }

    /**
     * Motor conversacional secundario / alternativo con Anthropic Claude.
     */
    private suspend fun chatWithClaude(
        userText: String,
        skillRegistry: com.fifo.voicepipeline.skills.FifoSkillRegistry?
    ): String {
        val messagesJson = gson.toJson(conversationHistory)
        val toolsFragment = if (skillRegistry != null) {
            """, "tools": ${skillRegistry.getAnthropicToolsJson()}"""
        } else {
            ""
        }

        val enrichedSystemPrompt = getEnrichedSystemPrompt()

        val jsonBody = """
        {
            "model": "$CLAUDE_MODEL",
            "max_tokens": 384,
            "system": ${gson.toJson(enrichedSystemPrompt)},
            "messages": $messagesJson$toolsFragment
        }
        """.trimIndent()

        val request = Request.Builder()
            .url(ANTHROPIC_URL)
            .header("x-api-key", anthropicApiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("content-type", "application/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            val response = httpClient.newCall(request).executeSuspend()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "Claude error ${response.code}: $body")
                return "Disculpa, tuve un problema al conectarme con Claude. Intenta de nuevo."
            }

            val json = JsonParser.parseString(body).asJsonObject
            val contentArray = json.getAsJsonArray("content")

            var textReply = ""
            var toolFeedback = ""

            contentArray?.forEach { element ->
                val block = element.asJsonObject
                val type = block.get("type")?.asString
                if (type == "text") {
                    textReply += block.get("text")?.asString ?: ""
                } else if (type == "tool_use" && skillRegistry != null) {
                    val toolName = block.get("name")?.asString ?: ""
                    val toolInput = block.getAsJsonObject("input")
                    val argsMap = mutableMapOf<String, Any?>()
                    toolInput?.keySet()?.forEach { key ->
                        val elementVal = toolInput.get(key)
                        if (elementVal.isJsonPrimitive) {
                            val prim = elementVal.asJsonPrimitive
                            argsMap[key] = when {
                                prim.isNumber -> prim.asInt
                                prim.isBoolean -> prim.asBoolean
                                else -> prim.asString
                            }
                        } else {
                            argsMap[key] = elementVal.toString()
                        }
                    }
                    val result = skillRegistry.executeSkill(toolName, argsMap)
                    if (result.spokenFeedback.isNotBlank()) {
                        toolFeedback = if (toolFeedback.isBlank()) result.spokenFeedback else "$toolFeedback ${result.spokenFeedback}"
                    }
                }
            }

            val combinedReply = when {
                toolFeedback.isNotBlank() && textReply.isNotBlank() -> {
                    val tLower = textReply.lowercase().trim()
                    val fLower = toolFeedback.lowercase().trim()
                    if (fLower.contains(tLower) || tLower.contains(fLower) || toolFeedback.length > 25) {
                        toolFeedback.trim()
                    } else {
                        "$textReply $toolFeedback".trim()
                    }
                }
                toolFeedback.isNotBlank() -> toolFeedback.trim()
                textReply.isNotBlank() -> textReply.trim()
                else -> "Listo, he registrado los cambios."
            }

            val reply = com.fifo.voicepipeline.audio.TextSanitizer.cleanForSpeech(combinedReply)
            conversationHistory.add(mapOf("role" to "assistant", "content" to reply))
            reply
        } catch (e: Exception) {
            Log.e(TAG, "Error llamando a Claude: ${e.message}", e)
            "Hubo un error de conexión con el servidor. Intenta de nuevo."
        }
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
            val response = httpClient.newCall(request).executeSuspend()
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
     * Prueba la validez de la clave de Claude realizando una consulta mínima de prueba.
     * Retorna Pair(éxito, mensaje descriptivo).
     */
    suspend fun testAnthropicConnection(overrideKey: String? = null): Pair<Boolean, String> {
        val key = (overrideKey ?: anthropicApiKey).trim()
        if (key.isEmpty()) {
            return Pair(false, "La clave de Claude no puede estar vacía.")
        }
        if (!key.startsWith("sk-ant-")) {
            return Pair(false, "La clave debe comenzar con 'sk-ant-'.")
        }

        val testBody = """
        {
            "model": "$CLAUDE_MODEL",
            "max_tokens": 10,
            "messages": [{"role": "user", "content": "Hola"}]
        }
        """.trimIndent()

        val request = Request.Builder()
            .url(ANTHROPIC_URL)
            .header("x-api-key", key)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("content-type", "application/json")
            .post(testBody.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            val response = httpClient.newCall(request).executeSuspend()
            val body = response.body?.string() ?: ""
            if (response.isSuccessful) {
                Pair(true, "¡Conexión exitosa con Claude!")
            } else {
                val errorMsg = try {
                    val json = JsonParser.parseString(body).asJsonObject
                    json.getAsJsonObject("error")?.get("message")?.asString ?: "Error ${response.code}"
                } catch (e: Exception) {
                    "Error HTTP ${response.code}"
                }
                Pair(false, "Claude rechazó la clave ($errorMsg)")
            }
        } catch (e: Exception) {
            Pair(false, "Fallo de red: ${e.message}")
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
            chatStreamingClaude(userText, onChunk, onComplete, onError)
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

        val factory = EventSources.createFactory(httpClient)
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

    private fun chatStreamingClaude(
        userText: String,
        onChunk: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        conversationHistory.add(mapOf("role" to "user", "content" to userText))

        val messagesJson = gson.toJson(conversationHistory)

        val enrichedSystemPrompt = getEnrichedSystemPrompt()

        val jsonBody = """
        {
            "model": "$CLAUDE_MODEL",
            "max_tokens": 256,
            "system": ${gson.toJson(enrichedSystemPrompt)},
            "messages": $messagesJson,
            "stream": true
        }
        """.trimIndent()

        val request = Request.Builder()
            .url(ANTHROPIC_URL)
            .header("x-api-key", anthropicApiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("content-type", "application/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        val fullResponse = StringBuilder()
        val sentenceBuffer = StringBuilder()

        val factory = EventSources.createFactory(httpClient)
        factory.newEventSource(request, object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data == "[DONE]") return

                try {
                    val json = JsonParser.parseString(data).asJsonObject
                    val eventType = json.get("type")?.asString ?: return

                    when (eventType) {
                        "content_block_delta" -> {
                            val delta = json.getAsJsonObject("delta")
                            val text = delta?.get("text")?.asString ?: return

                            fullResponse.append(text)
                            sentenceBuffer.append(text)

                            val bufferStr = sentenceBuffer.toString()
                            if (shouldFlush(bufferStr)) {
                                onChunk(bufferStr.trim())
                                sentenceBuffer.clear()
                            }
                        }
                        "message_stop" -> {
                            if (sentenceBuffer.isNotEmpty()) {
                                onChunk(sentenceBuffer.toString().trim())
                                sentenceBuffer.clear()
                            }

                            val response = fullResponse.toString()
                            conversationHistory.add(
                                mapOf("role" to "assistant", "content" to response)
                            )
                            onComplete(response)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parseando SSE: ${e.message}")
                }
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                val error = t ?: Exception("Error desconocido (HTTP ${response?.code})")
                Log.e(TAG, "Error streaming Claude: ${error.message}")
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
     * Retorna PCM crudo (sin header) a 24kHz 16-bit mono.
     *
     * @param text Texto a sintetizar
     * @return ByteArray con PCM crudo, o null si falla
     */
    suspend fun textToSpeech(text: String): ByteArray? {
        if (text.isBlank()) return null
        if (openAiApiKey.isEmpty()) {
            Log.w(TAG, "No hay API key de OpenAI — TTS no disponible")
            return null
        }

        val jsonBody = """
        {
            "model": "$TTS_MODEL",
            "input": ${gson.toJson(text)},
            "voice": "$TTS_VOICE",
            "response_format": "pcm",
            "speed": 1.0
        }
        """.trimIndent()

        val request = Request.Builder()
            .url(TTS_URL)
            .header("Authorization", "Bearer $openAiApiKey")
            .header("Content-Type", "application/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            val response = httpClient.newCall(request).executeSuspend()
            if (!response.isSuccessful) {
                Log.e(TAG, "TTS error ${response.code}: ${response.body?.string()}")
                return null
            }

            val pcm24k = response.body?.bytes() ?: return null
            Log.i(TAG, "TTS generó ${pcm24k.size} bytes de audio PCM")

            // Resample de 24kHz → 16kHz para el ESP32
            resample24kTo16k(pcm24k)
        } catch (e: Exception) {
            Log.e(TAG, "Error en TTS: ${e.message}", e)
            null
        }
    }

    /**
     * Resample simple de 24kHz → 16kHz.
     * Toma 2 de cada 3 muestras (ratio 2:3).
     * Para calidad de voz conversacional, esto es suficiente.
     */
    private fun resample24kTo16k(pcm24k: ByteArray): ByteArray {
        val shortBuffer = ByteBuffer.wrap(pcm24k)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()

        val inputSamples = shortBuffer.remaining()
        val outputSamples = (inputSamples * 2) / 3
        val output = ByteBuffer.allocate(outputSamples * 2)
            .order(ByteOrder.LITTLE_ENDIAN)

        val shortOut = output.asShortBuffer()

        // Interpolación lineal simple
        var srcPos = 0.0
        val ratio = 24000.0 / 16000.0  // 1.5

        for (i in 0 until outputSamples) {
            val idx = srcPos.toInt()
            if (idx >= inputSamples - 1) break

            val frac = srcPos - idx
            val s0 = shortBuffer.get(idx).toInt()
            val s1 = shortBuffer.get(idx + 1).toInt()
            val interpolated = (s0 + (s1 - s0) * frac).toInt().toShort()

            shortOut.put(interpolated)
            srcPos += ratio
        }

        return output.array().copyOf(shortOut.position() * 2)
    }

    /** Limpia el historial de conversación. */
    fun clearHistory() {
        conversationHistory.clear()
    }

    /** Extensión para ejecutar OkHttp calls de forma suspendible. */
    private suspend fun Call.executeSuspend(): Response {
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    continuation.resumeWith(Result.success(response))
                }
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.failure(e))
                }
            })
            continuation.invokeOnCancellation { cancel() }
        }
    }
}

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
    val newTaste: String? = null
)
