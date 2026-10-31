package com.fifo.voicepipeline.network

import android.util.Log
import com.fifo.voicepipeline.network.FifoBackendClient.SyncedUserBundle
import com.fifo.voicepipeline.data.model.ConversationFragment
import com.fifo.voicepipeline.data.model.DailyRoutine
import com.fifo.voicepipeline.data.model.FifoDeviceLocation
import com.fifo.voicepipeline.data.model.FifoMemoryItem
import com.fifo.voicepipeline.data.model.FifoReminderItem
import com.fifo.voicepipeline.data.model.FifoTasteStory
import com.fifo.voicepipeline.data.model.PastConversationItem
import com.fifo.voicepipeline.data.model.RelationalContact
import com.fifo.voicepipeline.data.model.UserProfileData
import com.fifo.voicepipeline.data.model.UserSocialPost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Salud del backend y descarga del bundle completo de un usuario.
 *
 * `checkHealth` conserva la conmutación USB (`127.0.0.1`) / emulador
 * (`10.0.2.2`) sobre la `baseUrl` compartida.
 */
internal class BackendSyncAdapter(private val http: BackendHttp) {

    suspend fun checkHealth(): Boolean = withContext(Dispatchers.IO) {
        if (http.isHealthy(http.baseUrl)) return@withContext true

        // Si falla con 127.0.0.1 (USB adb reverse), probar con 10.0.2.2 (emulador Android)
        if (http.baseUrl.contains("127.0.0.1")) {
            val emuUrl = http.baseUrl.replace("127.0.0.1", "10.0.2.2")
            if (http.isHealthy(emuUrl)) {
                Log.i(FIFO_BACKEND_TAG, "Conectado exitosamente con emulador Android: $emuUrl")
                http.baseUrl = emuUrl
                return@withContext true
            }
        } else if (http.baseUrl.contains("10.0.2.2")) {
            val usbUrl = http.baseUrl.replace("10.0.2.2", "127.0.0.1")
            if (http.isHealthy(usbUrl)) {
                Log.i(FIFO_BACKEND_TAG, "Conectado exitosamente vía USB / adb reverse: $usbUrl")
                http.baseUrl = usbUrl
                return@withContext true
            }
        }
        Log.d(FIFO_BACKEND_TAG, "Backend Docker no alcanzable en ${http.baseUrl}")
        false
    }

    suspend fun fetchUserBundle(userId: String): SyncedUserBundle? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(http.url("/users/$userId/sync"))
                .get()
                .build()

            http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val bodyStr = resp.body?.string() ?: return@withContext null
                val root = JSONObject(bodyStr)

                val pObj = root.getJSONObject("userProfile")
                val interestTagsArr = pObj.optJSONArray("interestTags") ?: JSONArray()
                val interestTags = (0 until interestTagsArr.length()).map { interestTagsArr.getString(it) }
                val profile = UserProfileData(
                    id = pObj.optString("id", userId),
                    fullName = pObj.optString("fullName", ""),
                    birthDate = pObj.optString("birthDate", ""),
                    birthYear = pObj.optInt("birthYear", 0),
                    estimatedAge = pObj.optInt("estimatedAge", 0),
                    genderIdentity = pObj.optString("genderIdentity", ""),
                    city = pObj.optString("city", ""),
                    bioAi = pObj.optString("bioAi", ""),
                    emergencyContactName = pObj.optString("emergencyContactName", ""),
                    emergencyContactPhone = pObj.optString("emergencyContactPhone", ""),
                    preferredAddress = pObj.optString("preferredAddress", ""),
                    interestTags = interestTags,
                    email = pObj.optString("email", "")
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
                        lastConnectedTime = it.optString("lastConnectedTime", "Desconectado"),
                        lastKnownLatitude = it.optDouble("lastKnownLatitude", 0.0),
                        lastKnownLongitude = it.optDouble("lastKnownLongitude", 0.0),
                        lastKnownAddress = it.optString("lastKnownAddress", "Sin registro de ubicación"),
                        lastKnownRoom = it.optString("lastKnownRoom", "Sin registrar"),
                        signalStrengthRssi = it.optInt("signalStrengthRssi", 0),
                        isBeeping = it.optBoolean("isBeeping", false)
                    )
                }

                val contactsArr = root.optJSONArray("relationalContacts") ?: JSONArray()
                val relationalContacts = (0 until contactsArr.length()).map { idx ->
                    val c = contactsArr.getJSONObject(idx)
                    val linkedIdsArr = c.optJSONArray("linkedConversationIds") ?: JSONArray()
                    val linkedConversationIds = (0 until linkedIdsArr.length()).map { linkedIdsArr.getString(it) }
                    RelationalContact(
                        id = c.optString("id"),
                        contactName = c.optString("contactName"),
                        phoneNumber = if (c.isNull("phoneNumber")) null else c.optString("phoneNumber"),
                        relationshipRole = c.optString("relationshipRole"),
                        closenessScore = c.optDouble("closenessScore", 0.5).toFloat(),
                        trustTier = c.optInt("trustTier", 2),
                        emotionalValence = c.optString("emotionalValence", "afectuoso"),
                        contextualMemory = if (c.isNull("contextualMemory")) null else c.optString("contextualMemory"),
                        mentionCount = c.optInt("mentionCount", 1),
                        lastMentionedAt = c.optString("lastMentionedAt", ""),
                        linkedConversationIds = linkedConversationIds
                    )
                }

                val routinesArr = root.optJSONArray("dailyRoutines") ?: JSONArray()
                val dailyRoutines = (0 until routinesArr.length()).map { idx ->
                    val r = routinesArr.getJSONObject(idx)
                    DailyRoutine(
                        id = r.optString("id"),
                        routineName = r.optString("routineName"),
                        category = r.optString("category", "medication"),
                        timeAnchor = r.optString("timeAnchor", "morning"),
                        typicalTimeStr = if (r.isNull("typicalTimeStr")) null else r.optString("typicalTimeStr"),
                        frequencyRule = r.optString("frequencyRule", "daily"),
                        confidenceScore = r.optDouble("confidenceScore", 0.8).toFloat(),
                        notes = if (r.isNull("notes")) null else r.optString("notes"),
                        isActive = r.optBoolean("isActive", true)
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
                    deviceLocation = devLoc,
                    relationalContacts = relationalContacts,
                    dailyRoutines = dailyRoutines
                )
            }
        } catch (e: Exception) {
            Log.w(FIFO_BACKEND_TAG, "Error sincronizando usuario $userId desde REST API: ${e.message}")
            null
        }
    }

    private fun parseSocialPosts(arr: JSONArray): List<UserSocialPost> {
        return (0 until arr.length()).map { idx ->
            val p = arr.getJSONObject(idx)
            UserSocialPost(
                id = p.optString("id"),
                author = p.optString("author", ""),
                age = p.optInt("age", 0),
                category = p.optString("category", "Bienestar"),
                timeAgo = p.optString("timeAgo", "Hoy"),
                content = p.optString("content", ""),
                likes = p.optInt("likes", 0),
                commentsCount = p.optInt("commentsCount", 0),
                accentColorHex = p.optLong("accentColorHex", 0xFF38BDF8)
            )
        }
    }
}
