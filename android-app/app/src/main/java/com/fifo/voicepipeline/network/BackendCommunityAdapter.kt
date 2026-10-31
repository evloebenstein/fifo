package com.fifo.voicepipeline.network

import android.util.Log
import com.fifo.voicepipeline.data.model.CommunityActivityItem
import com.fifo.voicepipeline.data.model.FriendRecommendation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Comunidad: recomendaciones de amistad y actividades comunitarias.
 */
internal class BackendCommunityAdapter(private val http: BackendHttp) {

    suspend fun fetchFriendRecommendations(userId: String): List<FriendRecommendation> =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(http.url("/users/$userId/fifo-friends/recommendations"))
                    .get()
                    .build()
                http.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    val bodyStr = resp.body?.string() ?: return@withContext emptyList()
                    val obj = JSONObject(bodyStr)
                    val arr = obj.optJSONArray("recommendations") ?: JSONArray()
                    (0 until arr.length()).map { idx ->
                        val item = arr.getJSONObject(idx)
                        val tagsArr = item.optJSONArray("sharedInterestTags") ?: JSONArray()
                        val topicsArr = item.optJSONArray("suggestedConversationTopics") ?: JSONArray()
                        FriendRecommendation(
                            friendId = item.optString("userId", "usr_friend_${idx}"),
                            fullName = item.optString("fullName", "Amigo Fifo"),
                            age = item.optInt("age", 68),
                            quote = item.optString("quote", "Me encanta compartir aficiones."),
                            avatarBgHex = item.optLong("avatarBgHex", 0xFF10B981),
                            bioAi = item.optString("bioAi", ""),
                            sharedTags = (0 until tagsArr.length()).map { tagsArr.getString(it) },
                            candTags = emptyList(),
                            affinityScore = item.optDouble("affinityScore", 0.0).toFloat(),
                            suggestedTopic = if (topicsArr.length() > 0) topicsArr.getString(0) else "Gustos afines",
                            friendshipStatus = "suggested"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(FIFO_BACKEND_TAG, "Error consultando recomendaciones de amigos: ${e.message}")
                emptyList()
            }
        }

    suspend fun createCommunityActivity(item: CommunityActivityItem): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("description", item.description)
                put("spots_label", item.spotsLabel)
                put("spots_max", item.spotsMax)
                put("spots_taken", item.spotsTaken)
                put("date_label", item.dateLabel)
                put("location_name", item.locationName)
                put("distance_label", item.distanceLabel)
                put("icon_category", item.iconCategory)
                put("organizer_id", item.organizerId)
                put("organizer_name", item.organizerName)
                put("organizer_type", item.organizerType)
                put("target_tastes", JSONArray(item.targetTastes))
            }
            http.postOrPatchJson(http.url("/activities"), "POST", json)
        } catch (e: Exception) {
            Log.d(FIFO_BACKEND_TAG, "Error sync createCommunityActivity: ${e.message}")
            false
        }
    }

    suspend fun toggleJoinActivity(activityId: String, userId: String, join: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val url = http.url("/activities/$activityId/join?user_id=$userId")
                val req = Request.Builder().url(url)
                if (join) {
                    req.post(http.jsonBody(JSONObject()))
                } else {
                    req.delete()
                }
                http.client.newCall(req.build()).execute().use { it.isSuccessful }
            } catch (e: Exception) {
                Log.d(FIFO_BACKEND_TAG, "Error sync toggleJoinActivity: ${e.message}")
                false
            }
        }
}
