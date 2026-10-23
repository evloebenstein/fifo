package com.fifo.voicepipeline.data.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.fifo.voicepipeline.data.memory.ConversationJournal
import com.fifo.voicepipeline.data.memory.JournalEntry
import com.fifo.voicepipeline.data.memory.JournalStatus

/**
 * Adaptador SQLite del diario durable.
 *
 * Cada operación es síncrona porque el turno debe quedar persistido antes de
 * comenzar la locución. El pipeline lo invoca desde un scope de IO.
 */
class SqliteConversationJournal(context: Context) : ConversationJournal {
    private val helper = FifoDatabaseHelper.getInstance(context.applicationContext)
    private val writableDatabase: SQLiteDatabase
        get() = helper.writableDatabase
    private val readableDatabase: SQLiteDatabase
        get() = helper.readableDatabase

    override fun append(entry: JournalEntry): JournalEntry {
        val cv = ContentValues().apply {
            put("id", entry.id)
            put("user_id", entry.userId)
            put("session_id", entry.sessionId)
            put("user_text", entry.userText)
            put("assistant_text", entry.assistantText)
            put("created_at", entry.createdAt)
            put("status", entry.status.name)
            put("attempts", entry.attempts)
            put("next_attempt_at", entry.nextAttemptAt)
            if (entry.lastError == null) putNull("last_error") else put("last_error", entry.lastError)
            put("needs_repair", if (entry.needsRepair) 1 else 0)
            if (entry.repairText == null) putNull("repair_text") else put("repair_text", entry.repairText)
            if (entry.repairedAt == null) putNull("repaired_at") else put("repaired_at", entry.repairedAt)
        }
        val rowId = writableDatabase.insertWithOnConflict(
            ConversationJournalSchema.TABLE,
            null,
            cv,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        check(rowId != -1L) { "No se pudo persistir el turno ${entry.id}" }
        return entry
    }

    override fun recent(limit: Int): List<JournalEntry> {
        val list = mutableListOf<JournalEntry>()
        readableDatabase.rawQuery(
            "SELECT * FROM ${ConversationJournalSchema.TABLE} " +
                "ORDER BY created_at DESC, rowid DESC LIMIT ?",
            arrayOf(limit.coerceAtLeast(0).toString())
        ).use { cursor ->
            while (cursor.moveToNext()) list.add(readEntry(cursor))
        }
        return list
    }

    override fun pending(now: Long, limit: Int): List<JournalEntry> {
        val list = mutableListOf<JournalEntry>()
        readableDatabase.rawQuery(
            "SELECT * FROM ${ConversationJournalSchema.TABLE} " +
                "WHERE status = ? AND next_attempt_at <= ? " +
                "ORDER BY created_at ASC, rowid ASC LIMIT ?",
            arrayOf(JournalStatus.PENDING.name, now.toString(), limit.coerceAtLeast(0).toString())
        ).use { cursor ->
            while (cursor.moveToNext()) list.add(readEntry(cursor))
        }
        return list
    }

    override fun markConsolidated(id: String) {
        val cv = ContentValues().apply {
            put("status", JournalStatus.CONSOLIDATED.name)
            putNull("last_error")
        }
        writableDatabase.update(ConversationJournalSchema.TABLE, cv, "id = ?", arrayOf(id))
    }

    override fun markRetry(id: String, attempts: Int, nextAttemptAt: Long, error: String) {
        val cv = ContentValues().apply {
            put("status", JournalStatus.PENDING.name)
            put("attempts", attempts)
            put("next_attempt_at", nextAttemptAt)
            put("last_error", error)
        }
        writableDatabase.update(ConversationJournalSchema.TABLE, cv, "id = ?", arrayOf(id))
    }

    override fun markNeedsRepair(id: String) {
        writableDatabase.update(
            ConversationJournalSchema.TABLE,
            ContentValues().apply { put("needs_repair", 1) },
            "id = ?",
            arrayOf(id)
        )
    }

    override fun markRepaired(id: String, repairText: String, repairedAt: Long) {
        val cv = ContentValues().apply {
            // Una corrección puede llegar después de que el turno ya se había
            // consolidado. Se reencola para que la memoria aprenda la versión
            // corregida; los IDs estables evitan duplicar fragmentos/conversaciones.
            put("status", JournalStatus.PENDING.name)
            put("attempts", 0)
            put("next_attempt_at", 0L)
            putNull("last_error")
            put("needs_repair", 0)
            put("repair_text", repairText)
            put("repaired_at", repairedAt)
        }
        writableDatabase.update(ConversationJournalSchema.TABLE, cv, "id = ?", arrayOf(id))
    }

    private fun readEntry(cursor: Cursor): JournalEntry {
        fun text(column: String): String = cursor.getString(cursor.getColumnIndexOrThrow(column))
        fun nullableText(column: String): String? {
            val index = cursor.getColumnIndexOrThrow(column)
            return if (cursor.isNull(index)) null else cursor.getString(index)
        }
        fun nullableLong(column: String): Long? {
            val index = cursor.getColumnIndexOrThrow(column)
            return if (cursor.isNull(index)) null else cursor.getLong(index)
        }
        val status = try {
            JournalStatus.valueOf(text("status"))
        } catch (_: Exception) {
            JournalStatus.PENDING
        }
        return JournalEntry(
            id = text("id"),
            userId = text("user_id"),
            sessionId = text("session_id"),
            userText = text("user_text"),
            assistantText = text("assistant_text"),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
            status = status,
            attempts = cursor.getInt(cursor.getColumnIndexOrThrow("attempts")),
            nextAttemptAt = cursor.getLong(cursor.getColumnIndexOrThrow("next_attempt_at")),
            lastError = nullableText("last_error"),
            needsRepair = cursor.getInt(cursor.getColumnIndexOrThrow("needs_repair")) == 1,
            repairText = nullableText("repair_text"),
            repairedAt = nullableLong("repaired_at")
        )
    }
}
