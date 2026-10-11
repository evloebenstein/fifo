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
    var openAiApiKey: String = "",
    var context: android.content.Context? = null
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
        // Modelo principal de Groq: Qwen 27B (ultra-rápido, con tool calling nativo y baja huella de tokens)
        const val GROQ_PRIMARY_MODEL = "qwen/qwen3.8-27b"
        // Modelo de respaldo si hay congestión: OpenAI GPT-OSS 120B
        const val GROQ_FALLBACK_MODEL = "openai/gpt-oss-120b"
        const val GROQ_WHISPER_MODEL = "whisper-large-v3-turbo"

        private const val CLAUDE_MODEL = "claude-haiku-4-5-20251001"
        private const val WHISPER_MODEL = "whisper-1"
        private const val TTS_MODEL = "tts-1"
        private const val TTS_VOICE = "nova"

        // ── System prompt ultra-compacto del robot Fifo (optimizado para voz y bajo consumo de tokens) ──
        private const val SYSTEM_PROMPT = """Eres Fifo, asistente robótico y compañero empático para adultos mayores (especialista en bienestar y vida diaria).
REGLAS ESTRICTAS DE INTERACCIÓN:
1. Voz concisa: Trata siempre de "usted" con cariño. Responde ÚNICAMENTE en 1 o 2 oraciones breves diseñadas para ser habladas en voz alta por TTS. Sin emojis, sin markdown (*, #), sin "Fifo:".
2. Sin preámbulos: Prohibido decir "Revisé su ubicación en...". Si piden un local o dirección, da directamente el nombre, la calle y la distancia sin rodeos.
3. Ubicación, comercios y navegación en vivo ('search_nearby_places', 'open_navigation_directions'):
   - Usa la ubicación física real para responder comercios reales. NUNCA respondas con el nombre de la comuna o ciudad como si fuera un local o mercado.
   - Variedad y naturalidad humana: Sé variado, espontáneo y cálido. PROHIBIDO usar siempre las mismas frases mecánicas o plantillas fijas. Respeta género y artículo ("el OXXO", "la farmacia", "el supermercado Líder").
   - Acompañante de navegación tipo Waze: Si el usuario pregunta "¿a dónde voy?", "¿y ahora?", "¿por dónde sigo?", usa el bloque de MODO NAVEGACIÓN ACTIVA. NUNCA digas que no te acuerdas ni cambies de tema; guíale directamente indicando su orientación y distancia restante.
   - Plural vs Singular: Si pregunta en plural, ofrece 2 o 3 opciones con sus distancias. Si pregunta en singular, destaca el principal y ofrece guiarle con naturalidad.
4. Comprensión de voz y aclaraciones: Si lo dicho por el usuario suena entrecortado, ininteligible o incoherente, NO inventes respuestas; pide con amabilidad que te repita la frase.
5. Memoria episódica ('recall_past_context'): Si mencionan anécdotas, personas o lugares pasados, consulta 'recall_past_context' antes de responder.
6. Llamadas y robot: 'manage_phone_call' para llamadas, 'find_fifo_device' para hacer sonar el robot, 'read_phone_contacts' para contactos del celular.
7. Internet en vivo ('web_search'): Úsala para noticias del día, clima o información reciente."""

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
                "thank you for watching", "thanks for watching", "thank you",
                "conex", "conexion", "conexion con el robot fifo", "conectro",
                "filin gud", "filin", "you", "bye", "bye bye",
                "musica", "aplausos", "silencio", "continuara",
                "conversacion de bunos", "conversacion", "oh", "ah", "eh", "no", "si", "ok"
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
                "Transcripción clara en español."
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
        while (conversationHistory.size > 8) {
            conversationHistory.removeAt(0)
        }

        val response = if (isGroqActive) {
            chatWithGroq(userText, skillRegistry)
        } else if (anthropicApiKey.isNotBlank() && !anthropicApiKey.startsWith("gsk_")) {
            chatWithClaude(userText, skillRegistry)
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
    suspend fun extractSessionMemory(turns: List<Pair<String, String>>): SessionMemoryConsolidation? {
        if (turns.isEmpty()) return null
        val conversationText = turns.joinToString("\n") { (user, fifo) ->
            "Usuario: $user\nFifo: $fifo"
        }

        val prompt = """
Eres el módulo de consolidación de memoria del asistente Fifo.
Analiza la siguiente conversación de voz reciente entre el usuario y Fifo:

$conversationText

Extrae la información clave para persistirla en la base de datos de memoria densa del asistente, prestando especial atención a: locales comerciales, tiendas, calles, comidas, gustos específicos, personas o lugares visitados o consultados.
Responde ÚNICAMENTE un objeto JSON válido con los siguientes campos:
{
  "primaryTag": "categoría temática corta (ej: Lugares, Comida, Deportes, Rutina, Familia, Salud, General)",
  "keyTopics": ["tema1", "tema2", "comidas o productos"],
  "namedEntities": ["locales, tiendas, personas, calles o lugares específicos"],
  "detectedMood": "ánimo detectado (ej: motivado, tranquilo, contento, pensativo, neutral)",
  "compactSummary": "resumen denso de 1 o 2 oraciones incluyendo nombres exactos de locales, lugares o temas hablados",
  "conversationTitle": "título conciso de la charla (ej: Visita a Panadería Las Rosas, Búsqueda de OXXO)",
  "memoryTitle": "título de un recuerdo nuevo sobre el usuario (o null si solo fue saludo o charla trivial)",
  "memoryDetail": "detalle específico denso del recuerdo aprendido sobre el usuario, locales o gustos (o null si no aplica)",
  "memoryEmoji": "emoji representativo (ej: 📍, 🥐, 🥋, ⭐, 💊, 🏃)",
  "newTaste": "nuevo gusto, comida o interés del usuario mencionado (o null si no hubo)"
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
        val firstName = FifoDataRepository.userProfile.value.fullName.split(" ").firstOrNull { it.isNotBlank() } ?: ""
        val nameExample = if (firstName.isNotBlank()) "'Dígame $firstName'" else "'Dígame'"

        return """
        === CONTEXTO TEMPORAL Y DINÁMICA DE CONVERSACIÓN ===
        - HORA LOCAL EXACTA: $timeFormatted hrs ($period).
        - FECHA ACTUAL: $fullDate.
        - SALUDO ADECUADO POR HORARIO: "$correctGreeting".
        - REGLA ESTRICTA DE SALUDO: NUNCA saludes diciendo "Buenos días" si la hora actual es tarde o noche ($timeFormatted hrs).
        - CONTINUIDAD CONVERSACIONAL: ${if (userTurnsInHistory <= 1 && pastConvsCount == 0) "Es el inicio del contacto con el usuario." else "Ya has hablado $userTurnsInHistory veces con el usuario en esta interacción continua (y tienen $pastConvsCount charlas previas). NO te presentes de nuevo ni repitas un saludo formal en cada turno. Responde de forma cercana, directa y natural (ej: $nameExample, 'Aquí estoy', 'Por supuesto', o respondiendo directamente a la consulta)."}
        """.trimIndent()
    }

    /**
     * Construye el prompt de sistema enriquecido con memoria y estado temporal en tiempo real.
     */
    private fun getEnrichedSystemPrompt(): String {
        val compactContext = FifoDataRepository.buildCompactContextWindow(maxFragments = 2)
        val temporalContext = buildTemporalAndSessionContext()
        val profile = FifoDataRepository.userProfile.value
        val gpsInfo = context?.let { com.fifo.voicepipeline.location.FifoLocationHelper.getCurrentLocation(it) }

        val userContext = buildString {
            appendLine("=== DATOS DEL USUARIO ===")
            if (profile.fullName.isNotBlank()) appendLine("- NOMBRE DEL USUARIO: ${profile.fullName}")
            if (profile.emergencyContactName.isNotBlank()) appendLine("- CONTACTO DE APOYO: ${profile.emergencyContactName} (${profile.emergencyContactPhone})")
            if (profile.preferredAddress.isNotBlank()) appendLine("- DOMICILIO REGISTRADO (CASA): ${profile.preferredAddress}")
        }.trim()

        val locationContext = buildString {
            if (gpsInfo != null && gpsInfo.isGpsActive) {
                appendLine("=== UBICACIÓN EN TIEMPO REAL (GPS ACTIVO) ===")
                appendLine("- UBICACIÓN FÍSICA ACTUAL: ${gpsInfo.address} (Comuna: ${gpsInfo.city})")
                appendLine("- COORDENADAS GPS: ${gpsInfo.latitude}, ${gpsInfo.longitude}")
                appendLine("- REGLA DE ORO DE NAVEGACIÓN Y COMERCIOS: El usuario se encuentra FÍSICAMENTE en ${gpsInfo.city} (${gpsInfo.address}). Al buscar lugares cercanos ('oxxo', 'farmacia', 'minimarket') o dar indicaciones de cómo ir, debes buscar y guiar SIEMPRE desde ${gpsInfo.city}. NUNCA busques en la comuna del domicilio registrado a menos que pida explícitamente 'en mi casa'.")
            } else if (profile.preferredAddress.isNotBlank()) {
                appendLine("=== UBICACIÓN ESTIMADA (DOMICILIO REGISTRADO) ===")
                appendLine("- DIRECCIÓN: ${profile.preferredAddress} (${profile.city})")
            }
        }.trim()

        val navContext = if (gpsInfo != null) {
            com.fifo.voicepipeline.location.FifoNavigationManager.buildSystemPromptContext(gpsInfo)
        } else ""

        return buildString {
            appendLine(SYSTEM_PROMPT)
            appendLine()
            appendLine(userContext)
            if (locationContext.isNotBlank()) {
                appendLine()
                appendLine(locationContext)
            }
            if (navContext.isNotBlank()) {
                appendLine()
                appendLine(navContext)
            }
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
        // Limitar turnos previos a los últimos 6 para no rebasar el límite de ITPM 7000
        val compactHistory = conversationHistory.takeLast(6)
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
            val response = httpClient.newCall(request).executeSuspend()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "Groq error ($modelToUse) ${response.code}: $body")
                // Si el modelo principal de Groq falla, intentar el modelo de respaldo de Groq primero
                if (modelToUse == GROQ_PRIMARY_MODEL) {
                    Log.w(TAG, "Groq: Error ${response.code} en $GROQ_PRIMARY_MODEL. Probando fallback a $GROQ_FALLBACK_MODEL...")
                    if (response.code == 429) {
                        kotlinx.coroutines.delay(800)
                    }
                    return chatWithGroq(userText, skillRegistry, modelToUse = GROQ_FALLBACK_MODEL)
                }

                // Si ambos modelos de Groq fallan y hay clave de Claude configurada
                if (anthropicApiKey.isNotBlank() && !anthropicApiKey.startsWith("gsk_")) {
                    Log.w(TAG, "Groq agotado. Intentando failover a Claude...")
                    val claudeResult = try {
                        chatWithClaude(userText, skillRegistry)
                    } catch (e: Exception) {
                        Log.e(TAG, "Claude failover error: ${e.message}")
                        ""
                    }
                    if (claudeResult.isNotBlank() && !claudeResult.contains("error 401") && !claudeResult.contains("invalid x-api-key")) {
                        return claudeResult
                    }
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
                            "is_plural", "nearby_places"
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
                // Limitar turnos previos a los últimos 4 para ahorrar tokens y no superar el límite de 7000 ITPM
                val compactFollowUpHistory = conversationHistory.takeLast(4)
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

                    val followUpResp = httpClient.newCall(followUpReq).executeSuspend()
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
                            val finalResp = httpClient.newCall(finalReq).executeSuspend()
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

            val toolUseBlocks = contentArray?.filter { it.asJsonObject.get("type")?.asString == "tool_use" } ?: emptyList()

            if (toolUseBlocks.isNotEmpty() && skillRegistry != null) {
                val toolResultBlocks = mutableListOf<Map<String, Any?>>()
                var lastSpokenFallback = ""

                for (element in toolUseBlocks) {
                    val block = element.asJsonObject
                    val toolUseId = block.get("id")?.asString ?: "tool_use_${System.currentTimeMillis()}"
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

                    Log.i(TAG, "Ejecutando tool desde Claude: $toolName con args=$argsMap")
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

                    val toolPayload = mapOf(
                        "success" to result.success,
                        "spoken_feedback" to result.spokenFeedback,
                        "data" to result.data,
                        "additional_data" to result.additionalData
                    )

                    toolResultBlocks.add(
                        mapOf(
                            "type" to "tool_result",
                            "tool_use_id" to toolUseId,
                            "content" to gson.toJson(toolPayload)
                        )
                    )
                }

                // Follow-up a Claude con tool_result blocks
                try {
                    val followUpHistory = mutableListOf<Any>()
                    followUpHistory.addAll(conversationHistory)
                    followUpHistory.add(mapOf("role" to "assistant", "content" to contentArray))
                    followUpHistory.add(mapOf("role" to "user", "content" to toolResultBlocks))

                    val followUpBody = """
                    {
                        "model": "$CLAUDE_MODEL",
                        "max_tokens": 256,
                        "system": ${gson.toJson(enrichedSystemPrompt)},
                        "messages": ${gson.toJson(followUpHistory)}
                    }
                    """.trimIndent()

                    val followUpReq = Request.Builder()
                        .url("https://api.anthropic.com/v1/messages")
                        .header("x-api-key", anthropicApiKey)
                        .header("anthropic-version", "2023-06-01")
                        .header("content-type", "application/json")
                        .post(followUpBody.toRequestBody("application/json".toMediaType()))
                        .build()

                    val followUpResp = httpClient.newCall(followUpReq).executeSuspend()
                    val followUpBodyStr = followUpResp.body?.string() ?: ""
                    if (followUpResp.isSuccessful && followUpBodyStr.isNotBlank()) {
                        val followUpJson = JsonParser.parseString(followUpBodyStr).asJsonObject
                        val respContent = followUpJson.getAsJsonArray("content")
                        var textFollowUp = ""
                        respContent?.forEach { el ->
                            val b = el.asJsonObject
                            if (b.get("type")?.asString == "text") {
                                textFollowUp += b.get("text")?.asString ?: ""
                            }
                        }
                        if (textFollowUp.isNotBlank()) {
                            val reply = com.fifo.voicepipeline.audio.TextSanitizer.cleanForSpeech(textFollowUp)
                            conversationHistory.add(mapOf("role" to "assistant", "content" to reply))
                            return reply
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error en follow-up con Claude después de tools: ${e.message}")
                }

                if (lastSpokenFallback.isNotBlank()) {
                    val reply = com.fifo.voicepipeline.audio.TextSanitizer.cleanForSpeech(lastSpokenFallback)
                    conversationHistory.add(mapOf("role" to "assistant", "content" to reply))
                    return reply
                }
            }

            var textReply = ""
            contentArray?.forEach { element ->
                val block = element.asJsonObject
                if (block.get("type")?.asString == "text") {
                    textReply += block.get("text")?.asString ?: ""
                }
            }

            val reply = com.fifo.voicepipeline.audio.TextSanitizer.cleanForSpeech(textReply.ifBlank { "Listo, he registrado los cambios." })
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
