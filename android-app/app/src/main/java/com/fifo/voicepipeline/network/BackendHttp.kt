package com.fifo.voicepipeline.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal const val FIFO_BACKEND_TAG = "FifoBackendClient"

/**
 * Transporte HTTP compartido por los adaptadores del backend propio.
 *
 * Es dueño del `OkHttpClient`, del `Content-Type` JSON y de la `baseUrl`
 * mutable (USB/emulador). Los adaptadores no crean clientes ni conocen
 * timeouts; solo arman la ruta y el payload de su dominio.
 */
internal class BackendHttp {

    @Volatile
    var baseUrl: String = "http://127.0.0.1:8090"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .build()

    val jsonMediaType: MediaType = "application/json; charset=utf-8".toMediaType()

    fun url(path: String): String = "$baseUrl$path"

    fun jsonBody(payload: JSONObject) = payload.toString().toRequestBody(jsonMediaType)

    fun jsonBody(payload: JSONArray) = payload.toString().toRequestBody(jsonMediaType)

    fun isHealthy(targetUrl: String): Boolean {
        return try {
            val req = Request.Builder().url("$targetUrl/health").get().build()
            client.newCall(req).execute().use { resp -> resp.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun postOrPatchJson(url: String, method: String, payload: JSONObject): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val body = jsonBody(payload)
                val builder = Request.Builder().url(url)
                when (method) {
                    "POST" -> builder.post(body)
                    "PUT" -> builder.put(body)
                    "PATCH" -> builder.patch(body)
                    "DELETE" -> builder.delete()
                }
                client.newCall(builder.build()).execute().use { it.isSuccessful }
            } catch (e: Exception) {
                Log.d(FIFO_BACKEND_TAG, "Error enviando $method a $url: ${e.message}")
                false
            }
        }
}
