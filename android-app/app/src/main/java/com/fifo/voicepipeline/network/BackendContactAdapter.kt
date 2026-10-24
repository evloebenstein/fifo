package com.fifo.voicepipeline.network

import android.util.Log
import com.fifo.voicepipeline.ui.model.ContactIdentityResult
import com.fifo.voicepipeline.ui.model.RelationalContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Grafo social y telefonía contextual: contactos relacionales y eventos de llamada.
 */
internal class BackendContactAdapter(private val http: BackendHttp) {

    suspend fun postRelationalContact(userId: String, contact: RelationalContact) =
        http.postOrPatchJson(http.url("/users/$userId/contacts-relational"), "POST", JSONObject().apply {
            put("contact_name", contact.contactName)
            contact.phoneNumber?.let { put("phone_number", it) }
            put("relationship_role", contact.relationshipRole)
            put("closeness_score", contact.closenessScore.toDouble())
            put("trust_tier", contact.trustTier)
            put("emotional_valence", contact.emotionalValence)
            contact.contextualMemory?.let { put("contextual_memory", it) }
            contact.alias?.let { put("alias", it) }
        })

    suspend fun syncRelationalContactsBatch(userId: String, contacts: List<RelationalContact>): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (contacts.isEmpty()) return@withContext true
                val jsonArr = JSONArray()
                contacts.forEach { c ->
                    jsonArr.put(JSONObject().apply {
                        put("contact_name", c.contactName)
                        c.phoneNumber?.let { put("phone_number", it) }
                        put("relationship_role", c.relationshipRole)
                        put("closeness_score", c.closenessScore.toDouble())
                        put("trust_tier", c.trustTier)
                        put("emotional_valence", c.emotionalValence)
                        c.contextualMemory?.let { put("contextual_memory", it) }
                        c.alias?.let { put("alias", it) }
                    })
                }
                val req = Request.Builder()
                    .url(http.url("/users/$userId/contacts-relational/sync"))
                    .post(http.jsonBody(jsonArr))
                    .build()
                http.client.newCall(req).execute().use { resp ->
                    resp.isSuccessful
                }
            } catch (e: Exception) {
                Log.w(FIFO_BACKEND_TAG, "Error sincronizando lote de contactos relacionales: ${e.message}")
                false
            }
        }

    suspend fun identifyContact(
        userId: String,
        callerName: String?,
        phoneNumber: String?
    ): ContactIdentityResult? = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                callerName?.let { put("caller_name", it) }
                phoneNumber?.let { put("phone_number", it) }
            }
            val req = Request.Builder()
                .url(http.url("/users/$userId/telephony/identify-contact"))
                .post(http.jsonBody(payload))
                .build()
            http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val bodyStr = resp.body?.string() ?: return@withContext null
                val obj = JSONObject(bodyStr)
                ContactIdentityResult(
                    isKnown = obj.optBoolean("isKnown", false),
                    contactName = obj.optString("contactName", callerName ?: "Contacto"),
                    relationshipRole = obj.optString("relationshipRole", "Contacto telefónico"),
                    phoneNumber = obj.optString("phoneNumber", phoneNumber ?: ""),
                    contextualNotes = obj.optString("contextualNotes", ""),
                    recentTopics = obj.optString("recentTopics", ""),
                    suggestedGreeting = obj.optString("suggestedGreeting", ""),
                    suggestedFollowup = obj.optString("suggestedFollowup", "")
                )
            }
        } catch (e: Exception) {
            Log.d(FIFO_BACKEND_TAG, "Fallback local identifyContact: ${e.message}")
            null
        }
    }

    suspend fun logCallEvent(
        userId: String,
        action: String,
        callerName: String,
        phoneNumber: String? = null,
        durationSeconds: Int = 0,
        notes: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("action", action)
                put("caller_name", callerName)
                phoneNumber?.let { put("phone_number", it) }
                put("duration_seconds", durationSeconds)
                notes?.let { put("notes", it) }
            }
            http.postOrPatchJson(http.url("/users/$userId/telephony/log-call-event"), "POST", payload)
        } catch (e: Exception) {
            Log.d(FIFO_BACKEND_TAG, "Error logCallEvent: ${e.message}")
            false
        }
    }
}
