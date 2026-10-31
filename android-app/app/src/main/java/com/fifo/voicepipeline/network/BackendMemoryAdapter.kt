package com.fifo.voicepipeline.network

import android.util.Log
import com.fifo.voicepipeline.data.memory.MentionedPersonExtraction
import com.fifo.voicepipeline.data.model.BackendConsolidationResult
import com.fifo.voicepipeline.data.model.DeepContextResult
import com.fifo.voicepipeline.data.model.FifoMemoryItem
import com.fifo.voicepipeline.data.model.FifoReminderItem
import com.fifo.voicepipeline.data.model.FifoTasteStory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Consolidación profunda de sesiones y recuperación de contexto (GraphRAG).
 */
internal class BackendMemoryAdapter(private val http: BackendHttp) {

    suspend fun analyzeAndConsolidateSession(
        userId: String,
        turns: List<Pair<String, String>>,
        durationSeconds: Int = 180,
        primaryTag: String = "Conversación",
        mentionedPersons: List<MentionedPersonExtraction> = emptyList(),
        groqApiKey: String? = null
    ): BackendConsolidationResult? = withContext(Dispatchers.IO) {
        try {
            val jsonTurns = JSONArray()
            turns.forEach { (speaker, text) ->
                jsonTurns.put(JSONObject().apply {
                    put("role", speaker)
                    put("text", text)
                })
            }
            val jsonMentions = JSONArray()
            mentionedPersons.forEach { p ->
                jsonMentions.put(JSONObject().apply {
                    put("name", p.name)
                    p.inferredRole?.let { put("inferred_role", it) }
                    put("context_snippet", p.contextSnippet)
                    put("emotional_valence", p.emotionalValence)
                    put("closeness_delta", p.closenessDelta.toDouble())
                })
            }
            val payload = JSONObject().apply {
                put("turns", jsonTurns)
                put("duration_seconds", durationSeconds)
                put("primary_tag", primaryTag)
                put("mentioned_persons", jsonMentions)
                groqApiKey?.let { put("groq_api_key", it) }
            }
            val req = Request.Builder()
                .url(http.url("/users/$userId/conversations/analyze-and-consolidate"))
                .post(http.jsonBody(payload))
                .build()
            http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val bodyStr = resp.body?.string() ?: return@withContext null
                val obj = JSONObject(bodyStr)
                val convId = obj.optString("conversation_id", "")
                val updatedBio = if (obj.isNull("updated_bio_ai")) null else obj.optString("updated_bio_ai")

                val tagsArr = obj.optJSONArray("interest_tags") ?: JSONArray()
                val interestTags = (0 until tagsArr.length()).map { tagsArr.getString(it) }

                val memArr = obj.optJSONArray("memories_added") ?: JSONArray()
                val memoriesAdded = (0 until memArr.length()).map { idx ->
                    val m = memArr.getJSONObject(idx)
                    FifoMemoryItem(
                        id = m.optString("id", "mem_${java.util.UUID.randomUUID()}"),
                        emoji = m.optString("emoji", "🌺"),
                        title = m.optString("title", "Recuerdo"),
                        detail = m.optString("detail", ""),
                        learnedDate = "Aprendido en la última charla"
                    )
                }

                val storiesArr = obj.optJSONArray("taste_stories_added") ?: JSONArray()
                val tasteStoriesAdded = (0 until storiesArr.length()).map { idx ->
                    val s = storiesArr.getJSONObject(idx)
                    val sTagsArr = s.optJSONArray("tags") ?: JSONArray()
                    val sTags = (0 until sTagsArr.length()).map { sTagsArr.getString(it) }
                    FifoTasteStory(
                        id = s.optString("id", "taste_${java.util.UUID.randomUUID()}"),
                        title = s.optString("title", "Gusto especial"),
                        subtitle = s.optString("subtitle", ""),
                        description = s.optString("description", ""),
                        tags = if (sTags.isNotEmpty()) sTags else listOf("Gusto"),
                        iconCategory = s.optString("icon_category", "nature"),
                        learnedFrom = s.optString("learned_from", "Charla reciente con Fifo")
                    )
                }

                val remArr = obj.optJSONArray("reminders_added") ?: JSONArray()
                val remindersAdded = (0 until remArr.length()).map { idx ->
                    val r = remArr.getJSONObject(idx)
                    FifoReminderItem(
                        id = r.optString("id", "rem_${java.util.UUID.randomUUID()}"),
                        title = r.optString("title", "Recordatorio"),
                        timeStr = r.optString("time_str", "en un momento"),
                        category = r.optString("category", "medication"),
                        isCompleted = false
                    )
                }

                BackendConsolidationResult(
                    success = true,
                    serverConversationId = convId,
                    updatedBioAi = updatedBio,
                    interestTags = interestTags,
                    memoriesAdded = memoriesAdded,
                    tasteStoriesAdded = tasteStoriesAdded,
                    remindersAdded = remindersAdded
                )
            }
        } catch (e: Exception) {
            Log.w(FIFO_BACKEND_TAG, "Error enviando sesión para consolidación profunda: ${e.message}")
            null
        }
    }

    suspend fun queryDeepContextFromServer(userId: String, query: String): DeepContextResult? =
        withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply { put("query", query) }
                val req = Request.Builder()
                    .url(http.url("/users/$userId/recall-context"))
                    .post(http.jsonBody(payload))
                    .build()
                http.client.newCall(req).execute().use { resp ->
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
                Log.d(FIFO_BACKEND_TAG, "Fallback local en recall_past_context: ${e.message}")
                null
            }
        }
}
