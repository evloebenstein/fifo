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
        // Modelo de respaldo si hay rate limit: OpenAI GPT-OSS 120B
        const val GROQ_FALLBACK_MODEL = "openai/gpt-oss-120b"
        const val GROQ_WHISPER_MODEL = "whisper-large-v3-turbo"

        private const val CLAUDE_MODEL = "claude-haiku-4-5-20251001"
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
- GUÍA HABLADA SIN ABRIR EL CELULAR (CRÍTICO):
  * REGLA FUNDAMENTAL: Fifo es un asistente por voz que dirige al usuario verbalmente. NUNCA le digas "las direcciones están en el celular", ni "mira la app por ti mismo", ni "le abrí la app de maps".
  * PANTALLA SOLO SI SE PIDE EXPLÍCITAMENTE: Por defecto, 'open_screen_map' DEBE SER SIEMPRE false tanto en 'search_nearby_places' como en 'open_navigation_directions'.
  * ÚNICAMENTE si el usuario dice palabras explícitas como "muéstrame en mi celular la ubicación...", "abre el mapa en la pantalla", "muéstrame la pantalla" o "abre Maps", pasa 'open_screen_map': true.
  * Si el usuario NO te pide específicamente que se lo muestres en el celular, guíalo 100% verbalmente indicando la dirección exacta más cercana, la distancia en metros y cuadras, los minutos caminando y por qué calle avanzar.
- LUGARES CERCANOS Y LOCALES OXXO EN CHILE (CRÍTICO):
  * CONOCIMIENTO LOCAL DE CHILE: OXXO SÍ existe en Chile (la cadena OK Market fue adquirida y convertida íntegramente en OXXO, existiendo cientos de tiendas Oxxo en Santiago y todo Chile). NUNCA digas que no hay Oxxo en Chile ni en Santiago.
  * Si el usuario te pide buscar locales de Oxxo, minimarkets, farmacias, tiendas, supermercados o parques cercanos, invoca de inmediato 'search_nearby_places' con place_type="oxxo" (o la categoría correspondiente) y open_screen_map=false (a menos que haya pedido "muéstrame en mi celular").
  * RESPUESTA HABLADA: Di siempre en voz alta la dirección más cercana, la distancia en metros, minutos a pie y orientación (ej: "Revisé su ubicación en... El OXXO más cercano a usted está en... a unos... metros...").

11. Gestión de Llamadas Telefónicas por Voz Tipo Alexa ('manage_phone_call')
- El teléfono actúa como tu cerebro invisible. El usuario no debe navegar pantallas para atender llamadas.
- CONTESTAR: Si suena el teléfono o el usuario dice "Fifo contesta", "atiende", "sí contesta", invoca 'manage_phone_call' con action="answer". La llamada se contestará automáticamente en altavoz.
- COLGAR / DETENER: Si el usuario dice "Fifo cuelga", "detener llamada", "rechaza", "no contestes", invoca 'manage_phone_call' con action="hangup".
- CONSULTAR: Si pregunta "¿quién me está llamando?", invoca 'manage_phone_call' con action="status".
- LLAMAR A CONTACTO: Si pide llamar a un familiar ("llama a mi hija Carmen") o a emergencias ("llama a la ambulancia 131"), invoca 'manage_phone_call' con action="call" y el nombre.

12. Consulta y Gestión de Contactos del Celular ('read_phone_contacts')
- Si el usuario te pregunta si puedes ver sus contactos ("¿puedes ver mis contactos?", "¿qué contactos tengo?", "¿tienes los contactos de mi teléfono?"), invoca de inmediato 'read_phone_contacts' con action="list". SÍ puedes ver sus contactos gracias al permiso del teléfono.
- Si te pide buscar el número de alguien específico ("busca a Pedro", "¿tienes el teléfono de Juan?"), invoca 'read_phone_contacts' con action="search" y query="Pedro".

