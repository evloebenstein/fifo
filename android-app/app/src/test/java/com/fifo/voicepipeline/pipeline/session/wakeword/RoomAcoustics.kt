package com.fifo.voicepipeline.pipeline.session.wakeword

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Simulación de sala para el arnés de campo (ADR-0045).
 *
 * El corpus de ADR-0044 es limpio y de campo cercano: mide el techo del enfoque
 * por plantilla, no lo que pasa en una cocina. Aquí se añade reverberación con
 * el **método de imagen** de Allen & Berkley, que es determinista, no descarga
 * nada y no mete binarios en el repositorio.
 *
 * La sala es una caja; cada pared refleja con un coeficiente que sale de Sabine
 * a partir del T60 deseado. La respuesta se trunca a 1,5 × T60, se filtra en
 * agudos (pared + micrófono) y se normaliza para que el **sonido directo** llegue
 * a nivel unitario: la cola suma energía por encima, como en una sala real. Si
 * se normalizara la energía total, reverberar atenuaría el directo y el arnés
 * sería más duro que el campo.
 */
internal data class RoomProfile(
    val id: String,
    val widthM: Double,
    val depthM: Double,
    val heightM: Double,
    val t60Seconds: Double
) {
    val volume: Double
        get() = widthM * depthM * heightM

    val surface: Double
        get() = 2.0 * (widthM * depthM + widthM * heightM + depthM * heightM)

    /** Coeficiente de absorción medio (Sabine): T60 = 0,161 · V / (S · α). */
    val absorption: Double
        get() = (0.161 * volume / (surface * t60Seconds)).coerceIn(0.02, 0.95)
}

internal object Rooms {

    /** Despacho pequeño: poca cola, el caso más favorable. */
    val OFFICE = RoomProfile(id = "office", widthM = 3.0, depthM = 3.5, heightM = 2.5, t60Seconds = 0.25)

    /** Salón comedor: el escenario normal de un móvil sobre la mesa. */
    val LIVING_ROOM = RoomProfile(id = "living", widthM = 5.0, depthM = 6.0, heightM = 2.7, t60Seconds = 0.50)

    /** Cocina: superficies duras, cola media y ruido de fondo. */
    val KITCHEN = RoomProfile(id = "kitchen", widthM = 3.5, depthM = 4.5, heightM = 2.4, t60Seconds = 0.35)

    val ALL: List<RoomProfile> = listOf(OFFICE, LIVING_ROOM, KITCHEN)
}

internal object RoomAcoustics {

    private const val SPEED_OF_SOUND = 343.0

    /** Orden máximo de imagen por eje: 17³ = 4.913 reflexiones, de sobra para T60 ≤ 0,5 s. */
    private const val MAX_IMAGE_ORDER = 8

    /** Pérdida de agudos de paredes y micrófono. */
    private const val LOW_PASS_HZ = 3_500.0

    /**
     * Respuesta al impulso de la sala. Misma sala → misma respuesta: la
     * posición se sortea con una semilla derivada del id de la sala, no del caso.
     */
    fun impulseResponse(room: RoomProfile, rng: Random): DoubleArray {
        val length = ((room.t60Seconds * 1.5).coerceAtMost(0.9) * SYNTH_SAMPLE_RATE).toInt()
            .coerceAtLeast(64)
        val ir = DoubleArray(length)

        // El móvil está sobre una mesa; la voz llega de 1,2 a 2,6 m.
        val listener = doubleArrayOf(room.widthM * 0.5, room.depthM * 0.4, 1.2)
        val distance = rng.nextDouble(1.2, 2.6)
        val azimuth = rng.nextDouble(0.0, 2.0 * PI)
        val source = doubleArrayOf(
            (listener[0] + distance * cos(azimuth)).coerceIn(0.3, room.widthM - 0.3),
            (listener[1] + distance * sin(azimuth)).coerceIn(0.3, room.depthM - 0.3),
            1.5
        )

        val reflection = sqrt(1.0 - room.absorption)
        val dimensions = doubleArrayOf(room.widthM, room.depthM, room.heightM)

        for (nx in -MAX_IMAGE_ORDER..MAX_IMAGE_ORDER) {
            val ix = imagePosition(source[0], nx, dimensions[0])
            for (ny in -MAX_IMAGE_ORDER..MAX_IMAGE_ORDER) {
                val iy = imagePosition(source[1], ny, dimensions[1])
                for (nz in -MAX_IMAGE_ORDER..MAX_IMAGE_ORDER) {
                    val iz = imagePosition(source[2], nz, dimensions[2])
                    val dx = ix - listener[0]
                    val dy = iy - listener[1]
                    val dz = iz - listener[2]
                    val d = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.05)

                    val delaySamples = (d / SPEED_OF_SOUND * SYNTH_SAMPLE_RATE).toInt()
                    if (delaySamples >= length) continue

                    val order = abs(nx) + abs(ny) + abs(nz)
                    ir[delaySamples] += reflection.pow(order) / d
                }
            }
        }

