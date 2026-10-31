package com.fifo.voicepipeline.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.fifo.voicepipeline.data.model.FifoReminderItem
import java.util.UUID

/**
 * DAO de recordatorios y eventos: `reminders_and_events`.
 *
 * No conoce `Context` ni el ciclo de vida de la base. El helper le pasa las
 * conexiones ya abiertas.
 */
class ReminderDao(
    private val readableDatabase: () -> SQLiteDatabase,
    private val writableDatabase: () -> SQLiteDatabase
) {
    fun getReminders(userId: String): List<FifoReminderItem> {
        val list = mutableListOf<FifoReminderItem>()
        readableDatabase().rawQuery(
            "SELECT id, title, time_str, category, is_completed " +
                "FROM reminders_and_events WHERE user_id = ? " +
                "ORDER BY is_completed ASC, time_str ASC",
            arrayOf(userId)
        ).use { cur ->
            while (cur.moveToNext()) {
                list.add(
                    FifoReminderItem(
                        id = cur.getString(0),
                        title = cur.getString(1),
                        timeStr = cur.getString(2),
                        category = cur.getString(3) ?: "medication",
                        isCompleted = cur.getInt(4) == 1
                    )
                )
            }
        }
        return list
    }

    fun addReminder(userId: String, reminder: FifoReminderItem) {
        val db = writableDatabase()
        val cv = ContentValues().apply {
            put("id", reminder.id.ifBlank { "rem_${UUID.randomUUID().toString().take(8)}" })
            put("user_id", userId)
            put("title", reminder.title)
            put("time_str", reminder.timeStr)
            put("category", reminder.category)
            put("is_completed", if (reminder.isCompleted) 1 else 0)
            put("spoken_text", "Es hora de su recordatorio: ${reminder.title}")
        }
        db.insertWithOnConflict("reminders_and_events", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun completeReminder(userId: String, reminderId: String, completed: Boolean = true) {
        val db = writableDatabase()
        val cv = ContentValues().apply {
            put("is_completed", if (completed) 1 else 0)
        }
        db.update("reminders_and_events", cv, "user_id = ? AND id = ?", arrayOf(userId, reminderId))
    }

    fun deleteReminder(userId: String, reminderId: String) {
        writableDatabase().delete(
            "reminders_and_events",
            "user_id = ? AND id = ?",
            arrayOf(userId, reminderId)
        )
    }
}
