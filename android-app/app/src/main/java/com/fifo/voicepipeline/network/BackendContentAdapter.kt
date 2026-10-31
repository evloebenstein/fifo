package com.fifo.voicepipeline.network

import com.fifo.voicepipeline.data.model.DailyRoutine
import com.fifo.voicepipeline.data.model.FifoTasteStory
import org.json.JSONArray
import org.json.JSONObject

/**
 * Escrituras de contenido del usuario: gustos, historias, posts, recuerdos,
 * recordatorios, conversaciones y rutinas diarias.
 */
internal class BackendContentAdapter(private val http: BackendHttp) {

    suspend fun postTaste(userId: String, action: String, tasteName: String) =
        http.postOrPatchJson(http.url("/users/$userId/tastes"), "POST", JSONObject().apply {
            put("action", action)
            put("taste_name", tasteName)
        })

    suspend fun postTasteStory(userId: String, story: FifoTasteStory) =
        http.postOrPatchJson(http.url("/users/$userId/taste-stories"), "POST", JSONObject().apply {
            put("title", story.title)
            put("subtitle", story.subtitle)
            put("description", story.description)
            put("tags", JSONArray(story.tags))
            put("icon_category", story.iconCategory)
            put("learned_from", story.learnedFrom)
        })

    suspend fun postSocialPost(userId: String, content: String, category: String) =
        http.postOrPatchJson(http.url("/users/$userId/social-posts"), "POST", JSONObject().apply {
            put("content", content)
            put("category", category)
        })

    suspend fun postReminder(userId: String, title: String, timeStr: String, category: String) =
        http.postOrPatchJson(http.url("/users/$userId/reminders"), "POST", JSONObject().apply {
            put("title", title)
            put("time_str", timeStr)
            put("category", category)
        })

    suspend fun postMemory(userId: String, emoji: String, title: String, detail: String) =
        http.postOrPatchJson(http.url("/users/$userId/memories"), "POST", JSONObject().apply {
            put("emoji", emoji)
            put("title", title)
            put("detail", detail)
        })

    suspend fun postDailyRoutine(userId: String, routine: DailyRoutine) =
        http.postOrPatchJson(http.url("/users/$userId/daily-routines"), "POST", JSONObject().apply {
            put("routine_name", routine.routineName)
            put("time_period", routine.timeAnchor)
            routine.typicalTimeStr?.let { put("preferred_time", it) }
            put("frequency", routine.frequencyRule)
            put("confidence_score", routine.confidenceScore.toDouble())
            put("action_category", routine.category)
            routine.notes?.let { put("description", it) }
            put("reminder_associated", routine.reminderAssociated)
        })

    suspend fun patchReminderComplete(reminderId: String) =
        http.postOrPatchJson(http.url("/reminders/$reminderId/complete"), "PATCH", JSONObject())

    suspend fun deleteReminder(reminderId: String) =
        http.postOrPatchJson(http.url("/reminders/$reminderId"), "DELETE", JSONObject())

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
    ) = http.postOrPatchJson(http.url("/users/$userId/conversations"), "POST", JSONObject().apply {
        put("title", title)
        put("summary", summary)
        put("primary_tag", primaryTag)
        put("duration_seconds", durationSeconds)
        put("detected_mood", detectedMood)
        put("key_topics", JSONArray(keyTopics))
        put("named_entities", JSONArray(namedEntities))
        fullTranscript?.let { put("full_transcript", it) }
    })
}
