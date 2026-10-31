package com.fifo.voicepipeline.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.fifo.voicepipeline.data.model.PastConversationItem
import java.util.UUID

/**
 * DAO de conversaciones pasadas: `past_conversations`.
 *
 * No conoce `Context` ni el ciclo de vida de la base. El helper le pasa las
 * conexiones ya abiertas.
 */
class PastConversationDao(
    private val readableDatabase: () -> SQLiteDatabase,
    private val writableDatabase: () -> SQLiteDatabase
) {
    fun getPastConversations(userId: String): List<PastConversationItem> {
        val list = mutableListOf<PastConversationItem>()
        readableDatabase().rawQuery(
            "SELECT id, title, date_label, duration_label, summary, topic_tag, icon_name " +
                "FROM past_conversations WHERE user_id = ? ORDER BY recorded_at DESC",
            arrayOf(userId)
        ).use { cur ->
            while (cur.moveToNext()) {
                list.add(
                    PastConversationItem(
                        id = cur.getString(0),
                        title = cur.getString(1),
                        date = cur.getString(2) ?: "Hoy",
                        duration = cur.getString(3) ?: "5 min",
                        summary = cur.getString(4) ?: "",
                        tag = cur.getString(5) ?: "Conversación",
                        iconName = cur.getString(6) ?: "heart"
                    )
                )
            }
        }
        return list
    }

    fun addPastConversation(userId: String, item: PastConversationItem) {
        val cv = ContentValues().apply {
            put("id", item.id.ifBlank { "c_${UUID.randomUUID().toString().take(8)}" })
            put("user_id", userId)
            put("title", item.title)
            put("date_label", item.date)
            put("duration_label", item.duration)
            put("summary", item.summary)
            put("topic_tag", item.tag)
            put("icon_name", item.iconName)
        }
        writableDatabase().insertWithOnConflict(
            "past_conversations",
            null,
            cv,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun deletePastConversation(userId: String, id: String) {
        writableDatabase().delete(
            "past_conversations",
            "user_id = ? AND id = ?",
            arrayOf(userId, id)
        )
    }
}
