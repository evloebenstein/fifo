package com.fifo.voicepipeline.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmResamplerTest {

    @Test
    fun `convierte 24k a 16k manteniendo la forma de la senal`() {
        val input = shortArrayOf(0, 0, 1000, 1000, 0, 0).toLittleEndianBytes()

        val output = PcmResampler.resample24kTo16k(input)

        assertArrayEquals(
            shortArrayOf(0, 500, 1000, 0),
            output.toLittleEndianShorts()
        )
    }

    @Test
    fun `audio vacio produce audio vacio`() {
        val output = PcmResampler.resample24kTo16k(ByteArray(0))

        assertEquals(0, output.size)
    }

    private fun ShortArray.toLittleEndianBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(size * 2).order(ByteOrder.LITTLE_ENDIAN)
        forEach(buffer::putShort)
        return buffer.array()
    }

    private fun ByteArray.toLittleEndianShorts(): ShortArray {
        val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(buffer.remaining()) { buffer.get() }
    }
}
