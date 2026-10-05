package com.fifo.voicepipeline.data.remote

import android.content.Context
import android.util.Log
import com.fifo.voicepipeline.data.FifoDataRepository
import com.fifo.voicepipeline.data.local.FifoDatabaseHelper
import com.fifo.voicepipeline.ui.model.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Cliente de red HTTP para sincronización entre la app Android y la base de datos MySQL (vía API Docker).
 *
 * Características:
 * - Diseñado para conectar con el backend en http://10.0.2.2:8090 (emulador) o IP local.
 * - Timeouts ultra-cortos (3s): si el backend Docker no está activo, la app sigue funcionando 100% en SQLite local.
 * - Sincronización bidireccional segura en segundo plano.
 */
object FifoApiClient {

    private const val TAG = "FifoApiClient"
    private const val DEFAULT_BASE_URL = "http://10.0.2.2:8090"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    var baseUrl: String = DEFAULT_BASE_URL

    /**
     * Verifica si el backend Docker de Fifo está accesible.
     */
    suspend fun isServerReachable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$baseUrl/health")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.d(TAG, "Backend Docker no alcanzable: ${e.message} (operando en SQLite local)")
            false
        }
    }

    /**
     * Sincroniza todos los datos locales con el backend Docker.
     */
    suspend fun syncAll(context: Context, userId: String = "usr_lucia_01"): Boolean = withContext(Dispatchers.IO) {
        if (!isServerReachable()) {
            Log.i(TAG, "Backend no disponible, omitiendo sincronización de red")
            return@withContext false
        }

        try {
            val dbHelper = FifoDatabaseHelper.getInstance(context)

            // 1. Sincronizar Perfil
            val profile = dbHelper.getUserProfile(userId)
            val profileJson = gson.toJson(mapOf(
                "full_name" to profile.fullName,
                "birth_date" to profile.birthDate,
                "birth_year" to profile.birthYear,
                "estimated_age" to profile.estimatedAge,
                "gender_identity" to profile.genderIdentity,
                "city" to profile.city,
                "bio_ai" to profile.bioAi,
                "emergency_contact_name" to profile.emergencyContactName,
                "emergency_contact_phone" to profile.emergencyContactPhone
            ))

            val putProfile = Request.Builder()
                .url("$baseUrl/api/users/$userId/profile")
                .put(profileJson.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(putProfile).execute().close()

            // 2. Obtener y fusionar gustos desde el servidor
            val getTastes = Request.Builder()
                .url("$baseUrl/api/users/$userId/tastes")
                .get()
                .build()

            client.newCall(getTastes).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: "[]"
                    val serverTastes: List<Map<String, Any>> = gson.fromJson(
                        body,
                        object : TypeToken<List<Map<String, Any>>>() {}.type
                    )
                    for (item in serverTastes) {
                        val name = item["name"] as? String
                        if (!name.isNullOrBlank()) {
                            dbHelper.addTaste(userId, name)
                        }
                    }
                }
            }

            // 3. Enviar ubicación del robot
            val devLoc = dbHelper.getDeviceLocation()
            val locJson = gson.toJson(mapOf(
                "is_connected" to devLoc.isConnected,
                "last_connected_time" to devLoc.lastConnectedTime,
                "last_known_latitude" to devLoc.lastKnownLatitude,
                "last_known_longitude" to devLoc.lastKnownLongitude,
                "last_known_address" to devLoc.lastKnownAddress,
                "last_known_room" to devLoc.lastKnownRoom,
                "signal_strength_rssi" to devLoc.signalStrengthRssi,
                "is_beeping" to devLoc.isBeeping
            ))

            val putLoc = Request.Builder()
                .url("$baseUrl/api/device/FIFO-S3-ESP32/location")
                .put(locJson.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(putLoc).execute().close()

            Log.i(TAG, "¡Sincronización con backend Docker completada con éxito!")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Error durante la sincronización: ${e.message}")
            false
        }
    }
}