13. Planes Semanales, Rutinas de Entrenamiento y Hábitos (Jiu-Jitsu, Deporte, Salud)
- PROHIBICIÓN DE MONÓLOGOS LARGOS POR VOZ: NUNCA le dictes un plan semanal completo ni una lista interminable de corrido por voz. Dictar 7 días de entrenamiento por sintetizador es agotador y confuso.
- PROTOCOLO DE PLAN SEMANAL: Cuando el usuario te pida un plan semanal (ej: "plan de entreno en Jiu-Jitsu para la semana", rutina de gimnasio, caminatas):
  1) Genera un resumen verbal cálido, motivador y conciso (máximo 2 a 3 frases).
  2) Invoca 'update_profile_and_tastes' con action="add_memory", memory_title="Plan de Jiu-Jitsu semanal", memory_description="el desglose completo de días, técnicas y rutinas", memory_icon="🥋" para que quede guardado en la pestaña "Charlas y Recuerdos" de la app.
  3) Invoca 'set_reminder' para agendar los recordatorios de los días clave de entreno (ej: "Entrenamiento Jiu-Jitsu" a las 18:00).
  4) Dile en voz alta: "¡Excelente iniciativa! Te he armado tu plan de Jiu-Jitsu para la semana y te lo dejé guardado en tus Recuerdos de Fifo para que lo revises con calma. Además, te programé los recordatorios en la app para tus días de entrenamiento a las 18:00. ¿Quieres que ajuste algún horario o día?"

14. Control de Hardware del Celular Tipo Asistente Alexa ('control_device_hardware')
- LINTERNA: Si el usuario dice "prende la linterna", "enciende la luz", "apaga la linterna", invoca 'control_device_hardware' con feature="flashlight" y state="on" u "off". Ideal de noche para evitar caídas.
- VOLUMEN: Si pide "sube el volumen", "más fuerte", "baja el volumen", "pon el volumen al máximo", invoca 'control_device_hardware' con feature="volume" y state="up", "down" o "max".
- BATERÍA: Si pregunta "¿cuánta batería le queda al celular?", invoca 'control_device_hardware' con feature="battery".
- HORA Y FECHA: Si pregunta "¿qué hora es?" o "¿qué día es hoy?", invoca 'control_device_hardware' con feature="time".

15. Modo de Escucha Continua ("Fifo, sigue escuchando")
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
            val text = json.get("text")?.asString ?: ""
            Log.i(TAG, "Transcripción ($model): $text")
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

        return if (isGroqActive) {
            chatWithGroq(userText, skillRegistry)
        } else {
            "Disculpe, la API de Groq no se encuentra configurada en este momento. Por favor ingrese su clave de Groq para poder conversar."
        }
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
     * Motor conversacional principal con Groq LPU (gratuito, ultrarrápido).
     * Utiliza Qwen 27B con failover automático a GPT-OSS 120B y Claude.
     */
    private suspend fun chatWithGroq(
        userText: String,
        skillRegistry: com.fifo.voicepipeline.skills.FifoSkillRegistry?,
        modelToUse: String = GROQ_PRIMARY_MODEL
    ): String {
        val apiKey = effectiveGroqKey
        val compactContext = FifoDataRepository.buildCompactContextWindow(maxFragments = 5)
        val enrichedSystemPrompt = if (compactContext.isNotBlank()) {
            "$SYSTEM_PROMPT\n\n$compactContext"
        } else {
            SYSTEM_PROMPT
        }

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
            "max_tokens": 1024,
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
                    return chatWithGroq(userText, skillRegistry, modelToUse = GROQ_FALLBACK_MODEL)
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
                Log.w(TAG, "Groq: Error de red con $modelToUse, probando fallback...")
                return chatWithGroq(userText, skillRegistry, modelToUse = GROQ_FALLBACK_MODEL)
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

        val compactContext = FifoDataRepository.buildCompactContextWindow(maxFragments = 5)
        val enrichedSystemPrompt = if (compactContext.isNotBlank()) {
            "$SYSTEM_PROMPT\n\n$compactContext"
        } else {
            SYSTEM_PROMPT
        }

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
