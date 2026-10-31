package com.fifo.voicepipeline.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.fifo.voicepipeline.data.model.DailyRoutine
import java.util.UUID

/**
 * DAO de rutinas diarias: `user_daily_routines`.
 *
 * No conoce `Context` ni el ciclo de vida de la base. El helper le pasa las
 * conexiones ya abiertas.
 */
class DailyRoutineDao(
    private val readableDatabase: () -> SQLiteDatabase,
    private val writableDatabase: () -> SQLiteDatabase
) {
    fun getDailyRoutines(userId: String): List<DailyRoutine> {
        val list = mutableListOf<DailyRoutine>()
        readableDatabase().rawQuery(
            "SELECT id, routine_name, category, time_anchor, typical_time_str, " +
                "frequency_rule, confidence_score, notes, is_active " +
                "FROM user_daily_routines WHERE user_id = ? AND is_active = 1 " +
                "ORDER BY typical_time_str ASC, confidence_score DESC",
            arrayOf(userId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    DailyRoutine(
                        id = cursor.getString(0) ?: "",
                        routineName = cursor.getString(1) ?: "",
                        category = cursor.getString(2) ?: "medication",
                        timeAnchor = cursor.getString(3) ?: "morning",
                        typicalTimeStr = cursor.getString(4),
                        frequencyRule = cursor.getString(5) ?: "daily",
                        confidenceScore = cursor.getFloat(6),
                        notes = cursor.getString(7),
                        isActive = cursor.getInt(8) == 1
                    )
                )
            }
        }
        return list
    }

    fun addOrUpdateDailyRoutine(userId: String, routine: DailyRoutine) {
        val cv = ContentValues().apply {
            put("id", routine.id.ifBlank { "rtn_${UUID.randomUUID().toString().take(8)}" })
            put("user_id", userId)
            put("routine_name", routine.routineName)
            put("category", routine.category)
            put("time_anchor", routine.timeAnchor)
            put("typical_time_str", routine.typicalTimeStr)
            put("frequency_rule", routine.frequencyRule)
            put("confidence_score", routine.confidenceScore)
            put("notes", routine.notes)
            put("is_active", if (routine.isActive) 1 else 0)
        }
        writableDatabase().insertWithOnConflict(
            "user_daily_routines",
            null,
            cv,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }
}
