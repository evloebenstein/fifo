package com.fifo.voicepipeline.network

import android.util.Log
import com.fifo.voicepipeline.network.FifoBackendClient.AuthResult
import com.fifo.voicepipeline.network.FifoBackendClient.UserProfileSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Autenticación, alta de perfil, listado de usuarios y edición del perfil.
 */
internal class BackendAccountAdapter(private val http: BackendHttp) {

    suspend fun patchDemographics(
        userId: String,
        fullName: String?,
        birthDate: String?,
        birthYear: Int?,
        gender: String?,
        city: String?
    ) = http.postOrPatchJson(http.url("/users/$userId/demographics"), "PATCH", JSONObject().apply {
        fullName?.let { put("full_name", it) }
        birthDate?.let { put("birth_date", it) }
        birthYear?.let { put("birth_year", it) }
        gender?.let { put("gender_identity", it) }
        city?.let { put("city", it) }
    })

    suspend fun putBio(userId: String, newBio: String) =
        http.postOrPatchJson(http.url("/users/$userId/bio"), "PUT", JSONObject().apply {
            put("bio_ai", newBio)
        })

    suspend fun putInterestTags(userId: String, tags: List<String>) =
        http.postOrPatchJson(http.url("/users/$userId/interest-tags"), "PUT", JSONObject().apply {
            put("interest_tags", JSONArray(tags))
        })

    suspend fun authenticateGoogleUser(
        googleId: String,
        email: String,
        fullName: String,
        avatarUrl: String? = null,
        estimatedAge: Int? = null,
        city: String? = null
    ): AuthResult? = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("google_id", googleId)
                put("email", email)
                put("full_name", fullName)
                avatarUrl?.let { put("avatar_url", it) }
                estimatedAge?.let { put("estimated_age", it) }
                city?.let { put("city", it) }
            }
            val req = Request.Builder()
                .url(http.url("/auth/google"))
                .post(http.jsonBody(payload))
                .build()
            http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val bodyStr = resp.body?.string() ?: return@withContext null
                val obj = JSONObject(bodyStr)
                AuthResult(
                    status = obj.optString("status", "ok"),
                    action = if (obj.has("action")) obj.optString("action") else null,
                    userId = obj.getString("user_id"),
                    message = obj.optString("message", "")
                )
            }
        } catch (e: Exception) {
            Log.w(FIFO_BACKEND_TAG, "Error en authenticateGoogleUser: ${e.message}")
            null
        }
    }

    suspend fun createUserProfile(
        fullName: String,
        email: String? = null,
        birthDate: String? = null,
        birthYear: Int? = null,
        estimatedAge: Int? = null,
        genderIdentity: String? = "Mujer",
        city: String? = "Santiago, Chile",
        initialTastes: List<String> = emptyList()
    ): AuthResult? = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("full_name", fullName)
                email?.let { put("email", it) }
                birthDate?.let { put("birth_date", it) }
                birthYear?.let { put("birth_year", it) }
                estimatedAge?.let { put("estimated_age", it) }
                genderIdentity?.let { put("gender_identity", it) }
                city?.let { put("city", it) }
                put("initial_tastes", JSONArray(initialTastes))
            }
            val req = Request.Builder()
                .url(http.url("/users"))
                .post(http.jsonBody(payload))
                .build()
            http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val bodyStr = resp.body?.string() ?: return@withContext null
                val obj = JSONObject(bodyStr)
                AuthResult(
                    status = obj.optString("status", "ok"),
                    action = obj.optString("action", "created"),
                    userId = obj.getString("user_id"),
                    message = obj.optString("message", "")
                )
            }
        } catch (e: Exception) {
            Log.w(FIFO_BACKEND_TAG, "Error en createUserProfile: ${e.message}")
            null
        }
    }

    suspend fun loginUser(identifier: String): AuthResult? = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("identifier", identifier)
            }
            val req = Request.Builder()
                .url(http.url("/auth/login"))
                .post(http.jsonBody(payload))
                .build()
            http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val bodyStr = resp.body?.string() ?: return@withContext null
                val obj = JSONObject(bodyStr)
                AuthResult(
                    status = obj.optString("status", "ok"),
                    action = "login",
                    userId = obj.getString("user_id"),
                    message = obj.optString("message", "")
                )
            }
        } catch (e: Exception) {
            Log.w(FIFO_BACKEND_TAG, "Error en loginUser: ${e.message}")
            null
        }
    }

    suspend fun fetchAllUsers(): List<UserProfileSummary> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(http.url("/users")).get().build()
            http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val bodyStr = resp.body?.string() ?: return@withContext emptyList()
                val obj = JSONObject(bodyStr)
                val arr = obj.optJSONArray("users") ?: JSONArray()
                (0 until arr.length()).map { idx ->
                    val u = arr.getJSONObject(idx)
                    val tArr = u.optJSONArray("tastes") ?: JSONArray()
                    val tastes = (0 until tArr.length()).map { tArr.getString(it) }
                    UserProfileSummary(
                        id = u.optString("id"),
                        fullName = u.optString("full_name"),
                        email = u.optString("email"),
                        age = u.optInt("estimated_age", 68),
                        city = u.optString("city", "Santiago, Chile"),
                        quote = if (u.has("short_quote") && !u.isNull("short_quote")) u.optString("short_quote") else null,
                        avatarBgHex = u.optLong("avatar_bg_hex", 4293514751L),
                        bioAi = u.optString("bio_ai", ""),
                        tastes = tastes
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(FIFO_BACKEND_TAG, "Error en fetchAllUsers: ${e.message}")
            emptyList()
        }
    }
}
