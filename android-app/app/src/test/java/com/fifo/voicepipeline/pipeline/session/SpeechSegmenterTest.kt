package com.fifo.voicepipeline.pipeline.session

import com.fifo.voicepipeline.audio.PcmBuffer
import com.fifo.voicepipeline.audio.VadProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SpeechSegmenterTest {

    private var nowMs = 0L
    private val frameBytes = 1024

    private fun speechFrame(): ByteArray = ByteArray(frameBytes).also { frame ->
        // Tono alternado (AC): el VAD aplica un paso-altos y descarta el DC puro.
        for (i in 0 until frame.size step 2) {
            val value: Short = if ((i / 2) % 2 == 0) 16000 else -16000
            frame[i] = (value.toInt() and 0xFF).toByte()
            frame[i + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
        }
    }

    private fun silenceFrame(): ByteArray = ByteArray(frameBytes)

    private fun newSegmenter(preRollCapacity: Int = 5): SpeechSegmenter = SpeechSegmenter(
        vad = VadProcessor(clock = { nowMs }),
        pcmBuffer = PcmBuffer(maxBytes = 64 * 1024),
        preRollCapacity = preRollCapacity
    )

    private fun advance() {
        nowMs += 32L
    }

    @Test
    fun `speech start includes pre-roll and speech end returns a WAV`() {
        val segmenter = newSegmenter()

        repeat(3) {
            val result = segmenter.processFrame(silenceFrame(), isSpeakingNow = false)
            assertTrue(result is SpeechSegment.Silence)
            advance()
        }

        assertTrue(segmenter.processFrame(speechFrame(), isSpeakingNow = false) is SpeechSegment.Silence)
        advance()
        assertTrue(segmenter.processFrame(speechFrame(), isSpeakingNow = false) is SpeechSegment.Started)
        advance()

        var ended: SpeechSegment.Ended? = null
        repeat(25) {
            val result = segmenter.processFrame(silenceFrame(), isSpeakingNow = false)
            if (result is SpeechSegment.Ended) ended = result
            advance()
        }

        val wav = ended?.wavData ?: error("El VAD debió cerrar la frase")
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        val dataSize = ByteBuffer.wrap(wav, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertTrue("El pre-roll debe entrar en el WAV", dataSize >= frameBytes * 5)
    }

    @Test
    fun `high energy alone does not create a WAV without silence`() {
        val segmenter = newSegmenter()

        segmenter.processFrame(speechFrame(), isSpeakingNow = true)
        segmenter.processFrame(speechFrame(), isSpeakingNow = true)
        val result = segmenter.processFrame(speechFrame(), isSpeakingNow = true)

        assertTrue(result is SpeechSegment.Started)
    }

    @Test
    fun `reset clears pre-roll and VAD state`() {
        val segmenter = newSegmenter()
        segmenter.processFrame(speechFrame(), isSpeakingNow = false)
        segmenter.processFrame(speechFrame(), isSpeakingNow = false)

        segmenter.reset()

        assertTrue(segmenter.processFrame(silenceFrame(), isSpeakingNow = false) is SpeechSegment.Silence)
    }
}
