package com.fifo.voicepipeline.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RobotAudioFormatTest {

    @Test
    fun `el audio ya a 8 kHz mono se conserva tal cual`() {
        val wav = wavFile(
            sampleRate = 8000,
            channels = 1,
            samples = shortArrayOf(0, 1000, -1000, 32000)
        )

        val pcm = RobotAudioFormat.wavToPcm8k(wav)

        assertNotNull(pcm)
        assertArrayEquals(shortArrayOf(0, 1000, -1000, 32000), pcm)
    }

    @Test
    fun `24 kHz se baja a 8 kHz promediando cada tres muestras`() {
        val wav = wavFile(
            sampleRate = 24000,
            channels = 1,
            samples = shortArrayOf(0, 300, 600, 900, 1200, 1500)
        )

        val pcm = RobotAudioFormat.wavToPcm8k(wav)

        assertNotNull(pcm)
        assertArrayEquals(shortArrayOf(300, 1200), pcm)
    }

    @Test
    fun `un WAV estereo se mezcla a mono`() {
        val wav = wavFile(
            sampleRate = 8000,
            channels = 2,
            samples = shortArrayOf(100, -100, 400, 200)
        )

        val pcm = RobotAudioFormat.wavToPcm8k(wav)

        assertNotNull(pcm)
        assertArrayEquals(shortArrayOf(0, 300), pcm)
    }

    @Test
    fun `un formato distinto de PCM 16 bits se rechaza`() {
        val wav = wavFile(sampleRate = 24000, channels = 1, samples = shortArrayOf(1, 2), bitsPerSample = 8)

        assertNull(RobotAudioFormat.wavToPcm8k(wav))
    }

    @Test
    fun `datos que no son RIFF se rechazan`() {
        assertNull(RobotAudioFormat.wavToPcm8k(ByteArray(64)))
        assertNull(RobotAudioFormat.wavToPcm8k(ByteArray(4)))
    }

    @Test
    fun `la serializacion usa little endian`() {
        val bytes = RobotAudioFormat.toLittleEndian(shortArrayOf(0x0102, -1), 0, 2)

        assertArrayEquals(byteArrayOf(0x02, 0x01, -1, -1), bytes)
    }

    @Test
    fun `un bloque mayor que la senal se recorta al final`() {
        val bytes = RobotAudioFormat.toLittleEndian(shortArrayOf(1, 2, 3), 1, 3)

        assertEquals(4, bytes.size)
        assertEquals(2.toShort(), ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).short)
    }

    /** Construye un WAV PCM mínimo en memoria, como el que devuelve el motor de TTS. */
    private fun wavFile(
        sampleRate: Int,
        channels: Int,
        samples: ShortArray,
        bitsPerSample: Int = 16
    ): ByteArray {
        val dataSize = samples.size * 2
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))

        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1)                       // PCM
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * channels * 2) // byte rate
        buffer.putShort((channels * 2).toShort())
        buffer.putShort(bitsPerSample.toShort())

        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)
        samples.forEach(buffer::putShort)

        return buffer.array()
    }
}
