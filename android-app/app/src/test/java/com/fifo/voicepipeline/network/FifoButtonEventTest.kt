package com.fifo.voicepipeline.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FifoButtonEventTest {

    @Test
    fun `los codigos del firmware se traducen a gestos`() {
        assertEquals(FifoButtonEvent.SHORT_PRESS, FifoButtonEvent.fromCode(1))
        assertEquals(FifoButtonEvent.LONG_PRESS, FifoButtonEvent.fromCode(2))
    }

    @Test
    fun `un codigo desconocido no produce gesto`() {
        assertNull(FifoButtonEvent.fromCode(0))
        assertNull(FifoButtonEvent.fromCode(9))
    }
}
