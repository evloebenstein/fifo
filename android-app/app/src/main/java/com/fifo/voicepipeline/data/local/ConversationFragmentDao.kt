package com.fifo.voicepipeline.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.fifo.voicepipeline.ui.model.ConversationFragment
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * DAO de fragmentos compactos de conversación: `conversation_fragments`.
 *
 * No conoce `Context` ni el ciclo de vida de la base. El helper le pasa las
 * conexiones ya abiertas y comparte el `Gson` para serializar temas y entidades.
 */
class ConversationFragmentDao(
    private val readableDatabase: () -> SQLiteDatabase,
    private val writableDatabase: () -> SQLiteDatabase,
    private val gson: Gson = Gson()
) {
    fun getConversationFragments(userId: String): List<ConversationFragment> {
        val list = mutableListOf<ConversationFragment>()
        readableDatabase().rawQuery(
            "SELECT id, server_conversation_id, key_topics, named_entities, " +
                "detected_mood, compact_summary, primary_tag, duration_seconds, recorded_at " +
                "FROM conversation_fragments WHERE user_id = ? ORDER BY recorded_at DESC",
            arrayOf(userId)
        ).use { cur ->
            while (cur.moveToNext()) {
                val topicsJson = cur.getString(2) ?: "[]"
                val entitiesJson = cur.getString(3) ?: "[]"
                val topics: List<String> = try {
                    gson.fromJson(topicsJson, object : TypeToken<List<String>>() {}.type)
                } catch (e: Exception) {
                    emptyList()
                }
                val entities: List<String> = try {
                    gson.fromJson(entitiesJson, object : TypeToken<List<String>>() {}.type)
                } catch (e: Exception) {
                    emptyList()
                }
                list.add(
                    ConversationFragment(
                        id = cur.getString(0),
                        serverConversationId = cur.getString(1) ?: "",
                        keyTopics = topics,
                        namedEntities = entities,
                        detectedMood = cur.getString(4) ?: "neutral",
                        compactSummary = cur.getString(5) ?: "",
                        primaryTag = cur.getString(6) ?: "General",
                        durationSeconds = cur.getInt(7),
                        recordedAt = cur.getString(8) ?: ""
                    )
                )
            }
        }
        return list
    }

    fun addConversationFragment(userId: String, fragment: ConversationFragment) {
        val cv = ContentValues().apply {
            put("id", fragment.id.ifBlank { "frag_${UUID.randomUUID().toString().take(8)}" })
            put("user_id", userId)
            put("server_conversation_id", fragment.serverConversationId)
            put("key_topics", gson.toJson(fragment.keyTopics))
            put("named_entities", gson.toJson(fragment.namedEntities))
            put("detected_mood", fragment.detectedMood)
            put("compact_summary", fragment.compactSummary)
            put("primary_tag", fragment.primaryTag)
            put("duration_seconds", fragment.durationSeconds)
            if (fragment.recordedAt.isNotBlank()) put("recorded_at", fragment.recordedAt)
        }
        writableDatabase().insertWithOnConflict(
            "conversation_fragments",
            null,
            cv,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }
}