        lowPass(ir, LOW_PASS_HZ)
        normalisePeak(ir)
        return ir
    }

    /** Convoluciona con la sala y devuelve el mismo número de muestras (la etiqueta temporal no se mueve). */
    fun reverberate(pcm: ShortArray, room: RoomProfile, rng: Random): ShortArray {
        val ir = impulseResponse(room, rng)
        return FftConvolver.convolve(pcm, ir)
    }

    /** Posición de la imagen `index` de una fuente situada en `base` dentro de [0, length]. */
    private fun imagePosition(base: Double, index: Int, length: Double): Double =
        if (index % 2 == 0) index * length + base else index * length + (length - base)

    private fun lowPass(signal: DoubleArray, cutoffHz: Double) {
        val k = 1.0 - exp(-2.0 * PI * cutoffHz / SYNTH_SAMPLE_RATE)
        var y = 0.0
        for (i in signal.indices) {
            y += k * (signal[i] - y)
            signal[i] = y
        }
    }

    private fun normalisePeak(signal: DoubleArray) {
        var peak = 0.0
        for (value in signal) peak = maxOf(peak, abs(value))
        if (peak <= 0.0) return
        val scale = 1.0 / peak
        for (i in signal.indices) signal[i] *= scale
    }
}

/**
 * Convolución por solapamiento-suma con FFT.
 *
 * La convolución directa de doce minutos de audio contra una RIR de 12.000
 * coeficientes son decenas de miles de millones de operaciones; con FFT son dos
 * transformadas por bloque. Es código de prueba: la precisión importa más que
 * la elegancia, pero no se envía con la app.
 */
internal object FftConvolver {

    private const val BLOCK = 4_096

    fun convolve(signal: ShortArray, ir: DoubleArray): ShortArray {
        if (signal.isEmpty() || ir.isEmpty()) return signal

        val fftSize = nextPowerOfTwo(BLOCK + ir.size - 1)
        val irRe = DoubleArray(fftSize)
        val irIm = DoubleArray(fftSize)
        ir.copyInto(irRe)
        fft(irRe, irIm, inverse = false)

        val input = DoubleArray(signal.size) { signal[it] / 32768.0 }
        val out = DoubleArray(signal.size + ir.size)

        val blockRe = DoubleArray(fftSize)
        val blockIm = DoubleArray(fftSize)

        var start = 0
        while (start < input.size) {
            blockRe.fill(0.0)
            blockIm.fill(0.0)
            val count = minOf(BLOCK, input.size - start)
            for (i in 0 until count) blockRe[i] = input[start + i]

            fft(blockRe, blockIm, inverse = false)
            for (i in 0 until fftSize) {
                val re = blockRe[i] * irRe[i] - blockIm[i] * irIm[i]
                val im = blockRe[i] * irIm[i] + blockIm[i] * irRe[i]
                blockRe[i] = re
                blockIm[i] = im
            }
            fft(blockRe, blockIm, inverse = true)

            for (i in 0 until fftSize) {
                val index = start + i
                if (index >= out.size) break
                out[index] += blockRe[i]
            }
            start += BLOCK
        }

        var peak = 0.0
        for (value in out) peak = maxOf(peak, abs(value))
        val headroom = if (peak > 0.95) 0.95 / peak else 1.0

        return ShortArray(signal.size) { index ->
            val value = (out[index] * headroom).coerceIn(-1.0, 1.0)
            (value * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun nextPowerOfTwo(value: Int): Int {
        var size = 1
        while (size < value) size = size shl 1
        return size
    }

    private fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tempRe = re[i]
                re[i] = re[j]
                re[j] = tempRe
                val tempIm = im[i]
                im[i] = im[j]
                im[j] = tempIm
            }
        }

        var length = 2
        while (length <= n) {
            val angle = 2.0 * PI / length * if (inverse) 1.0 else -1.0
            val wRe = cos(angle)
            val wIm = sin(angle)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                val half = length / 2
                for (k in 0 until half) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + half] * curRe - im[i + k + half] * curIm
                    val vIm = re[i + k + half] * curIm + im[i + k + half] * curRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + half] = uRe - vRe
                    im[i + k + half] = uIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += length
            }
            length = length shl 1
        }

        if (inverse) {
            val scale = 1.0 / n
            for (index in 0 until n) {
                re[index] = re[index] * scale
                im[index] = im[index] * scale
            }
        }
    }
}
