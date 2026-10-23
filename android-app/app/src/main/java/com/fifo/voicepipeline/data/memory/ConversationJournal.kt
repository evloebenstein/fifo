package com.fifo.voicepipeline.data.memory

import java.util.UUID

/**
 * Estado de durabilidad de un turno dentro del diario conversacional.
 *
 * PENDING: el turno ya está persistido en disco, pero aún no se consolidó en memoria.
 * CONSOLIDATED: el turno ya fue procesado por el consolidador (o no requería memoria nueva).
 */
enum class JournalStatus {
    PENDING,
    CONSOLIDATED
}

/**
 * Un turno completo (usuario + Fifo) registrado antes de comenzar la locución.
 *
 * Los campos de reparación permiten corregir un turno erróneo más adelante sin
 * perder el contexto original. `needsRepair` no borra ni reescribe el texto: la
 * corrección se guarda aparte para que la memoria pueda auditar el error.
 */
data class JournalEntry(
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val sessionId: String,
    val userText: String,
    val assistantText: String,
    val createdAt: Long,
    val status: JournalStatus = JournalStatus.PENDING,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0L,
    val lastError: String? = null,
    val needsRepair: Boolean = false,
    val repairText: String? = null,
    val repairedAt: Long? = null
)

/**
 * Puerto durable del diario de turnos.
 *
 * La implementación de producción es SQLite; la de pruebas es en memoria.
 * `append` es síncrono a propósito: el turno debe quedar en disco antes de
 * empezar a hablar, no después.
 */
interface ConversationJournal {
    fun append(entry: JournalEntry): JournalEntry

    /** Turnos más recientes, del más nuevo al más viejo. */
    fun recent(limit: Int = 4): List<JournalEntry>

    /** Turnos pendientes cuyo backoff ya venció. */
    fun pending(now: Long, limit: Int = 20): List<JournalEntry>

    fun markConsolidated(id: String)

    fun markRetry(id: String, attempts: Int, nextAttemptAt: Long, error: String)

    fun markNeedsRepair(id: String)

    fun markRepaired(id: String, repairText: String, repairedAt: Long)
}

/**
 * Implementación determinista para pruebas JVM y para el arranque sin SQLite.
 * No se usa como fuente de verdad en producción.
 */
class InMemoryConversationJournal : ConversationJournal {
    private val lock = Any()
    private val entries = mutableListOf<JournalEntry>()

    override fun append(entry: JournalEntry): JournalEntry = synchronized(lock) {
        val index = entries.indexOfFirst { it.id == entry.id }
        if (index >= 0) entries[index] = entry else entries.add(entry)
        entry
    }

    override fun recent(limit: Int): List<JournalEntry> = synchronized(lock) {
        entries.asReversed().take(limit.coerceAtLeast(0))
    }

    override fun pending(now: Long, limit: Int): List<JournalEntry> = synchronized(lock) {
        entries.asSequence()
            .filter { it.status == JournalStatus.PENDING && it.nextAttemptAt <= now }
            .take(limit.coerceAtLeast(0))
            .toList()
    }

    override fun markConsolidated(id: String) = update(id) {
        it.copy(status = JournalStatus.CONSOLIDATED, lastError = null)
    }

    override fun markRetry(id: String, attempts: Int, nextAttemptAt: Long, error: String) = update(id) {
        it.copy(
            status = JournalStatus.PENDING,
            attempts = attempts,
            nextAttemptAt = nextAttemptAt,
            lastError = error
        )
    }

    override fun markNeedsRepair(id: String) = update(id) {
        it.copy(needsRepair = true)
    }

    override fun markRepaired(id: String, repairText: String, repairedAt: Long) = update(id) {
        it.copy(
            status = JournalStatus.PENDING,
            attempts = 0,
            nextAttemptAt = 0L,
            lastError = null,
            needsRepair = false,
            repairText = repairText,
            repairedAt = repairedAt
        )
    }

    /** Solo para aserciones de prueba. */
    fun all(): List<JournalEntry> = synchronized(lock) { entries.toList() }

    private fun update(id: String, transform: (JournalEntry) -> JournalEntry) {
        synchronized(lock) {
            val index = entries.indexOfFirst { it.id == id }
            if (index >= 0) entries[index] = transform(entries[index])
        }
    }
}
