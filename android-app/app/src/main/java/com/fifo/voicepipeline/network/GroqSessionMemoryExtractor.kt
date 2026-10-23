package com.fifo.voicepipeline.network

import android.util.Log
import com.fifo.voicepipeline.data.memory.SessionMemoryConsolidation
import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Adaptador de extracción de memoria de sesión con Groq.
 *
 * Distingue entre "no había nada que recordar" (`null`) y un fallo recuperable
 * de red/parseo (excepción cuando `failOnError = true`) para que la cola durable
 * pueda reintentar sin descartar turnos.
 */
internal class GroqSessionMemoryExtractor(
    private val apiKeyProvider: () -> String,
    private val httpClient: OkHttpClient = fifoHttpClient,
    private val gson: Gson = Gson()
) {
    companion object {
        private const val TAG = "GroqMemory"
        private const val GROQ_CHAT_URL = "https://api.groq.com/openai/v1/chat/completions"
        private const val PRIMARY_MODEL = "qwen/qwen3.8-27b"
    }

    suspend fun extract(
        turns: List<Pair<String, String>>,
        failOnError: Boolean
    ): SessionMemoryConsolidation? {
        if (turns.isEmpty()) return null

        val apiKey = apiKeyProvider()
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

        val jsonResponse = if (apiKey.isNotBlank()) {
            try {
                val jsonBody = """
                {
                    "model": "$PRIMARY_MODEL",
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
                if (failOnError) throw e
                null
            }
        } else null

        if (jsonResponse.isNullOrBlank()) {
            if (failOnError && apiKey.isNotBlank()) {
                throw IOException("Groq no devolvió memoria de sesión")
            }
            return null
        }

        return try {
            SessionMemoryParser.parse(jsonResponse)
        } catch (e: Exception) {
            Log.e(TAG, "Error parseando JSON de consolidación: ${e.message}", e)
            if (failOnError) throw e
            null
        }
    }
}
