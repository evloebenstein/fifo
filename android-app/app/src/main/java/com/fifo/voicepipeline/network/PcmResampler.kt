package com.fifo.voicepipeline.network

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Conversión de PCM lineal entre las frecuencias usadas por TTS y el ESP32.
 *
 * Es una utilidad pura para poder probarla sin Android ni red.
 */
internal object PcmResampler {

    /**
     * Resample simple de 24 kHz → 16 kHz con interpolación lineal.
     * Para voz conversacional es suficiente y evita introducir un DSP pesado.
     */
    fun resample24kTo16k(pcm24k: ByteArray): ByteArray {
        val shortBuffer = ByteBuffer.wrap(pcm24k)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()

        val inputSamples = shortBuffer.remaining()
        val outputSamples = (inputSamples * 2) / 3
        val output = ByteBuffer.allocate(outputSamples * 2)
            .order(ByteOrder.LITTLE_ENDIAN)

        val shortOut = output.asShortBuffer()

        var srcPos = 0.0
        val ratio = 24000.0 / 16000.0

        for (i in 0 until outputSamples) {
            val idx = srcPos.toInt()
            if (idx >= inputSamples - 1) break

            val frac = srcPos - idx
            val s0 = shortBuffer.get(idx).toInt()
            val s1 = shortBuffer.get(idx + 1).toInt()
            val interpolated = (s0 + (s1 - s0) * frac).toInt().toShort()

            shortOut.put(interpolated)
            srcPos += ratio
        }

        return output.array().copyOf(shortOut.position() * 2)
    }
}
