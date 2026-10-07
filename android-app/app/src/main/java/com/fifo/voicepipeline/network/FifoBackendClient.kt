package com.fifo.voicepipeline.network

import android.util.Log
import com.fifo.voicepipeline.ui.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cliente HTTP ligero para sincronizar FifoDataRepository y los Skills de voz
 * con el microservicio REST + MySQL 8.4 en Docker (fifo-api en puerto 8090).
 *
 * Soporta conexión directa por USB mediante `adb reverse tcp:8090 tcp:8090`
 * (http://127.0.0.1:8090) o IP LAN configurable.
 */
object FifoBackendClient {
    private const val TAG = "FifoBackendClient"

    @Volatile
    var baseUrl: String = "http://127.0.0.1:8090"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    data class SyncedUserBundle(
        val userProfile: UserProfileData,
        val tastes: List<String>,
        val tasteStories: List<FifoTasteStory>,
        val socialPosts: List<UserSocialPost>,
        val communityFeedPosts: List<UserSocialPost>,
        val memories: List<FifoMemoryItem>,
        val reminders: List<FifoReminderItem>,
        val pastConversations: List<PastConversationItem>,
        val conversationFragments: List<ConversationFragment>,
        val deviceLocation: FifoDeviceLocation?
    )

    suspend fun checkHealth(): Boolean = withContext(Dispatchers.IO) {
        if (tryHealth(baseUrl)) return@withContext true

        // Si falla con 127.0.0.1 (USB adb reverse), probar con 10.0.2.2 (emulador Android)
        if (baseUrl.contains("127.0.0.1")) {
            val emuUrl = baseUrl.replace("127.0.0.1", "10.0.2.2")
            if (tryHealth(emuUrl)) {
                Log.i(TAG, "Conectado exitosamente con emulador Android: $emuUrl")
                baseUrl = emuUrl
                return@withContext true
            }
        } else if (baseUrl.contains("10.0.2.2")) {
            val usbUrl = baseUrl.replace("10.0.2.2", "127.0.0.1")
            if (tryHealth(usbUrl)) {
                Log.i(TAG, "Conectado exitosamente vía USB / adb reverse: $usbUrl")
                baseUrl = usbUrl
                return@withContext true
            }
        }
        Log.d(TAG, "Backend Docker no alcanzable en $baseUrl")
        false
    }

    private fun tryHealth(targetUrl: String): Boolean {
        return try {
            val req = Request.Builder().url("$targetUrl/health").get().build()
            httpClient.newCall(req).execute().use { resp -> resp.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun fetchUserBundle(userId: String = "usr_lucia_01"): SyncedUserBundle? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$baseUrl/users/$userId/sync")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val bodyStr = resp.body?.string() ?: return@withContext null
                val root = JSONObject(bodyStr)

                val pObj = root.getJSONObject("userProfile")
                val profile = UserProfileData(
                    id = pObj.optString("id", userId),
                    fullName = pObj.optString("fullName", "Lucía González"),
                    birthDate = pObj.optString("birthDate", "14 de Mayo, 1958"),
                    birthYear = pObj.optInt("birthYear", 1958),
                    estimatedAge = pObj.optInt("estimatedAge", 68),
                    genderIdentity = pObj.optString("genderIdentity", "Mujer"),
                    city = pObj.optString("city", "Santiago, Chile"),
                    bioAi = pObj.optString("bioAi", ""),
                    emergencyContactName = pObj.optString("emergencyContactName", ""),
                    emergencyContactPhone = pObj.optString("emergencyContactPhone", ""),
                    preferredAddress = pObj.optString("preferredAddress", "")
                )

                val tastesArr = root.optJSONArray("tastes") ?: JSONArray()
                val tastes = (0 until tastesArr.length()).map { tastesArr.getString(it) }

                val storiesArr = root.optJSONArray("tasteStories") ?: JSONArray()
                val stories = (0 until storiesArr.length()).map { idx ->
                    val s = storiesArr.getJSONObject(idx)
                    val tagsJson = s.optJSONArray("tags") ?: JSONArray()
                    val tags = (0 until tagsJson.length()).map { tagsJson.getString(it) }
                    FifoTasteStory(
                        id = s.optString("id"),
                        title = s.optString("title"),
                        subtitle = s.optString("subtitle"),
                        description = s.optString("description"),
                        tags = tags,
                        iconCategory = s.optString("icon_category", "heart"),
                        learnedFrom = s.optString("learned_from", "Charla con Fifo")
                    )
                }

                val postsArr = root.optJSONArray("userSocialPosts") ?: JSONArray()
                val userPosts = parseSocialPosts(postsArr)

                val feedArr = root.optJSONArray("communityFeedPosts") ?: JSONArray()
                val feedPosts = parseSocialPosts(feedArr)

                val memArr = root.optJSONArray("memories") ?: JSONArray()
                val memories = (0 until memArr.length()).map { idx ->
                    val m = memArr.getJSONObject(idx)
                    FifoMemoryItem(
                        id = m.optString("id"),
                        emoji = m.optString("emoji", "⭐"),
                        title = m.optString("title"),
                        detail = m.optString("detail"),
                        learnedDate = m.optString("learnedDate", "Aprendido recientemente")
                    )
                }

                val remArr = root.optJSONArray("reminders") ?: JSONArray()
                val reminders = (0 until remArr.length()).map { idx ->
                    val r = remArr.getJSONObject(idx)
                    FifoReminderItem(
                        id = r.optString("id"),
                        title = r.optString("title"),
                        timeStr = r.optString("timeStr"),
                        category = r.optString("category", "medication"),
                        isCompleted = r.optBoolean("isCompleted", false)
                    )
                }

                val convArr = root.optJSONArray("pastConversations") ?: JSONArray()
                val pastConvs = (0 until convArr.length()).map { idx ->
                    val c = convArr.getJSONObject(idx)
                    PastConversationItem(
                        id = c.optString("id"),
                        title = c.optString("title"),
                        date = c.optString("date", "Hoy"),
                        duration = c.optString("duration", "5 min"),
                        summary = c.optString("summary"),
                        tag = c.optString("tag", "Conversación"),
                        iconName = c.optString("iconName", "heart")
                    )
                }

                val fragArr = root.optJSONArray("conversationFragments") ?: JSONArray()
                val fragments = (0 until fragArr.length()).map { idx ->
                    val f = fragArr.getJSONObject(idx)
                    val topicsJson = f.optJSONArray("keyTopics") ?: JSONArray()
                    val entitiesJson = f.optJSONArray("namedEntities") ?: JSONArray()
                    ConversationFragment(
                        id = f.optString("id"),
                        serverConversationId = f.optString("serverConversationId", ""),
                        keyTopics = (0 until topicsJson.length()).map { topicsJson.getString(it) },
                        namedEntities = (0 until entitiesJson.length()).map { entitiesJson.getString(it) },
                        detectedMood = f.optString("detectedMood", "neutral"),
                        compactSummary = f.optString("compactSummary", ""),
                        primaryTag = f.optString("primaryTag", "General"),
                        durationSeconds = f.optInt("durationSeconds", 180),
                        recordedAt = f.optString("recordedAt", "")
                    )
                }

                val devObj = root.optJSONObject("deviceLocation")
                val devLoc = devObj?.let {
                    FifoDeviceLocation(
                        isConnected = it.optBoolean("isConnected", false),
                        lastConnectedTime = it.optString("lastConnectedTime", "Hoy"),
                        lastKnownLatitude = it.optDouble("lastKnownLatitude", -33.4255),
                        lastKnownLongitude = it.optDouble("lastKnownLongitude", -70.6143),
                        lastKnownAddress = it.optString("lastKnownAddress", ""),
                        lastKnownRoom = it.optString("lastKnownRoom", ""),
                        signalStrengthRssi = it.optInt("signalStrengthRssi", -64),
                        isBeeping = it.optBoolean("isBeeping", false)
                    )
                }

                SyncedUserBundle(
                    userProfile = profile,
                    tastes = tastes,
                    tasteStories = stories,
                    socialPosts = userPosts,
                    communityFeedPosts = feedPosts,
                    memories = memories,
                    reminders = reminders,
                    pastConversations = pastConvs,
                    conversationFragments = fragments,
                    deviceLocation = devLoc
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error sincronizando usuario $userId desde REST API: ${e.message}")
            null
        }
    }

    private fun parseSocialPosts(arr: JSONArray): List<UserSocialPost> {
        return (0 until arr.length()).map { idx ->
            val p = arr.getJSONObject(idx)
            UserSocialPost(
                id = p.optString("id"),
                author = p.optString("author", "Lucía"),
                age = p.optInt("age", 68),
                category = p.optString("category", "Bienestar"),
                timeAgo = p.optString("timeAgo", "Hoy"),
                content = p.optString("content", ""),
                likes = p.optInt("likes", 0),
                commentsCount = p.optInt("commentsCount", 0),
                accentColorHex = p.optLong("accentColorHex", 0xFF38BDF8)
            )
        }
    }

    suspend fun patchDemographics(
        userId: String,
        fullName: String?,
        birthDate: String?,
        birthYear: Int?,
        gender: String?,
        city: String?
    ) = postOrPatchJson("$baseUrl/users/$userId/demographics", "PATCH", JSONObject().apply {
        fullName?.let { put("full_name", it) }
        birthDate?.let { put("birth_date", it) }
        birthYear?.let { put("birth_year", it) }
        gender?.let { put("gender_identity", it) }
        city?.let { put("city", it) }
    })

    suspend fun putBio(userId: String, newBio: String) =
        postOrPatchJson("$baseUrl/users/$userId/bio", "PUT", JSONObject().apply {
            put("bio_ai", newBio)
        })

    suspend fun postTaste(userId: String, action: String, tasteName: String) =
        postOrPatchJson("$baseUrl/users/$userId/tastes", "POST", JSONObject().apply {
            put("action", action)
            put("taste_name", tasteName)
        })

    suspend fun postTasteStory(userId: String, story: FifoTasteStory) =
        postOrPatchJson("$baseUrl/users/$userId/taste-stories", "POST", JSONObject().apply {
            put("title", story.title)
            put("subtitle", story.subtitle)
            put("description", story.description)
            put("tags", JSONArray(story.tags))
            put("icon_category", story.iconCategory)
            put("learned_from", story.learnedFrom)
        })

    suspend fun postSocialPost(userId: String, content: String, category: String) =
        postOrPatchJson("$baseUrl/users/$userId/social-posts", "POST", JSONObject().apply {
            put("content", content)
            put("category", category)
        })

    suspend fun postReminder(userId: String, title: String, timeStr: String, category: String) =
        postOrPatchJson("$baseUrl/users/$userId/reminders", "POST", JSONObject().apply {
            put("title", title)
            put("time_str", timeStr)
            put("category", category)
        })

    suspend fun postMemory(userId: String, emoji: String, title: String, detail: String) =
        postOrPatchJson("$baseUrl/users/$userId/memories", "POST", JSONObject().apply {
            put("emoji", emoji)
            put("title", title)
            put("detail", detail)
        })

    suspend fun patchReminderComplete(reminderId: String) =
        postOrPatchJson("$baseUrl/reminders/$reminderId/complete", "PATCH", JSONObject())

    suspend fun postConversation(
        userId: String,
        title: String,
        summary: String,
        primaryTag: String = "Conversación",
        durationSeconds: Int = 180,
        detectedMood: String = "tranquilo",
        keyTopics: List<String> = emptyList(),
        namedEntities: List<String> = emptyList(),
        fullTranscript: String? = null
    ) = postOrPatchJson("$baseUrl/users/$userId/conversations", "POST", JSONObject().apply {
        put("title", title)
        put("summary", summary)
        put("primary_tag", primaryTag)
        put("duration_seconds", durationSeconds)
        put("detected_mood", detectedMood)
        put("key_topics", JSONArray(keyTopics))
        put("named_entities", JSONArray(namedEntities))
        fullTranscript?.let { put("full_transcript", it) }
    })

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
    ) = postOrPatchJson("$baseUrl/users/$userId/device-location", "PATCH", JSONObject().apply {
        isConnected?.let { put("is_connected", it) }
        lastConnectedTime?.let { put("last_connected_time", it) }
        latitude?.let { put("latitude", it) }
        longitude?.let { put("longitude", it) }
        address?.let { put("address", it) }
        roomHint?.let { put("room_hint", it) }
        rssi?.let { put("rssi", it) }
        isBeeping?.let { put("is_beeping", it) }
    })

    suspend fun queryDeepContextFromServer(userId: String, query: String): DeepContextResult? =
        withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply { put("query", query) }
                val req = Request.Builder()
                    .url("$baseUrl/users/$userId/recall-context")
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val bodyStr = resp.body?.string() ?: return@withContext null
                    val obj = JSONObject(bodyStr)
                    val excArr = obj.optJSONArray("relevantExcerpts") ?: JSONArray()
                    val entArr = obj.optJSONArray("foundEntities") ?: JSONArray()
                    DeepContextResult(
                        relevantExcerpts = (0 until excArr.length()).map { excArr.getString(it) },
                        foundEntities = (0 until entArr.length()).map { entArr.getString(it) },
                        synthesizedContext = obj.optString("synthesizedContext", ""),
                        conversationsSearched = obj.optInt("conversationsSearched", 0)
                    )
                }
            } catch (e: Exception) {
                Log.d(TAG, "Fallback local en recall_past_context: ${e.message}")
                null
            }
        }

    private suspend fun postOrPatchJson(url: String, method: String, payload: JSONObject): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val body = payload.toString().toRequestBody(jsonMediaType)
                val builder = Request.Builder().url(url)
                when (method) {
                    "POST" -> builder.post(body)
                    "PUT" -> builder.put(body)
                    "PATCH" -> builder.patch(body)
                }
                httpClient.newCall(builder.build()).execute().use { it.isSuccessful }
            } catch (e: Exception) {
                Log.d(TAG, "Error enviando $method a $url: ${e.message}")
                false
            }
        }
}
