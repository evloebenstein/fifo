package com.fifo.voicepipeline.data.local

import android.content.Context
import com.fifo.voicepipeline.data.memory.ConversationJournal
import com.fifo.voicepipeline.data.memory.JournalEntry

/**
 * Adaptador SQLite del diario durable.
 *
 * Cada operación es síncrona porque el turno debe quedar persistido antes de
 * comenzar la locución. El pipeline lo invoca desde un scope de IO.
 */
class SqliteConversationJournal(context: Context) : ConversationJournal {
    private val helper = FifoDatabaseHelper.getInstance(context.applicationContext)

    override fun append(entry: JournalEntry): JournalEntry {
        val rowId = helper.insertJournalEntry(entry)
        check(rowId != -1L) { "No se pudo persistir el turno ${entry.id}" }
        return entry
    }

    override fun recent(limit: Int): List<JournalEntry> =
        helper.getRecentJournalEntries(limit)

    override fun pending(now: Long, limit: Int): List<JournalEntry> =
        helper.getPendingJournalEntries(now, limit)

    override fun markConsolidated(id: String) {
        helper.markJournalConsolidated(id)
    }

    override fun markRetry(id: String, attempts: Int, nextAttemptAt: Long, error: String) {
        helper.markJournalRetry(id, attempts, nextAttemptAt, error)
    }

    override fun markNeedsRepair(id: String) {
        helper.markJournalNeedsRepair(id)
    }

    override fun markRepaired(id: String, repairText: String, repairedAt: Long) {
        helper.markJournalRepaired(id, repairText, repairedAt)
    }
}
