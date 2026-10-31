package com.fifo.voicepipeline.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.fifo.voicepipeline.data.model.FifoMemoryItem
import java.util.UUID

/**
 * DAO de recuerdos: `memories`.
 *
 * No conoce `Context` ni el ciclo de vida de la base. El helper le pasa las
 * conexiones ya abiertas.
 */
class MemoryDao(
    private val readableDatabase: () -> SQLiteDatabase,
    private val writableDatabase: () -> SQLiteDatabase
) {
    fun getMemories(userId: String): List<FifoMemoryItem> {
        val list = mutableListOf<FifoMemoryItem>()
        readableDatabase().rawQuery(
            "SELECT id, emoji, title, detail, learned_date_label " +
                "FROM memories WHERE user_id = ? ORDER BY created_at DESC",
            arrayOf(userId)
        ).use { cur ->
            while (cur.moveToNext()) {
                list.add(
                    FifoMemoryItem(
                        id = cur.getString(0),
                        emoji = cur.getString(1) ?: "⭐",
                        title = cur.getString(2),
                        detail = cur.getString(3),
                        learnedDate = cur.getString(4) ?: ""
                    )
                )
            }
        }
        return list
    }

    fun addMemory(userId: String, item: FifoMemoryItem) {
        val db = writableDatabase()
        val cv = ContentValues().apply {
            put("id", item.id.ifBlank { "mem_${UUID.randomUUID().toString().take(8)}" })
            put("user_id", userId)
            put("emoji", item.emoji)
            put("title", item.title)
            put("detail", item.detail)
            put("learned_date_label", item.learnedDate)
        }
        db.insertWithOnConflict("memories", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Elimina un recuerdo del usuario. Devuelve `true` si borró alguna fila. */
    fun deleteMemory(userId: String, id: String): Boolean =
        writableDatabase().delete("memories", "user_id = ? AND id = ?", arrayOf(userId, id)) > 0
}
