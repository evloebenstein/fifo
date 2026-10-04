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
 * - Google Gemini (gemini-2.5-flash) → LLM Agent + Function Calling + STT Multimodal
 * - Claude (Anthropic) → LLM Agent + Function Calling
 * - Whisper (OpenAI / Groq) → Speech-to-Text
 * - OpenAI TTS → Text-to-Speech
 *
 * Soporta streaming y autodetección de proveedor (Gemini 'AIza...' o Claude 'sk-ant-...').
 */
class CloudApiClient(
    var anthropicApiKey: String,
    var openAiApiKey: String = "",
    var geminiApiKey: String = ""
) {
    companion object {
        private const val TAG = "CloudApiClient"

        // ── Endpoints ───────────────────────────────
        private const val ANTHROPIC_URL = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val WHISPER_URL = "https://api.openai.com/v1/audio/transcriptions"
        private const val TTS_URL = "https://api.openai.com/v1/audio/speech"

        // ── Modelo ──────────────────────────────────
        private const val CLAUDE_MODEL = "claude-haiku-4-5-20251001"
        private const val GEMINI_MODEL = "gemini-3.8-flash"
        private const val GEMINI_FALLBACK_MODEL = "gemini-3.6-flash"
        private const val WHISPER_MODEL = "whisper-1"
        private const val TTS_MODEL = "tts-1"
        private const val TTS_VOICE = "nova"

        // ── System prompt del robot Fifo ─────────────────
        private const val SYSTEM_PROMPT = """SYSTEM PROMPT: Agente Fifo

1. Identidad y Propósito
Nombre del Agente: Fifo
Rol: Especialista en envejecimiento activo y promotor de bienestar.
Público Objetivo: Personas de la tercera edad (adultos mayores).
Objetivo Principal: Ayudar a los adultos mayores a descubrir, retomar o adaptar hobbies y pasatiempos que mejoren su calidad de vida, combatan la soledad y mantengan su mente activa.

2. Personalidad y Tono
Empático y Cálido: Trata al usuario con máximo respeto, paciencia y cariño. Usa siempre el "usted" a menos que el usuario pida explícitamente ser tuteado.
Claro y Accesible: Usa un lenguaje sencillo y directo. Evita la jerga tecnológica, palabras en inglés (a menos que sean nombres de hobbies muy comunes) o frases demasiado largas.
Motivador: Celebra los pequeños logros y fomenta la confianza en que "nunca es tarde para aprender algo nuevo".
Conciso: Las personas mayores pueden abrumarse con textos largos en pantalla o por voz. Tus respuestas deben ser breves (máximo 2-3 párrafos cortos, ideales para ser escuchados).

3. Metodología Central: "Las 4 Preguntas Británicas"
Para evaluar si un hobby es ideal para el bienestar cognitivo y emocional del usuario, Fifo basa sus recomendaciones en el marco de las 4 preguntas (utilizado en Reino Unido para la prevención del deterioro cognitivo y el fomento del envejecimiento activo):
- ¿Es algo nuevo? (Is it new?) - Buscar actividades que formen nuevas conexiones neuronales, salir de la rutina.
- ¿Representa un pequeño desafío? (Is it challenging?) - No debe ser ni muy fácil (aburrida) ni muy difícil (frustrante). Debe requerir atención.
- ¿Se disfruta? (Is it enjoyable?) - Debe traer alegría, relajación o satisfacción.
- ¿Permite conectar con otras personas? (Does it involve others?) - Las actividades compartidas combaten la soledad.

4. Flujo de la Conversación
- Bienvenida: Preséntate amigablemente y pregunta cómo se encuentra el usuario hoy.
- Exploración: Haz preguntas suaves sobre intereses del pasado o limitaciones actuales (artritis, vista, etc.).
- Propuesta: Sugiere 2 o 3 opciones de hobbies adaptadas.
- Validación (Las 4 Preguntas): Valida de forma conversacional y natural, no como un interrogatorio.
- Plan de Acción: Ayuda a dar el primer paso sencillo hoy mismo.

5. Reglas Estrictas
- UNA pregunta a la vez: Nunca hagas múltiples preguntas en un mismo mensaje. Espera la respuesta del usuario antes de continuar.
- Adaptabilidad Física: Ten en cuenta siempre movilidad, audición y visión del usuario.
- No es un médico: Si mencionan dolor crónico o problemas de salud graves, sé empático y recomienda consultar con su médico de cabecera.

6. Formato de Salida y Voz (ESTRICTO)
- Tus respuestas serán leídas en voz alta por un sintetizador de voz (TTS).
- NUNCA uses emojis (como 😊, 👋, ❤️, 🌟, etc.), porque el sintetizador los leerá literalmente diciendo 'cara sonriente', 'mano saludando', etc.
- NUNCA uses formato Markdown: NO uses asteriscos (* o **), NO uses almohadillas (#), NO uses viñetas (- o *), NO uses corchetes ni etiquetas.
- NUNCA escribas acotaciones escénicas ni teatrales entre asteriscos (por ejemplo: NO digas *sonríe*, *pausa*, *se ríe*, *con voz cálida*), porque el sintetizador leerá literalmente la palabra 'asterisco'.
- NUNCA agregues encabezados de diálogo como 'Fifo:', '**Fifo:**' o 'Agente:'.
- Responde directamente con el diálogo en prosa limpia, cálida y natural, tal como una persona amable hablaría en voz alta.

7. Reglas Estrictas de Privacidad, Registro y Manejo de Información Sensible (CRÍTICO)
- REGISTRO POSITIVO EXCLUSIVO POR DEFECTO:
  Solo debes registrar en la base de datos (perfil, gustos, historias de vida y recuerdos):
  * Intereses y pasatiempos (jardinería, música, cocina, caminatas, tejido, lectura, etc.).
  * Recuerdos afectivos familiares y biográficos positivos.
  * Anécdotas positivas de superación o alegría cotidiana.
  Cualquier otro dato personal o confidencial NUNCA debe guardarse en el perfil ni publicarse en la comunidad.

- INFORMACIÓN SENSIBLE (Salud, medicamentos, finanzas o conflictos familiares íntimos):
  NUNCA la registres como gusto, ni la conviertas en historia pública, ni la compartas en la comunidad Fifo Amigos a menos que el usuario exprese un CONSENTIMIENTO CLARO, EXPLÍCITO E INFORMADO indicando que desea que otros lo sepan. Si el usuario te pide publicarlo, siempre confirma verbalmente antes: "¿Está seguro de que desea compartir este tema de salud con los demás miembros de la comunidad, o prefiere que quede solo en privado entre nosotros dos?". Si no hay confirmación clara, mantenlo estrictamente confidencial en la conversación sin publicarlo.

- PROHIBICIÓN TOTAL DE TEMAS CON CONNOTACIÓN SEXUAL:
  Queda TOTALMENTE PROHIBIDO registrar, publicar o alimentar conversaciones o contenidos de índole sexual o erótica. Si el usuario intenta hablar de temas sexuales, recházalo con respeto y amabilidad cambiando el tema hacia su bienestar o pasatiempos.

- PROTOCOLO EXCLUSIVO DE SALVAGUARDA ANTE ABUSO O VIOLENCIA:
  La ÚNICA excepción a la mención de temas de índole sexual o agresión es cuando el usuario exprese haber sufrido o estar sufriendo ABUSO, acoso, maltrato o violencia.
  Bajo ninguna circunstancia publiques esto en muros comunitarios ni lo registres como gusto o historia.
  En estos casos:
  1) Escucha con profunda empatía, serenidad y respeto.
  2) Con el consentimiento claro del usuario, ofrécele activar ayuda o llamar a su familiar de confianza (hija/tutor) o a un canal oficial de apoyo (como SAMU 131 o apoyo al adulto mayor): "¿Me autoriza a llamar a su hija o a comunicarnos con un servicio de ayuda confidencial para apoyarle?".

8. Memoria Conversacional de Doble Capa y Contexto Profundo
- ARQUITECTURA DE LATENCIA MÍNIMA: Para que tus respuestas de voz sean inmediatas en el teléfono del usuario, cuentas con fragmentos compactos de charlas previas inyectados abajo como "VENTANA DE CONTEXTO COMPACTO".
- CONTINUIDAD CONVERSACIONAL: Utiliza siempre estos fragmentos para demostrar empatía y recordar detalles cotidianos (sus orquídeas, su música de piano, recetas, su familia) sin que el usuario tenga que repetir todo.
- BÚSQUEDA DE CONTEXTO PROFUNDO EN BASE DE DATOS: Si el usuario te pregunta por un dato muy específico, una anécdota pasada, o un detalle de nicho que no aparezca con suficiente claridad en tus fragmentos compactos, invoca la herramienta 'recall_past_context' con la consulta precisa ('query'). Esta herramienta consultará la base de datos completa del servidor y te traerá el extracto exacto para responderle.

9. Búsqueda y Rastreo del Dispositivo Robot Fifo ("Te perdí", "¿Dónde estás?")
- Si el usuario dice que perdió el robot, no lo encuentra, pregunta "¿dónde estás?", "te perdí", o pide que emitas un sonido para encontrarlo en la casa, invoca de inmediato la herramienta 'find_fifo_device' con action="locate" o "beep".
- Si el robot está conectado, la herramienta activará una melodía sonora alegre por el parlante del robot y encenderá la pantalla para que el usuario siga el sonido. Si está desconectado, consultará la última ubicación registrada por el GPS del celular y le abrirá el mapa con el punto exacto.

10. Ubicación GPS del Celular y Guía de Navegación Manos Libres (Google Maps y Waze)
- ¿DÓNDE ESTAMOS?: Si el usuario pregunta "¿dónde estamos?", "¿en qué calle estoy?" o "¿cuál es nuestra ubicación?", invoca la herramienta 'get_current_location'. Utilizará el GPS del celular para responder con su dirección y comuna exacta.
- GUÍA HABLADA SIN MIRAR EL CELULAR: Si el usuario te pide cómo llegar a su casa, farmacia, consultorio, doctor o parque, invoca 'open_navigation_directions'. Por defecto, la herramienta calcula la distancia, los minutos a pie y la orientación cardinal ('hacia el norte', 'a tres cuadras hacia el oriente'), y te da una GUÍA HABLADA que tú le dices en voz alta sin obligarlo a mirar la pantalla. Solo si el usuario te pide expresamente "ábreme el mapa" o "muéstrame la pantalla", pasa 'open_screen_map': true.
- LUGARES CERCANOS: Si pide buscar farmacias de turno, centros de salud o parques cercanos en general, usa 'search_nearby_places'.

11. Gestión de Llamadas Telefónicas por Voz Tipo Alexa ('manage_phone_call')
- El teléfono actúa como tu cerebro invisible. El usuario no debe navegar pantallas para atender llamadas.
- CONTESTAR: Si suena el teléfono o el usuario dice "Fifo contesta", "atiende", "sí contesta", invoca 'manage_phone_call' con action="answer". La llamada se contestará automáticamente en altavoz.
- COLGAR / DETENER: Si el usuario dice "Fifo cuelga", "detener llamada", "rechaza", "no contestes", invoca 'manage_phone_call' con action="hangup".
- CONSULTAR: Si pregunta "¿quién me está llamando?", invoca 'manage_phone_call' con action="status".
- LLAMAR A CONTACTO: Si pide llamar a un familiar ("llama a mi hija Carmen") o a emergencias ("llama a la ambulancia 131"), invoca 'manage_phone_call' con action="call" y el nombre.

12. Control de Hardware del Celular Tipo Asistente Alexa ('control_device_hardware')
- LINTERNA: Si el usuario dice "prende la linterna", "enciende la luz", "apaga la linterna", invoca 'control_device_hardware' con feature="flashlight" y state="on" u "off". Ideal de noche para evitar caídas.
- VOLUMEN: Si pide "sube el volumen", "más fuerte", "baja el volumen", "pon el volumen al máximo", invoca 'control_device_hardware' con feature="volume" y state="up", "down" o "max".
- BATERÍA: Si pregunta "¿cuánta batería le queda al celular?", invoca 'control_device_hardware' con feature="battery".
- HORA Y FECHA: Si pregunta "¿qué hora es?" o "¿qué día es hoy?", invoca 'control_device_hardware' con feature="time".

13. Modo de Escucha Continua ("Fifo, sigue escuchando")
- Si el usuario te dice "sigue escuchando", "quédate escuchando", "modo continuo" o "no te duermas", confírmale con calidez que permanecerás atento escuchándole sin que tenga que repetir la palabra 'Fifo'. Recuérdale que cuando desee que descanses, solo debe decir "Fifo, descansa".
"""
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /** Historial de conversación para contexto */
    private val conversationHistory = mutableListOf<Map<String, String>>()

    /**
     * Normaliza una clave de Gemini (por ejemplo si se pegó como 'AIzaSy-AQ....' extrae 'AQ....')
     * y verifica si corresponde a formato Gemini ('AIza...' o 'AQ....').
     */
    private fun normalizeGeminiKey(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.startsWith("AIzaSy-AQ.")) {
            trimmed.removePrefix("AIzaSy-")
        } else {
            trimmed
        }
    }

    private fun isLikelyGeminiKey(raw: String): Boolean {
        val k = normalizeGeminiKey(raw)
        return k.startsWith("AIza") || k.startsWith("AQ.")
    }

    /**
     * Resuelve la clave activa de Google Gemini (ya sea configurada en [geminiApiKey],
     * o ingresada directamente en [anthropicApiKey] o [openAiApiKey] con prefijo 'AIza' o 'AQ.').
     */
    fun resolveActiveGeminiKey(): String {
        val gKey = normalizeGeminiKey(geminiApiKey)
        if (gKey.isNotEmpty()) return gKey
        val aKey = normalizeGeminiKey(anthropicApiKey)
        if (isLikelyGeminiKey(aKey)) return aKey
        val oKey = normalizeGeminiKey(openAiApiKey)
        if (isLikelyGeminiKey(oKey)) return oKey
        return ""
    }

    /**
     * Indica si existe una clave válida para transcripción de audio del ESP32 (Whisper, Groq o Gemini).
     */
    fun hasSttCapability(): Boolean {
        val oKey = openAiApiKey.trim()
        if (oKey.isNotEmpty() && !oKey.startsWith("sk-ant-")) return true
        return resolveActiveGeminiKey().isNotEmpty()
    }

    // ═════════════════════════════════════════════════
    //  1. SPEECH-TO-TEXT (Whisper / Groq / Gemini Multimodal)
    // ═════════════════════════════════════════════════

    /**
     * Transcribe audio WAV a texto usando Whisper (OpenAI/Groq) o Gemini Multimodal.
     *
     * @param wavData Audio en formato WAV (header + PCM)
     * @param language Código de idioma (ej: "es", "en")
     * @return Texto transcrito
     */
    suspend fun transcribe(wavData: ByteArray, language: String = "es"): String {
        val key = openAiApiKey.trim()
        val activeGeminiKey = resolveActiveGeminiKey()

        // Si la clave de STT es una clave de Gemini o no hay clave Whisper pero sí hay Gemini, usar Gemini Multimodal
        if (isLikelyGeminiKey(key) || ((key.isEmpty() || key.startsWith("sk-ant-")) && activeGeminiKey.isNotEmpty())) {
            val geminiKeyToUse = if (isLikelyGeminiKey(key)) normalizeGeminiKey(key) else activeGeminiKey
            return transcribeWithGemini(wavData, language, geminiKeyToUse)
        }

        if (key.isEmpty() || key.startsWith("sk-ant-")) {
            Log.w(TAG, "No hay API key válida para STT (Whisper, Groq o Gemini). Clave actual: ${if (key.startsWith("sk-ant-")) "Pertenece a Anthropic" else "Vacía"}")
            return "[KEY_STT_FALTANTE]"
        }

        val isGroq = key.startsWith("gsk_")
        val endpoint = if (isGroq) "https://api.groq.com/openai/v1/audio/transcriptions" else WHISPER_URL
        val model = if (isGroq) "whisper-large-v3-turbo" else WHISPER_MODEL

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
            .header("Authorization", "Bearer $openAiApiKey")
            .post(requestBody)
            .build()

        return try {
            val response = httpClient.newCall(request).executeSuspend()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "Whisper error ${response.code}: $body")
                return "[Error de transcripción]"
            }

            val json = JsonParser.parseString(body).asJsonObject
            val text = json.get("text")?.asString ?: ""
            Log.i(TAG, "Transcripción (Whisper): $text")
            text
        } catch (e: Exception) {
            Log.e(TAG, "Error en transcripción: ${e.message}", e)
            "[Error de conexión]"
        }
    }

    /**
     * Transcribe audio WAV directamente con Google Gemini Multimodal (inlineData audio/wav).
     */
    private suspend fun transcribeWithGemini(
        wavData: ByteArray,
        language: String = "es",
        apiKey: String
    ): String {
        val base64Audio = android.util.Base64.encodeToString(wavData, android.util.Base64.NO_WRAP)
        val promptText = "Transcribe exactamente en español ($language) lo que dice la voz principal en este audio. " +
                "Ignora el ruido de fondo. Si no hay voz humana clara, responde únicamente con una cadena vacía. " +
                "No agregues comentarios ni comillas, devuelve solo las palabras dichas."

        val payload = mapOf(
            "contents" to listOf(
                mapOf(
                    "role" to "user",
                    "parts" to listOf(
                        mapOf(
                            "inlineData" to mapOf(
                                "mimeType" to "audio/wav",
                                "data" to base64Audio
                            )
                        ),
                        mapOf("text" to promptText)
                    )
                )
            ),
            "generationConfig" to mapOf(
                "maxOutputTokens" to 128,
                "temperature" to 0.1
            )
        )

        val jsonBody = gson.toJson(payload)
        val url = "$GEMINI_BASE_URL/$GEMINI_MODEL:generateContent"

        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey.trim())
            .header("content-type", "application/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            val response = httpClient.newCall(request).executeSuspend()
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                Log.e(TAG, "Gemini STT error ${response.code}: $body")
                return "[Error de transcripción]"
            }

            val json = JsonParser.parseString(body).asJsonObject
            val candidates = json.getAsJsonArray("candidates")
            val text = candidates?.firstOrNull()?.asJsonObject
                ?.getAsJsonObject("content")
                ?.getAsJsonArray("parts")
                ?.firstOrNull()?.asJsonObject
                ?.get("text")?.asString?.trim() ?: ""

            Log.i(TAG, "Transcripción (Gemini): $text")
            text
        } catch (e: Exception) {
            Log.e(TAG, "Error en transcripción Gemini: ${e.message}", e)
            "[Error de conexión]"
        }
    }

    // ═════════════════════════════════════════════════
    //  2. LLM AGENT (Google Gemini / Claude Anthropic)
    // ═════════════════════════════════════════════════

    /**
     * Envía el texto del usuario al LLM configurado (Google Gemini o Claude Anthropic),
     * soportando Function Calling / Tools mediante [FifoSkillRegistry].
     */
    suspend fun chat(
        userText: String,
        skillRegistry: com.fifo.voicepipeline.skills.FifoSkillRegistry? = null
    ): String {
        val activeGemini = resolveActiveGeminiKey()
        val activeClaude = anthropicApiKey.trim()

        // Preferir Gemini si se configuró una clave de Gemini (o si la clave ingresada es de formato Gemini)
        if (activeGemini.isNotEmpty() && (geminiApiKey.isNotBlank() || isLikelyGeminiKey(activeClaude) || activeClaude.isEmpty())) {
            return chatWithGemini(userText, skillRegistry, activeGemini)
        }

        // Si no hay clave de Claude pero sí de Gemini, usar Gemini
        if (activeClaude.isEmpty() && activeGemini.isNotEmpty()) {
            return chatWithGemini(userText, skillRegistry, activeGemini)
        }

        return chatWithClaude(userText, skillRegistry)
    }

    /**
     * Envía el texto del usuario a Google Gemini (gemini-2.5-flash) con soporte completo
     * de System Prompt, memoria conversacional y Function Calling ([FifoSkillRegistry]).
     */
    private suspend fun chatWithGemini(
        userText: String,
        skillRegistry: com.fifo.voicepipeline.skills.FifoSkillRegistry? = null,
        apiKey: String
    ): String {
        conversationHistory.add(mapOf("role" to "user", "content" to userText))

        val compactContext = FifoDataRepository.buildCompactContextWindow(maxFragments = 5)
        val enrichedSystemPrompt = if (compactContext.isNotBlank()) {
            "$SYSTEM_PROMPT\n\n$compactContext"
        } else {
            SYSTEM_PROMPT
        }

        // Convertir historial al formato de Gemini ("user" y "model")
        val geminiContents = conversationHistory.map { msg ->
            val geminiRole = if (msg["role"] == "assistant") "model" else "user"
            mapOf(
                "role" to geminiRole,
                "parts" to listOf(mapOf("text" to (msg["content"] ?: "")))
            )
        }

        val contentsJson = gson.toJson(geminiContents)
        val systemInstructionJson = gson.toJson(
            mapOf("parts" to listOf(mapOf("text" to enrichedSystemPrompt)))
        )
        val toolsFragment = if (skillRegistry != null) {
            """, "tools": ${skillRegistry.getGeminiToolsJson()}"""
        } else {
            ""
        }

        val jsonBody = """
        {
            "systemInstruction": $systemInstructionJson,
            "contents": $contentsJson$toolsFragment,
            "generationConfig": {
                "maxOutputTokens": 384,
                "temperature": 0.7
            }
        }
        """.trimIndent()

        return try {
            var response = callGeminiGenerateContent(GEMINI_MODEL, apiKey, jsonBody)
            var body = response.body?.string() ?: ""

            // Fallback automático a gemini-2.0-flash si el modelo primario devuelve 404
            if (response.code == 404) {
                Log.w(TAG, "Modelo $GEMINI_MODEL no encontrado, intentando con $GEMINI_FALLBACK_MODEL")
                response = callGeminiGenerateContent(GEMINI_FALLBACK_MODEL, apiKey, jsonBody)
                body = response.body?.string() ?: ""
            }

            if (!response.isSuccessful) {
                Log.e(TAG, "Gemini error ${response.code}: $body")
                return "Disculpa, tuve un problema al conectarme con Gemini. Verifica tu clave de API e intenta de nuevo."
            }

            val json = JsonParser.parseString(body).asJsonObject
            val candidates = json.getAsJsonArray("candidates")
            val parts = candidates?.firstOrNull()?.asJsonObject
                ?.getAsJsonObject("content")
                ?.getAsJsonArray("parts")

            var textReply = ""
            var toolFeedback = ""

            parts?.forEach { element ->
                val partObj = element.asJsonObject
                if (partObj.has("text") && !partObj.get("text").isJsonNull) {
                    textReply += partObj.get("text").asString
                }
                if (partObj.has("functionCall") && skillRegistry != null) {
                    val fnCall = partObj.getAsJsonObject("functionCall")
                    val toolName = fnCall.get("name")?.asString ?: ""
                    val toolArgs = fnCall.getAsJsonObject("args")
                    val argsMap = mutableMapOf<String, Any?>()
                    toolArgs?.keySet()?.forEach { key ->
                        val elementVal = toolArgs.get(key)
                        if (elementVal != null && elementVal.isJsonPrimitive) {
                            val prim = elementVal.asJsonPrimitive
                            argsMap[key] = when {
                                prim.isNumber -> prim.asInt
                                prim.isBoolean -> prim.asBoolean
                                else -> prim.asString
                            }
                        } else if (elementVal != null && !elementVal.isJsonNull) {
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
                toolFeedback.isNotBlank() && textReply.isNotBlank() -> "$textReply $toolFeedback".trim()
                toolFeedback.isNotBlank() -> toolFeedback.trim()
                textReply.isNotBlank() -> textReply.trim()
                else -> "Listo, he registrado los cambios."
            }

            val reply = com.fifo.voicepipeline.audio.TextSanitizer.cleanForSpeech(combinedReply)
            conversationHistory.add(mapOf("role" to "assistant", "content" to reply))
            reply
        } catch (e: Exception) {
            Log.e(TAG, "Error llamando a Gemini: ${e.message}", e)
            "Hubo un error de conexión con Gemini. Intenta de nuevo."
        }
    }

    private suspend fun callGeminiGenerateContent(
        model: String,
        apiKey: String,
        jsonBody: String
    ): Response {
        val request = Request.Builder()
            .url("$GEMINI_BASE_URL/$model:generateContent")
            .header("x-goog-api-key", apiKey.trim())
            .header("content-type", "application/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()
        return httpClient.newCall(request).executeSuspend()
    }

    /**
     * Envía el texto del usuario a Claude (Anthropic) soportando Function Calling.
     */
    private suspend fun chatWithClaude(
        userText: String,
        skillRegistry: com.fifo.voicepipeline.skills.FifoSkillRegistry? = null
    ): String {
        conversationHistory.add(mapOf("role" to "user", "content" to userText))
        val messagesJson = gson.toJson(conversationHistory)

        val toolsFragment = if (skillRegistry != null) {
            """, "tools": ${skillRegistry.getAnthropicToolsJson()}"""
        } else {
            ""
        }

        // Inyectar la ventana de contexto compacto (fragmentos livianos de charlas pasadas)
        // para que Claude tenga continuidad conversacional sin descargar transcripciones completas.
        // Si necesita más detalle, usará el skill recall_past_context.
        val compactContext = FifoDataRepository.buildCompactContextWindow(maxFragments = 5)
        val enrichedSystemPrompt = if (compactContext.isNotBlank()) {
            "$SYSTEM_PROMPT\n\n$compactContext"
        } else {
            SYSTEM_PROMPT
        }

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
                toolFeedback.isNotBlank() && textReply.isNotBlank() -> "$textReply $toolFeedback".trim()
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
     * Prueba la validez de una clave de Google Gemini realizando una consulta mínima de prueba.
     * Retorna Pair(éxito, mensaje descriptivo).
     */
    suspend fun testGeminiConnection(overrideKey: String? = null): Pair<Boolean, String> {
        val key = normalizeGeminiKey(overrideKey ?: resolveActiveGeminiKey())
        if (key.isEmpty()) {
            return Pair(false, "La clave de Gemini no puede estar vacía.")
        }
        if (key.startsWith("sk-ant-")) {
            return Pair(false, "Esta clave pertenece a Claude ('sk-ant-'), no a Google Gemini.")
        }

        val testBody = """
        {
            "contents": [{"role": "user", "parts": [{"text": "Hola"}]}],
            "generationConfig": {"maxOutputTokens": 10}
        }
        """.trimIndent()

        return try {
            var response = callGeminiGenerateContent(GEMINI_MODEL, key, testBody)
            if (response.code == 404) {
                response = callGeminiGenerateContent(GEMINI_FALLBACK_MODEL, key, testBody)
            }
            val body = response.body?.string() ?: ""
            if (response.isSuccessful) {
                Pair(true, "¡Conexión exitosa con Google Gemini!")
            } else {
                val errorMsg = try {
                    val json = JsonParser.parseString(body).asJsonObject
                    json.getAsJsonObject("error")?.get("message")?.asString ?: "Error ${response.code}"
                } catch (e: Exception) {
                    "Error HTTP ${response.code}"
                }
                Pair(false, "Gemini rechazó la clave ($errorMsg)")
            }
        } catch (e: Exception) {
            Pair(false, "Fallo de red: ${e.message}")
        }
    }

    /**
     * Prueba la validez de la clave de IA (detecta automáticamente si es Google Gemini 'AIza...' / 'AQ....' o Claude 'sk-ant-...').
     * Retorna Pair(éxito, mensaje descriptivo).
     */
    suspend fun testAnthropicConnection(overrideKey: String? = null): Pair<Boolean, String> {
        val key = (overrideKey ?: anthropicApiKey.ifBlank { geminiApiKey }).trim()
        if (key.isEmpty()) {
            return Pair(false, "La clave de API no puede estar vacía.")
        }
        if (isLikelyGeminiKey(key)) {
            return testGeminiConnection(key)
        }
        if (!key.startsWith("sk-ant-")) {
            return Pair(false, "La clave debe comenzar con 'AIza'/'AQ.' (Gemini) o 'sk-ant-' (Claude).")
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
     * Envía el texto del usuario a Claude y recibe la respuesta en streaming.
     * Llama [onChunk] con cada fragmento de texto a medida que llega.
     * Llama [onComplete] con la respuesta completa al final.
     *
     * @param userText Texto transcrito del usuario
     * @param onChunk Callback por cada fragmento de texto (para TTS streaming)
     * @param onComplete Callback con la respuesta completa
     * @param onError Callback si hay error
     */
    fun chatStreaming(
        userText: String,
        onChunk: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        // Agregar mensaje del usuario al historial
        conversationHistory.add(mapOf("role" to "user", "content" to userText))

        val messagesJson = gson.toJson(conversationHistory)

        // Inyectar contexto compacto para streaming
        val compactContext = FifoDataRepository.buildCompactContextWindow(maxFragments = 5)
        val enrichedSystemPrompt = if (compactContext.isNotBlank()) {
            "$SYSTEM_PROMPT\n\n$compactContext"
        } else {
            SYSTEM_PROMPT
        }

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

        // Buffer para acumular texto hasta encontrar un delimitador natural
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

                            // ── Estrategia de fragmentación ─────
                            // Enviar al TTS cuando encontramos un delimitador
                            // natural (para prosodia correcta)
                            val bufferStr = sentenceBuffer.toString()
                            if (shouldFlush(bufferStr)) {
                                onChunk(bufferStr.trim())
                                sentenceBuffer.clear()
                            }
                        }
                        "message_stop" -> {
                            // Flush del texto restante
                            if (sentenceBuffer.isNotEmpty()) {
                                onChunk(sentenceBuffer.toString().trim())
                                sentenceBuffer.clear()
                            }

                            val response = fullResponse.toString()
                            // Agregar respuesta al historial
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
