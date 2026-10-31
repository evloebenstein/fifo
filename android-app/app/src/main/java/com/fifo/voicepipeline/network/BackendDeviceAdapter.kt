package com.fifo.voicepipeline.network

import android.util.Log
import com.fifo.voicepipeline.data.model.NavigationContextResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ubicación del dispositivo y contexto de navegación.
 */
internal class BackendDeviceAdapter(private val http: BackendHttp) {

    suspend fun patchDeviceLocation(
        userId: String,
        isConnected: Boolean? = null,
        lastConnectedTime: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        address: String? = null,
        roomHint: String? = null,
        rssi: Int? = null,
        isBeeping: Boolean? = null
    ) = http.postOrPatchJson(http.url("/users/$userId/device-location"), "PATCH", JSONObject().apply {
        isConnected?.let { put("is_connected", it) }
        lastConnectedTime?.let { put("last_connected_time", it) }
        latitude?.let { put("latitude", it) }
        longitude?.let { put("longitude", it) }
        address?.let { put("address", it) }
        roomHint?.let { put("room_hint", it) }
        rssi?.let { put("rssi", it) }
        isBeeping?.let { put("is_beeping", it) }
    })

    suspend fun fetchNavigationContextualIntent(
        userId: String,
        destination: String,
        lat: Double? = null,
        lon: Double? = null
    ): NavigationContextResult? = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("destination", destination)
                lat?.let { put("current_lat", it) }
                lon?.let { put("current_lon", it) }
            }
            val req = Request.Builder()
                .url(http.url("/users/$userId/navigation/contextual-intent"))
                .post(http.jsonBody(payload))
                .build()
            http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val bodyStr = resp.body?.string() ?: return@withContext null
                val obj = JSONObject(bodyStr)
                val notesArr = obj.optJSONArray("contextualNotes") ?: JSONArray()
                val recsArr = obj.optJSONArray("proactiveRecommendations") ?: JSONArray()
                NavigationContextResult(
                    destination = obj.optString("destination", destination),
                    purposeType = obj.optString("purposeType", "general"),
                    contextualNotes = (0 until notesArr.length()).map { notesArr.getString(it) },
                    proactiveRecommendations = (0 until recsArr.length()).map { recsArr.getString(it) },
                    safetyAlerts = if (obj.has("safetyAlerts") && !obj.isNull("safetyAlerts")) obj.getString("safetyAlerts") else null
                )
            }
        } catch (e: Exception) {
            Log.d(FIFO_BACKEND_TAG, "Fallback local fetchNavigationContextualIntent: ${e.message}")
            null
        }
    }
}
