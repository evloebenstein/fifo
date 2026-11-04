package com.fifo.voicepipeline.audio

/**
 * Formato del enlace de voz hacia el robot (ADR-0055).
 *
 * El firmware reproduce PCM 16-bit little endian mono a 8 kHz. Android sintetiza
 * a 22–24 kHz, así que aquí se decodifica el WAV del motor de TTS y se baja a la
 * frecuencia y al canal que espera el MAX98357A.
 *
 * Es aritmética pura, sin Android, para poder probarla sin dispositivo.
 */
object RobotAudioFormat {

    /** Frecuencia del enlace descendente: la del MAX98357A en el firmware. */
    const val TARGET_SAMPLE_RATE = 8000

    /**
     * Decodifica un WAV PCM 16-bit y lo convierte a mono a [TARGET_SAMPLE_RATE].
     * @return muestras PCM 16-bit, o `null` si el formato no es reproducible.
     */
    fun wavToPcm8k(bytes: ByteArray): ShortArray? {
        if (bytes.size < 44) return null
        if (ascii(bytes, 0, 4) != "RIFF" || ascii(bytes, 8, 4) != "WAVE") return null

        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataLength = 0

        var pos = 12
        while (pos + 8 <= bytes.size) {
            val chunkId = ascii(bytes, pos, 4)
            val chunkSize = leInt(bytes, pos + 4)
            val body = pos + 8

            when (chunkId) {
                "fmt " -> {
                    if (body + 16 <= bytes.size) {
                        channels = leShort(bytes, body + 2)
                        sampleRate = leInt(bytes, body + 4)
                        bitsPerSample = leShort(bytes, body + 14)
                    }
                }
                "data" -> {
                    dataOffset = body
                    dataLength = minOf(chunkSize, bytes.size - body)
                }
            }

            if (dataOffset >= 0 && sampleRate > 0) break
            pos = body + chunkSize + (chunkSize and 1)
        }

        if (dataOffset < 0 || dataLength < 2 || sampleRate <= 0) return null
        if (bitsPerSample != 16 || channels <= 0) return null

        val frameCount = dataLength / (2 * channels)
        if (frameCount <= 0) return null

        val mono = ShortArray(frameCount)
        var offset = dataOffset
        for (i in 0 until frameCount) {
            var sum = 0
            for (c in 0 until channels) {
                sum += leShort(bytes, offset)
                offset += 2
            }
            mono[i] = (sum / channels).toShort()
        }

        return resampleToTarget(mono, sampleRate)
    }

    /**
     * Remuestreo por media de bloque: además de cambiar la frecuencia, actúa
     * como filtro paso-bajo y evita el aliasing de bajar 24 kHz a 8 kHz.
     */
    fun resampleToTarget(samples: ShortArray, sourceRate: Int): ShortArray {
        if (sourceRate <= 0) return ShortArray(0)
        if (sourceRate == TARGET_SAMPLE_RATE) return samples
        if (samples.isEmpty()) return ShortArray(0)

        val step = sourceRate.toDouble() / TARGET_SAMPLE_RATE
        val outSize = (samples.size / step).toInt()
        if (outSize <= 0) return ShortArray(0)

        val out = ShortArray(outSize)
        var position = 0.0
        for (i in 0 until outSize) {
            val start = position.toInt().coerceIn(0, samples.size - 1)
            val end = (position + step).toInt().coerceIn(start + 1, samples.size)
            var sum = 0L
            for (j in start until end) sum += samples[j]
            out[i] = (sum / (end - start)).coerceIn(-32768L, 32767L).toInt().toShort()
            position += step
        }
        return out
    }

    /** Serializa un rango de muestras a PCM 16-bit little endian. */
    fun toLittleEndian(samples: ShortArray, from: Int, to: Int): ByteArray {
        val out = ByteArray((to - from) * 2)
        var index = 0
        for (i in from until to) {
            val value = samples[i].toInt()
            out[index++] = (value and 0xFF).toByte()
            out[index++] = ((value shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.US_ASCII)

    private fun leShort(bytes: ByteArray, offset: Int): Int {
        val raw = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        // leShort devuelve un Int con signo: sin esto, -100 se lee como 65436.
        return if (raw >= 0x8000) raw - 0x10000 else raw
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
