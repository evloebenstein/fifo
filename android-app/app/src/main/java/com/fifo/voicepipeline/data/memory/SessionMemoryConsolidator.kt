package com.fifo.voicepipeline.data.memory

/**
 * Costura que convierte turnos durables en memoria episódica.
 *
 * Devuelve `false` cuando la consolidación falló de forma recuperable. El
 * `MemoryCoordinator` conserva entonces los turnos en la cola pendiente y
 * aplica backoff exponencial. Un `true` significa que el turno ya no necesita
 * reintentos (aunque no haya producido un recuerdo nuevo).
 */
interface SessionMemoryConsolidator {
    suspend fun consolidate(turns: List<JournalEntry>): Boolean
}

/**
 * Consolidador de prueba: registra cada lote y permite simular fallos.
 */
class InMemorySessionMemoryConsolidator(
    var shouldFail: Boolean = false
) : SessionMemoryConsolidator {
    val consolidatedBatches = mutableListOf<List<JournalEntry>>()
    val failedBatches = mutableListOf<List<JournalEntry>>()

    override suspend fun consolidate(turns: List<JournalEntry>): Boolean {
        if (shouldFail) {
            failedBatches.add(turns.toList())
            return false
        }
        consolidatedBatches.add(turns.toList())
        return true
    }
}
