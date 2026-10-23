package com.fifo.voicepipeline.pipeline.session

import com.fifo.voicepipeline.data.memory.ConversationJournal
import com.fifo.voicepipeline.data.memory.JournalEntry
import com.fifo.voicepipeline.data.memory.SessionMemoryConsolidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lo que VoiceSession necesita de la memoria durable.
 *
 * La sesión no conoce SQLite, HTTP ni el consolidador: solo registra turnos,
 * consulta los recientes para reparar y pide consolidar al cerrar la sesión.
 */
interface SessionMemory {
    fun recordTurn(
        userText: String,
        assistantText: String,
        needsRepair: Boolean = false
    ): JournalEntry?

    fun recentTurns(limit: Int = 2): List<JournalEntry>

    fun markNeedsRepair(id: String)

    fun markRepaired(id: String, repairText: String, repairedAt: Long)

    fun requestConsolidation()
}

/**
 * Coordinador de memoria durable.
 *
 * Garantías:
 * - `recordTurn` escribe en el diario antes de que VoiceSession empiece a hablar.
 * - La cola pendiente vive en el diario (SQLite en producción), no en RAM.
 * - Los fallos de consolidación se reintentan con backoff exponencial.
 * - El turno no se marca consolidado hasta que el consolidador devuelve `true`.
 *
 * Es JVM-testeable: el reloj, el diario y el consolidador son inyectables.
 */
class MemoryCoordinator(
    private val journal: ConversationJournal,
    private val consolidator: SessionMemoryConsolidator,
    private val scope: CoroutineScope,
    private val userIdProvider: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sessionId: String = "session_${java.util.UUID.randomUUID()}",
    private val baseBackoffMs: Long = 30_000L,
    private val maxBackoffMs: Long = 15 * 60_000L,
    private val retryPollMs: Long = 30_000L,
    private val batchSize: Int = 20
) : SessionMemory {

    private val drainMutex = Mutex()
    private var retryJob: Job? = null

    override fun recordTurn(
        userText: String,
        assistantText: String,
        needsRepair: Boolean
    ): JournalEntry? {
        if (userText.isBlank() || assistantText.isBlank()) return null
        val entry = JournalEntry(
            userId = userIdProvider(),
            sessionId = sessionId,
            userText = userText.trim(),
            assistantText = assistantText.trim(),
            createdAt = clock(),
            needsRepair = needsRepair
        )
        return journal.append(entry)
    }

    override fun recentTurns(limit: Int): List<JournalEntry> = journal.recent(limit)

    override fun markNeedsRepair(id: String) {
        journal.markNeedsRepair(id)
    }

    override fun markRepaired(id: String, repairText: String, repairedAt: Long) {
        journal.markRepaired(id, repairText, repairedAt)
    }

    /**
     * Dispara un intento de drenaje sin bloquear el turno actual. Si falla,
     * los turnos quedan con `nextAttemptAt` y el bucle de reintentos los retoma.
     */
    override fun requestConsolidation() {
        scope.launch { drainSafely() }
    }

    /** Arranca el bucle que recupera pendientes tras un cierre o reinicio. */
    fun start() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            drainSafely()
            while (isActive) {
                delay(retryPollMs)
                drainSafely()
            }
        }
    }

    fun stop() {
        retryJob?.cancel()
        retryJob = null
    }

    /**
     * Drena los turnos cuyo backoff venció. Es público para pruebas
     * deterministas; producción lo llama desde [start] y [requestConsolidation].
     */
    suspend fun drainDue(now: Long = clock()) {
        drainMutex.withLock {
            val pending = journal.pending(now, batchSize)
            if (pending.isEmpty()) return
            pending.groupBy { it.sessionId }.forEach { (_, turns) ->
                val success = try {
                    consolidator.consolidate(turns)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val message = e.message ?: e::class.java.simpleName
                    turns.forEach { markFailure(it, now, message) }
                    return@forEach
                }

                if (success) {
                    turns.forEach { journal.markConsolidated(it.id) }
                } else {
                    turns.forEach { markFailure(it, now, "consolidación rechazada") }
                }
            }
        }
    }

    private fun markFailure(entry: JournalEntry, now: Long, error: String) {
        val attempts = entry.attempts + 1
        journal.markRetry(
            id = entry.id,
            attempts = attempts,
            nextAttemptAt = now + backoffFor(attempts),
            error = error
        )
    }

    private suspend fun drainSafely() {
        try {
            drainDue()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // El turno ya está persistido; el bucle de reintentos lo retomará.
        }
    }

    private fun backoffFor(attempts: Int): Long {
        if (attempts <= 0) return 0L
        val exponent = (attempts - 1).coerceAtMost(20)
        val delay = baseBackoffMs shl exponent
        return delay.coerceAtMost(maxBackoffMs)
    }
}
