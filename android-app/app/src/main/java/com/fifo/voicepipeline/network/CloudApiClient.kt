package com.fifo.voicepipeline.network

import android.util.Log
import com.fifo.voicepipeline.audio.AudioConfig
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
    var anthropicApiKey: String,
    var openAiApiKey: String = ""
) {
    companion object {
        private const val TAG = "CloudApiClient"

        // ── Endpoints ───────────────────────────────
        private const val ANTHROPIC_URL = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val WHISPER_URL = "https://api.openai.com/v1/audio/transcriptions"
        private const val TTS_URL = "https://api.openai.com/v1/audio/speech"

        // ── Modelo ──────────────────────────────────
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
- Responde directamente con el diálogo en prosa limpia, cálida y natural, tal como una persona amable hablaría en voz alta."""
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
     * Si no hay API key de OpenAI, usa un endpoint alternativo.
     *
     * @param wavData Audio en formato WAV (header + PCM)
     * @param language Código de idioma (ej: "es", "en")
     * @return Texto transcrito
     */
    suspend fun transcribe(wavData: ByteArray, language: String = "es"): String {
        val key = openAiApiKey.trim()
        if (key.isEmpty() || key.startsWith("sk-ant-")) {
            Log.w(TAG, "No hay API key válida para Whisper (OpenAI o Groq). Clave actual: ${if (key.startsWith("sk-ant-")) "Pertenece a Anthropic, no a Whisper" else "Vacía"}")
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
            Log.i(TAG, "Transcripción: $text")
            text
        } catch (e: Exception) {
            Log.e(TAG, "Error en transcripción: ${e.message}", e)
            "[Error de conexión]"
        }
    }

    // ═════════════════════════════════════════════════
    //  2. LLM AGENT (Claude - Anthropic)
    // ═════════════════════════════════════════════════

    /**
     * Envía el texto del usuario a Claude de forma directa y asíncrona.
     * Retorna la respuesta completa en ~300-500 ms sin depender de SSE.
     */
    suspend fun chat(userText: String): String {
        conversationHistory.add(mapOf("role" to "user", "content" to userText))
        val messagesJson = gson.toJson(conversationHistory)

        val jsonBody = """
        {
            "model": "$CLAUDE_MODEL",
            "max_tokens": 256,
            "system": ${gson.toJson(SYSTEM_PROMPT)},
            "messages": $messagesJson
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
            val rawReply = contentArray?.get(0)?.asJsonObject?.get("text")?.asString
                ?: "No pude entender tu respuesta."
            val reply = com.fifo.voicepipeline.audio.TextSanitizer.cleanForSpeech(rawReply)

            conversationHistory.add(mapOf("role" to "assistant", "content" to reply))
            reply
        } catch (e: Exception) {
            Log.e(TAG, "Error llamando a Claude: ${e.message}", e)
            "Hubo un error de conexión con el servidor. Intenta de nuevo."
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

        val jsonBody = """
        {
            "model": "$CLAUDE_MODEL",
            "max_tokens": 256,
            "system": ${gson.toJson(SYSTEM_PROMPT)},
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
