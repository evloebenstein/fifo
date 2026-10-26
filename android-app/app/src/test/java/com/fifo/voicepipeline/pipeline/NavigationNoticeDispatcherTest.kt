package com.fifo.voicepipeline.pipeline

import com.fifo.voicepipeline.location.FifoNavigationManager
import com.fifo.voicepipeline.location.NavigationNotice
import com.fifo.voicepipeline.location.NavigationNoticeKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Orquestación de avisos autónomos (ADR-0041).
 *
 * El dispatcher es el dueño de la coalescencia, la espera y la narración. Estos
 * tests fijan el contrato sin Android: el facade solo le entrega hechos.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationNoticeDispatcherTest {

    private val address = "Manquehue Norte 1234, Vitacura"

    private fun destination(name: String = "OXXO Manquehue") =
        FifoNavigationManager.ActiveDestination(
            name = name,
            latitude = -33.4172,
            longitude = -70.6088,
            fullAddress = address,
            road = "Manquehue Norte"
        )

    private fun notice(
        kind: NavigationNoticeKind = NavigationNoticeKind.STALLED,
        name: String = "OXXO Manquehue",
        destinationAddress: String = address
    ) = NavigationNotice(
        kind = kind,
        destinationName = name,
        destinationAddress = destinationAddress,
        streetName = "Manquehue Norte",
        distanceMeters = 120,
        blocks = 2,
        walkingMinutes = 2,
        relativeInstruction = "siga derecho",
        cardinalDirection = "norte",
        isFacingTarget = true
    )

    private fun dispatcher(
        scope: kotlinx.coroutines.CoroutineScope,
        awaitIdle: suspend () -> Unit = {},
        active: () -> FifoNavigationManager.ActiveDestination? = { destination() },
        narrate: suspend (NavigationNotice) -> String? = { "locución" },
        speak: (String) -> Unit = {},
        log: (String) -> Unit = {}
    ) = NavigationNoticeDispatcher(scope, awaitIdle, active, narrate, speak, log)

    @Test
    fun `the latest notice wins while the pipeline is busy`() = runTest {
        val idle = CompletableDeferred<Unit>()
        val narrated = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val dispatcher = dispatcher(
            scope = this,
            awaitIdle = { idle.await() },
            active = { destination("Último") },
            narrate = { notice -> narrated += notice.destinationName; "ok" },
            speak = { spoken += it }
        )

        dispatcher.dispatch(notice(name = "Primero"))
        dispatcher.dispatch(notice(kind = NavigationNoticeKind.APPROACHING_50M, name = "Último"))

        advanceUntilIdle()
        assertTrue(narrated.isEmpty())

        idle.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("Último"), narrated)
        assertEquals(listOf("ok"), spoken)
    }

    @Test
    fun `a notice from another destination is discarded`() = runTest {
        val narrated = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val dispatcher = dispatcher(
            scope = this,
            active = { destination("Otro destino") },
            narrate = { narrated += it.destinationName; "ok" },
            speak = { spoken += it }
        )

        dispatcher.dispatch(notice(name = "Primero"))
        advanceUntilIdle()

        assertTrue(narrated.isEmpty())
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `an arrival is announced even after the route closes`() = runTest {
        val spoken = mutableListOf<String>()
        val dispatcher = dispatcher(
            scope = this,
            active = { null },
            narrate = { null },
            speak = { spoken += it }
        )

        val arrival = notice(kind = NavigationNoticeKind.ARRIVED)
        dispatcher.dispatch(arrival)
        advanceUntilIdle()

        assertEquals(listOf(arrival.fallbackSpeech()), spoken)
    }

    @Test
    fun `the fallback speaks when the model does not answer`() = runTest {
        val spoken = mutableListOf<String>()
        val dispatcher = dispatcher(
            scope = this,
            narrate = { null },
            speak = { spoken += it }
        )

        val stalled = notice()
        dispatcher.dispatch(stalled)
        advanceUntilIdle()

        assertEquals(listOf(stalled.fallbackSpeech()), spoken)
    }

    @Test
    fun `a failing narrator falls back and keeps the queue alive`() = runTest {
        var shouldFail = true
        val spoken = mutableListOf<String>()
        val dispatcher = dispatcher(
            scope = this,
            narrate = { if (shouldFail) error("sin red") else "ok" },
            speak = { spoken += it }
        )

        val stalled = notice()
        dispatcher.dispatch(stalled)
        advanceUntilIdle()

        shouldFail = false
        dispatcher.dispatch(notice())
        advanceUntilIdle()

        assertEquals(listOf(stalled.fallbackSpeech(), "ok"), spoken)
    }

    @Test
    fun `a failing speaker does not block the next notice`() = runTest {
        var shouldFail = true
        val spoken = mutableListOf<String>()
        val dispatcher = dispatcher(
            scope = this,
            speak = { speech ->
                if (shouldFail) error("TTS roto") else spoken += speech
            }
        )

        dispatcher.dispatch(notice())
        advanceUntilIdle()

        shouldFail = false
        dispatcher.dispatch(notice())
        advanceUntilIdle()

        assertEquals(listOf("locución"), spoken)
    }

    @Test
    fun `an empty narration falls back instead of speaking silence`() = runTest {
        val spoken = mutableListOf<String>()
        val dispatcher = dispatcher(
            scope = this,
            narrate = { "   " },
            speak = { spoken += it }
        )

        val stalled = notice()
        dispatcher.dispatch(stalled)
        advanceUntilIdle()

        // Un texto vacío del modelo se considera falta de respuesta: fallback.
        assertEquals(listOf(stalled.fallbackSpeech()), spoken)
    }

    // ── Relevancia del aviso (función pura) ───────────────────────────────

    @Test
    fun `a notice is relevant only for the same active destination`() {
        assertTrue(isNavigationNoticeRelevant(notice(), destination()))
        assertFalse(isNavigationNoticeRelevant(notice(), destination("Farmacia")))
        assertFalse(isNavigationNoticeRelevant(notice(), null))
    }

    @Test
    fun `the address is part of the destination identity`() {
        val moved = destination().copy(fullAddress = "Otra dirección 999")
        assertFalse(isNavigationNoticeRelevant(notice(), moved))
    }

    @Test
    fun `an arrival is stale only when another route is active`() {
        val arrival = notice(kind = NavigationNoticeKind.ARRIVED)

        assertTrue(isNavigationNoticeRelevant(arrival, null))
        assertTrue(isNavigationNoticeRelevant(arrival, destination()))
        assertFalse(isNavigationNoticeRelevant(arrival, destination("Farmacia")))
    }
}
