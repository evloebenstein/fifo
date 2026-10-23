package com.fifo.voicepipeline.network

import android.util.Log
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Adaptador de Speech-to-Text.
 *
 * Prefiere Groq Whisper Large V3 Turbo y conserva el fallback explícito a
 * OpenAI Whisper cuando el usuario configuró una clave de OpenAI. No conoce
 * el historial conversacional ni el pipeline de voz.
 */
internal class GroqSpeechToTextClient(
    private val groqKeyProvider: () -> String,
    private val openAiKeyProvider: () -> String,
    private val httpClient: OkHttpClient = fifoHttpClient
) {
    companion object {
        private const val TAG = "GroqWhisper"
        private const val GROQ_WHISPER_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        private const val OPENAI_WHISPER_URL = "https://api.openai.com/v1/audio/transcriptions"
        private const val GROQ_WHISPER_MODEL = "whisper-large-v3-turbo"
        private const val OPENAI_WHISPER_MODEL = "whisper-1"
    }

    suspend fun transcribe(
        wavData: ByteArray,
        language: String,
        allowShortResponses: Boolean
    ): String {
        val groqKey = groqKeyProvider()
        val openKey = openAiKeyProvider().trim()

        val (authKey, endpoint, model) = when {
            groqKey.isNotBlank() -> Triple(groqKey, GROQ_WHISPER_URL, GROQ_WHISPER_MODEL)
            openKey.isNotBlank() -> {
                if (openKey.startsWith("gsk_")) {
                    Triple(openKey, GROQ_WHISPER_URL, GROQ_WHISPER_MODEL)
                } else {
                    Triple(openKey, OPENAI_WHISPER_URL, OPENAI_WHISPER_MODEL)
                }
            }
            else -> {
                Log.w(TAG, "No hay API key válida para Whisper (Groq u OpenAI).")
                return "[KEY_STT_FALTANTE]"
            }
        }

        // El prompt de Whisper ayuda a guiarlo hacia transcripción de conversación directa
        // en lugar de frases de relleno de video de YouTube.
        val whisperPrompt = if (allowShortResponses) {
            "Conversación en español. El usuario responde brevemente a una pregunta. Transcribir exactamente lo que dice."
        } else {
            "Conversación en español con asistente de voz. Transcribir el comando o pregunta del usuario."
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
            .addFormDataPart("prompt", whisperPrompt)
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
            if (WhisperHallucinationFilter.isHallucination(text, allowShortResponses)) {
                Log.w(
                    TAG,
                    "Alucinación de silencio/ruido de Whisper descartada: '$text' " +
                        "(allowShortResponses=$allowShortResponses)"
                )
                return ""
            }
            text
        } catch (e: Exception) {
            Log.e(TAG, "Error en transcripción: ${e.message}", e)
            "[Error de conexión]"
        }
    }
}
