package com.fifo.voicepipeline.data.local

import android.database.sqlite.SQLiteDatabase

/**
 * Esquema del diario durable de turnos.
 *
 * El ciclo de vida de la tabla lo necesita `FifoDatabaseHelper`, pero el SQL
 * pertenece al módulo de memoria. Mantenerlo aquí evita que el helper conozca
 * detalles de la cola de consolidación.
 */
internal object ConversationJournalSchema {
    const val TABLE = "conversation_journal"

    fun create(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE (
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL,
                session_id TEXT NOT NULL,
                user_text TEXT NOT NULL,
                assistant_text TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                status TEXT NOT NULL DEFAULT 'PENDING',
                attempts INTEGER NOT NULL DEFAULT 0,
                next_attempt_at INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                needs_repair INTEGER NOT NULL DEFAULT 0,
                repair_text TEXT,
                repaired_at INTEGER
            )
        """.trimIndent())
        db.execSQL("""
            CREATE INDEX IF NOT EXISTS idx_conversation_journal_pending
            ON $TABLE(status, next_attempt_at, created_at)
        """.trimIndent())
    }
}
