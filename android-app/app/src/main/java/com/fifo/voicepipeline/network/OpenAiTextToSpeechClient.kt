package com.fifo.voicepipeline.network

import android.util.Log
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Adaptador de Text-to-Speech de OpenAI.
 *
 * Devuelve PCM crudo a 16 kHz listo para el parlante o el ESP32. La sesión
 * de voz no conoce la API, el formato de respuesta ni el resampling.
 */
internal class OpenAiTextToSpeechClient(
    private val apiKeyProvider: () -> String,
    private val httpClient: OkHttpClient = fifoHttpClient,
    private val gson: Gson = Gson()
) {
    companion object {
        private const val TAG = "OpenAiTts"
        private const val TTS_URL = "https://api.openai.com/v1/audio/speech"
        private const val TTS_MODEL = "tts-1"
        private const val TTS_VOICE = "nova"
    }

    suspend fun synthesize(text: String): ByteArray? {
        if (text.isBlank()) return null

        val apiKey = apiKeyProvider()
        if (apiKey.isEmpty()) {
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
            .header("Authorization", "Bearer $apiKey")
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
            PcmResampler.resample24kTo16k(pcm24k)
        } catch (e: Exception) {
            Log.e(TAG, "Error en TTS: ${e.message}", e)
            null
        }
    }
}
