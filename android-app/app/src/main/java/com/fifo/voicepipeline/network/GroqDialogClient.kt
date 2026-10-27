package com.fifo.voicepipeline.network

import android.util.Log
import com.fifo.voicepipeline.data.FifoDataRepository
import com.fifo.voicepipeline.pipeline.session.ConversationContextProvider
import com.fifo.voicepipeline.pipeline.session.SpeakerIdentity
import com.fifo.voicepipeline.pipeline.session.SpeakerPromptContext
import com.fifo.voicepipeline.skills.FifoSkillRegistry
import com.fifo.voicepipeline.skills.SkillResult
import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import com.fifo.voicepipeline.location.FifoNavigationManager
import com.fifo.voicepipeline.location.NavigationNotice
import com.fifo.voicepipeline.location.NavigationNoticeKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Motor de diálogo de Groq LPU.
 *
 * Posee el historial conversacional, la construcción de contexto, la
 * orquestación de herramientas y el streaming de respuestas. No conoce STT,
 * TTS ni la máquina de estados de `VoiceSession`; esas piezas viven en
 * adaptadores separados.
 */
internal class GroqDialogClient(
    private val apiKeyProvider: () -> String,
    private val conversationContext: () -> ConversationContextProvider?,
    private val androidContext: () -> android.content.Context?,
    private val navigationManager: () -> com.fifo.voicepipeline.location.FifoNavigationManager?
) {
    companion object {
        private const val TAG = "GroqDialog"
        private const val GROQ_CHAT_URL = "https://api.groq.com/openai/v1/chat/completions"

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
   - Al dar direcciones o guiar ('open_navigation_directions', 'search_nearby_places'), comprende la NECESIDAD detrás del destino (salud, comida, compras, paseo) y sugiere ideas o precauciones pertinentes sin rodeos.
7. UBICACIÓN Y ORIENTACIÓN ("¿dónde estoy?", "¿en qué calle estoy?", "¿dónde voy?", "¿cuánto falta?", "me desvié", "¿y ahora?"):
   - Usa la CONCIENCIA ESPACIAL del contexto (calle, comuna, orientación y ruta activa) y responde con una referencia útil, no con coordenadas ni con la sola comuna.
   - Con una ruta activa, di el destino, la indicación relativa a la orientación actual y la distancia o cuadras restantes (2 o 3 oraciones breves). Si se desvió, avísalo con calma y retoma la guía.
   - Sin ruta activa, ofrece una referencia del entorno o del domicilio según el contexto; si hay un destino consultado y pendiente de confirmación, ofrécelo en una frase.
   - Si no hay ubicación disponible, dilo con honestidad y ofrece ayuda alternativa; nunca inventes una calle.
8. RUTA ACTIVA Y COSAS ENTRE MEDIO ('manage_navigation_session'):
   - Si el usuario menciona una compra, trámite o parada en el camino, anótala con action=add_stop y tenla presente; recuérdasela cuando venga al caso sin repetirla en cada frase.
   - Si pide una pausa, más tiempo o dice que se va a detener, usa action=pause (con minutes si lo indica). No lo apures ni le hables de desvíos mientras esté en pausa.
   - Cuando diga que está listo o quiera seguir, usa action=resume, retoma la guía y resume en una frase el destino y los encargos pendientes.
   - Si corrige la ruta o dice que Fifo se equivocó, usa action=correct, reconócelo sin excusas y reorienta con la orientación y la distancia reales; si falta contexto del lugar, investígalo con 'web_search' antes de afirmar.
   - Puede conversar de otros temas sin perder la ruta: responde esa conversación y retoma el acompañamiento cuando corresponda."""

        private const val PRIMARY_MODEL = "qwen/qwen3.8-27b"

        /** Tiempo máximo para redactar un aviso de navegación antes de usar el fallback. */
        private const val NAVIGATION_NARRATION_TIMEOUT_MS = 6_000L
    }

    private val gson = Gson()
    private val conversationHistory = mutableListOf<Map<String, String>>()

    suspend fun chat(
        userText: String,
        skillRegistry: FifoSkillRegistry? = null,
        speaker: SpeakerIdentity? = null
    ): String {
        conversationHistory.add(mapOf("role" to "user", "content" to userText))
        while (conversationHistory.size > 8) {
            conversationHistory.removeAt(0)
        }

        val response = if (apiKeyProvider().isNotBlank()) {
            chatWithGroq(userText, skillRegistry, speaker)
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

    fun hasRecentConversation(): Boolean = conversationHistory.isNotEmpty()

    /**
     * Redacta un aviso autónomo de navegación a partir del evento estructurado.
     *
     * No usa el catálogo de herramientas ni altera el historial conversacional:
     * el aviso se compone con el contexto espacial y de sesión que ya viaja en el
     * system prompt. Devuelve `null` si no hay clave o si el modelo no responde,
     * para que el pipeline use [NavigationNotice.fallbackSpeech].
     */
    suspend fun narrateNavigationNotice(
        notice: NavigationNotice
    ): String? {
        if (apiKeyProvider().isBlank()) return null

        val prompt = buildString {
            append("AVISO AUTÓNOMO DE NAVEGACIÓN (no es una pregunta del usuario).\n")
            append("Evento: ").append(notice.kind.name).append('\n')
            append("Destino: ").append(notice.destinationName)
            append(" (").append(notice.destinationAddress).append(")\n")
            append("Calle principal: ").append(notice.streetName).append('\n')
            append("Distancia restante: ").append(notice.distanceMeters).append(" metros (")
            append(notice.blocks).append(" cuadras, ").append(notice.walkingMinutes).append(" min a pie)\n")
            append("Indicación relativa a la orientación actual: ").append(notice.relativeInstruction).append('\n')
            append("Rumbo hacia el destino: ").append(notice.cardinalDirection).append('\n')
            if (notice.kind == NavigationNoticeKind.ARRIVED) {
                append("El usuario acaba de llegar y la ruta se cerró en este momento.\n")
            } else if (!notice.isFacingTarget) {
                append("El usuario no apunta hacia el destino: indícale hacia dónde girar.\n")
            }
            if (notice.stalledSeconds > 0) {
                append("Lleva ").append(notice.stalledSeconds).append(" segundos detenido.\n")
            }
            if (notice.plannedStops.isNotEmpty()) {
                append("Encargos pendientes del usuario: ")
                append(notice.plannedStops.joinToString("; ")).append('\n')
            }
            if (notice.pauseReason.isNotBlank()) {
                append("El usuario había indicado una pausa: ").append(notice.pauseReason).append('\n')
            }
            append("Redacta el aviso en 1 o 2 oraciones breves, en segunda persona y con calidez, ")
            append("usando la orientación y la distancia reales. No inventes calles ni distancias. ")
            append("No digas 'aviso' ni describas el evento: habla como un acompañante que camina al lado. ")
            append("Si hay un encargo pendiente y encaja naturalmente, menciónalo una sola vez.")
        }

        val response = withTimeoutOrNull(NAVIGATION_NARRATION_TIMEOUT_MS) {
            chatWithGroq(prompt, skillRegistry = null)
        } ?: return null

        val cleaned = response.trim()
        if (cleaned.isBlank() || cleaned.startsWith("Disculp")) return null
        return cleaned
    }

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

    fun clearHistory() {
        conversationHistory.clear()
    }

    private suspend fun chatWithGroq(
        userText: String,
        skillRegistry: FifoSkillRegistry?,
        speaker: SpeakerIdentity? = null,
        modelToUse: String = PRIMARY_MODEL,
        retryCount: Int = 0
    ): String {
        val apiKey = apiKeyProvider()
        val enrichedSystemPrompt = getEnrichedSystemPrompt(speaker)

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
                    delay(2000)
                    return chatWithGroq(
                        userText,
                        skillRegistry,
                        speaker,
                        modelToUse = modelToUse,
                        retryCount = retryCount + 1
                    )
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
            val textReply = messageObj.get("content")?.run { if (isJsonNull) "" else asString } ?: ""

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
                        SkillResult(
                            success = false,
                            spokenFeedback = "No se pudo completar la acción en este momento."
                        )
                    }

                    if (result.spokenFeedback.isNotBlank()) {
                        lastSpokenFallback = result.spokenFeedback
                    }

                    val compactData = result.data.filterKeys {
                        it in listOf(
                            "destination", "distance_meters", "walking_minutes",
                            "driving_minutes", "cardinalDirection", "title",
                            "summary", "heading", "screen_opened", "success",
                            "is_plural", "nearby_places",
                            // Contexto de ubicación y conciencia espacial
                            "address", "city", "gps_active", "latitude", "longitude",
                            // Contexto de telefonía y relaciones
                            "action", "status", "caller", "caller_name", "phone",
                            "relationship", "recent_context", "suggested_followup", "notes",
                            // Contexto de control de hardware
                            "feature", "state", "battery_level", "is_charging", "time_formatted", "volumePercent",
                            // Contexto y necesidad de navegación
                            "street", "road", "turn", "blocks", "app_used",
                            "needs_destination", "purpose_type", "route_active",
                            "destination_address", "relative_instruction", "cardinal_direction",
                            "planned_stops", "added_stop", "completed_stops",
                            "paused", "pause_reason", "pause_minutes", "resumed",
                            "corrected_route", "last_correction",
                            "contextual_notes", "contextual_recommendations"
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

            val reply = com.fifo.voicepipeline.audio.TextSanitizer.cleanForSpeech(
                textReply.ifBlank { "Listo, he registrado los cambios." }
            )
            conversationHistory.add(mapOf("role" to "assistant", "content" to reply))
            reply
        } catch (e: Exception) {
            Log.e(TAG, "Error llamando a Groq: ${e.message}", e)
            "Disculpe, hubo un problema de conexión con la API de Groq. Por favor intente nuevamente en unos instantes."
        }
    }

    suspend fun generateNaturalResumption(interruptedSentence: String): String {
        val apiKey = apiKeyProvider()
        if (apiKey.isNotBlank()) {
            try {
                val prompt = """Eres Fifo, asistente afectuoso. Estabas diciendo esto en voz alta:
"$interruptedSentence"
El usuario te interrumpió diciendo "Fifo", te callaste de inmediato para escucharle con atención, pero él no dijo nada más (se quedó en silencio).
Reanuda el habla de manera muy natural, espontánea y cálida (por ejemplo notando amablemente que parecía querer decir algo o preguntar, y retomando tu idea donde quedó).
Genera ÚNICAMENTE 1 oración concisa para hablar en voz alta por voz, sin introducciones ni comillas ni formato."""

                val jsonBody = """
                {
                    "model": "$PRIMARY_MODEL",
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

    suspend fun testConnection(overrideKey: String? = null): Pair<Boolean, String> {
        val key = (overrideKey ?: apiKeyProvider()).trim()
        return testGroqConnection(key)
    }

    suspend fun testGroqConnection(overrideKey: String? = null): Pair<Boolean, String> {
        val key = (overrideKey ?: apiKeyProvider()).trim()
        if (key.isEmpty()) {
            return Pair(false, "La clave de Groq no puede estar vacía.")
        }
        if (!key.startsWith("gsk_")) {
            return Pair(false, "La clave de Groq debe comenzar con 'gsk_'.")
        }

        val testBody = """
        {
            "model": "$PRIMARY_MODEL",
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

    fun chatStreaming(
        userText: String,
        onChunk: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        if (apiKeyProvider().isNotBlank()) {
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
            "model": "$PRIMARY_MODEL",
            "messages": $messagesJson,
            "max_tokens": 512,
            "temperature": 0.6,
            "stream": true
        }
        """.trimIndent()

        val request = Request.Builder()
            .url(GROQ_CHAT_URL)
            .header("Authorization", "Bearer ${apiKeyProvider()}")
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

    private fun shouldFlush(text: String): Boolean {
        if (text.isBlank()) return false
        val trimmed = text.trim()
        if (trimmed.endsWith('.') || trimmed.endsWith(',') ||
            trimmed.endsWith('?') || trimmed.endsWith('!') ||
            trimmed.endsWith(':') || trimmed.endsWith(';') ||
            trimmed.endsWith('…')) {
            return true
        }
        return trimmed.split("\\s+".toRegex()).size >= 12
    }

    private fun buildTemporalAndSessionContext(): String {
        val now = java.util.Calendar.getInstance()
        val hour = now.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = now.get(java.util.Calendar.MINUTE)
        val timeFormatted = String.format(java.util.Locale.US, "%02d:%02d", hour, minute)

        val (period, correctGreeting) = when (hour) {
            in 6..11 -> "Mañana" to "Buenos días"
            in 12..19 -> "Tarde" to "Buenas tardes"
            else -> "Noche" to "Buenas noches"
        }

        val userTurnsInHistory = conversationHistory.count { it["role"] == "user" }
        val pastConvsCount = FifoDataRepository.conversations.value.size

        return "HORA ACTUAL: $timeFormatted hrs ($period, saludo: '$correctGreeting')." +
            if (userTurnsInHistory > 1 || pastConvsCount > 0) " (Conversación en curso: responde directo y cercano sin repetir saludos formales)." else ""
    }

    private fun getEnrichedSystemPrompt(speaker: SpeakerIdentity? = null): String {
        val injectedContext = conversationContext()?.buildContextWindow()
        val speakerContext = SpeakerPromptContext.build(speaker)
        if (!injectedContext.isNullOrBlank()) {
            return buildString {
                append(SYSTEM_PROMPT).append("\n\n")
                if (!speakerContext.isNullOrBlank()) {
                    append(speakerContext).append("\n\n")
                }
                append(injectedContext)
            }.trim()
        }

        val compactContext = FifoDataRepository.buildCompactContextWindow(maxFragments = 1)
        val temporalContext = buildTemporalAndSessionContext()
        val profile = FifoDataRepository.userProfile.value
        val gpsInfo = androidContext()?.let {
            com.fifo.voicepipeline.location.FifoLocationHelper.getCurrentLocation(it)
        }

        val locationContext = if (gpsInfo != null && gpsInfo.isGpsActive) {
            "UBICACIÓN GPS ACTUAL: ${gpsInfo.address} (${gpsInfo.city}). Coordenadas: ${gpsInfo.latitude}, ${gpsInfo.longitude}. Busca y guía siempre desde ${gpsInfo.city}."
        } else if (profile.preferredAddress.isNotBlank()) {
            "UBICACIÓN ESTIMADA: ${profile.preferredAddress} (${profile.city})."
        } else ""

        val navContext = if (gpsInfo != null) {
            navigationManager()?.buildSystemPromptContext(gpsInfo).orEmpty()
        } else ""

        return buildString {
            append(SYSTEM_PROMPT).append("\n\n")
            if (!speakerContext.isNullOrBlank()) {
                append(speakerContext).append("\n")
            }
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
}
