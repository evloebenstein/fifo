package com.fifo.voicepipeline.data.memory

import com.fifo.voicepipeline.pipeline.session.MemoryCoordinator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryCoordinatorTest {

    @Test
    fun testRecordTurn_isPersistedBeforeConsolidation() {
        val now = 1_000L
        val journal = InMemoryConversationJournal()
        val coordinator = MemoryCoordinator(
            journal = journal,
            consolidator = InMemorySessionMemoryConsolidator(),
            scope = TestScope(StandardTestDispatcher()),
            userIdProvider = { "usr_test" },
            clock = { now },
            sessionId = "ses_test"
        )

        val entry = coordinator.recordTurn("Fifo, ¿qué hora es?", "Son las cinco.")

        assertNotNull(entry)
        assertEquals(1, journal.all().size)
        assertEquals(JournalStatus.PENDING, journal.all().single().status)
        assertEquals("Fifo, ¿qué hora es?", journal.all().single().userText)
        assertEquals("Son las cinco.", journal.all().single().assistantText)
        assertEquals("usr_test", journal.all().single().userId)
        assertEquals("ses_test", journal.all().single().sessionId)
    }

    @Test
    fun testFailedConsolidation_retriesWithExponentialBackoff() = runTest {
        var now = 1_000L
        val journal = InMemoryConversationJournal()
        val consolidator = InMemorySessionMemoryConsolidator(shouldFail = true)
        val coordinator = MemoryCoordinator(
            journal = journal,
            consolidator = consolidator,
            scope = this,
            userIdProvider = { "usr_test" },
            clock = { now },
            sessionId = "ses_test",
            baseBackoffMs = 30_000L,
            maxBackoffMs = 15 * 60_000L
        )

        coordinator.recordTurn("Fifo, ¿qué hora es?", "Son las cinco.")
        coordinator.drainDue(now)

        var stored = journal.all().single()
        assertEquals(JournalStatus.PENDING, stored.status)
        assertEquals(1, stored.attempts)
        assertEquals(31_000L, stored.nextAttemptAt)
        assertEquals(1, consolidator.failedBatches.size)

        // Antes del backoff no se reintenta.
        coordinator.drainDue(30_999L)
        assertEquals(1, consolidator.failedBatches.size)

        // Segundo fallo: el backoff se duplica.
        coordinator.drainDue(31_000L)
        stored = journal.all().single()
        assertEquals(2, stored.attempts)
        assertEquals(91_000L, stored.nextAttemptAt)

        // Al vencer el segundo backoff, consolida y sale de la cola.
        consolidator.shouldFail = false
        now = 91_000L
        coordinator.drainDue(now)

        stored = journal.all().single()
        assertEquals(JournalStatus.CONSOLIDATED, stored.status)
        assertEquals(1, consolidator.consolidatedBatches.size)
        assertTrue(journal.pending(now).isEmpty())
    }

    @Test
    fun testPendingQueue_survivesCoordinatorRestart() = runTest {
        val journal = InMemoryConversationJournal()
        val first = MemoryCoordinator(
            journal = journal,
            consolidator = InMemorySessionMemoryConsolidator(shouldFail = true),
            scope = this,
            userIdProvider = { "usr_test" },
            clock = { 1_000L },
            sessionId = "ses_old",
            baseBackoffMs = 30_000L
        )
        first.recordTurn("Fifo, hablemos de mi nieto", "Claro, cuénteme de él.")
        first.drainDue(1_000L)

        // "Reinicio": un coordinador nuevo lee el mismo diario durable.
        val consolidator = InMemorySessionMemoryConsolidator()
        val second = MemoryCoordinator(
            journal = journal,
            consolidator = consolidator,
            scope = this,
            userIdProvider = { "usr_test" },
            clock = { 31_000L },
            sessionId = "ses_new"
        )
        second.drainDue(31_000L)

        assertEquals(1, consolidator.consolidatedBatches.size)
        assertEquals(JournalStatus.CONSOLIDATED, journal.all().single().status)
    }

    @Test
    fun testStartDrainsPendingWithoutWaitingForThePollingLoop() = runTest {
        val journal = InMemoryConversationJournal()
        val consolidator = InMemorySessionMemoryConsolidator()
        val coordinator = MemoryCoordinator(
            journal = journal,
            consolidator = consolidator,
            scope = this,
            userIdProvider = { "usr_test" },
            clock = { 1_000L },
            sessionId = "ses_test"
        )
        coordinator.recordTurn("Fifo, hablemos de mi nieto", "Claro, cuénteme de él.")

        coordinator.start()
        testScheduler.runCurrent()

        assertEquals(1, consolidator.consolidatedBatches.size)
        assertEquals(JournalStatus.CONSOLIDATED, journal.all().single().status)
        coordinator.stop()
    }

    @Test
    fun testRepairRequeuesAnAlreadyConsolidatedTurn() = runTest {
        val journal = InMemoryConversationJournal()
        val original = journal.append(
            JournalEntry(
                userId = "usr_test",
                sessionId = "ses_test",
                userText = "Fifo, ¿a qué hora abre?",
                assistantText = "A las ocho.",
                createdAt = 1_000L,
                status = JournalStatus.CONSOLIDATED,
                attempts = 3
            )
        )
        val coordinator = MemoryCoordinator(
            journal = journal,
            consolidator = InMemorySessionMemoryConsolidator(),
            scope = this,
            userIdProvider = { "usr_test" },
            clock = { 2_000L },
            sessionId = "ses_test"
        )

        coordinator.markRepaired(original.id, "Tienes razón, abre a las nueve.", 2_000L)

        val repaired = journal.all().single()
        assertEquals(JournalStatus.PENDING, repaired.status)
        assertEquals(0, repaired.attempts)
        assertEquals(0L, repaired.nextAttemptAt)
        assertEquals("Tienes razón, abre a las nueve.", repaired.repairText)
        assertFalse(repaired.needsRepair)
    }
}
