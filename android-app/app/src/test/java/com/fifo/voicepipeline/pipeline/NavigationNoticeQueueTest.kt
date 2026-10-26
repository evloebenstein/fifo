package com.fifo.voicepipeline.pipeline

import com.fifo.voicepipeline.location.NavigationNotice
import com.fifo.voicepipeline.location.NavigationNoticeKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coalescencia de avisos autónomos (ADR-0041).
 *
 * Ningún aviso se descarta por duración: el más reciente reemplaza a los
 * anteriores y se locuta cuando el turno en curso termina. Un solo drenaje
 * puede estar activo a la vez.
 */
class NavigationNoticeQueueTest {

    private fun notice(
        kind: NavigationNoticeKind =
            NavigationNoticeKind.STALLED,
        name: String = "OXXO Manquehue"
    ) = NavigationNotice(
        kind = kind,
        destinationName = name,
        destinationAddress = "Manquehue Norte 1234, Vitacura",
        streetName = "Manquehue Norte",
        distanceMeters = 120,
        blocks = 2,
        walkingMinutes = 2,
        relativeInstruction = "siga derecho",
        cardinalDirection = "norte",
        isFacingTarget = true
    )

    @Test
    fun `first notice starts the drain and later ones only queue`() {
        val queue = NavigationNoticeQueue()

        assertTrue(queue.offer(notice()))
        assertFalse(queue.offer(notice(kind = NavigationNoticeKind.APPROACHING_50M)))
    }

    @Test
    fun `the latest notice wins when several arrive before draining`() {
        val queue = NavigationNoticeQueue()
        val first = notice(name = "Primero")
        val latest = notice(name = "Ultimo")

        queue.offer(first)
        queue.offer(latest)

        assertSame(latest, queue.poll())
    }

    @Test
    fun `hasPending reports the coalesced notice`() {
        val queue = NavigationNoticeQueue()
        assertFalse(queue.hasPending())

        queue.offer(notice())

        assertTrue(queue.hasPending())
        queue.poll()
        assertFalse(queue.hasPending())
    }

    @Test
    fun `an empty poll releases the batch for the next notice`() {
        val queue = NavigationNoticeQueue()

        assertTrue(queue.offer(notice()))
        assertNotNull(queue.poll())
        assertNull(queue.poll())
        assertTrue(queue.offer(notice()))
    }

    @Test
    fun `release lets a new batch start after an abnormal end`() {
        val queue = NavigationNoticeQueue()

        assertTrue(queue.offer(notice()))
        assertFalse(queue.offer(notice()))
        queue.release()

        assertTrue(queue.offer(notice()))
    }
}
