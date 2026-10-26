package com.fifo.voicepipeline.pipeline.session.wakeword

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Ruido sintético para el arnés (ADR-0044).
 *
 * Tres condiciones que estresan distinto: blanco (energía plana, enmascara las
 * fricativas de "fifo"), rosa (más parecido a tráfico o aire acondicionado) y
 * balbuceo (varias voces superpuestas, el peor caso para /f/).
 */
internal enum class NoiseKind { WHITE, PINK, BABBLE }

internal object SimulatedNoise {

    fun generate(kind: NoiseKind, samples: Int, rng: Random): DoubleArray = when (kind) {
        NoiseKind.WHITE -> white(samples, rng)
        NoiseKind.PINK -> pink(samples, rng)
        NoiseKind.BABBLE -> babble(samples, rng)
    }

    fun white(samples: Int, rng: Random): DoubleArray = DoubleArray(samples) { rng.nextDouble(-1.0, 1.0) }

    /** Filtro de Paul Kellet: rosa barato y estable. */
    fun pink(samples: Int, rng: Random): DoubleArray {
        var b0 = 0.0
        var b1 = 0.0
        var b2 = 0.0
        var b3 = 0.0
        var b4 = 0.0
        var b5 = 0.0
        var b6 = 0.0
        val out = DoubleArray(samples)
        for (i in 0 until samples) {
            val white = rng.nextDouble(-1.0, 1.0)
            b0 = 0.99886 * b0 + white * 0.0555179
            b1 = 0.99332 * b1 + white * 0.0750759
            b2 = 0.96900 * b2 + white * 0.1538520
            b3 = 0.86650 * b3 + white * 0.3104856
            b4 = 0.55000 * b4 + white * 0.5329522
            b5 = -0.7616 * b5 - white * 0.0168980
            out[i] = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362
            b6 = white * 0.115926
        }
        return out
    }

    /** Varias voces simultáneas: el enmascarador más realista para una sala. */
    fun babble(samples: Int, rng: Random): DoubleArray {
        val out = DoubleArray(samples + SYNTH_SAMPLE_RATE)
        val voices = 5
        repeat(voices) { voice ->
            val speaker = SimulatedSpeaker(
                name = "babble-$voice",
                f0Hz = rng.nextDouble(95.0, 215.0),
                formantScale = rng.nextDouble(0.90, 1.10),
                rate = rng.nextDouble(0.9, 1.15)
            )
            var cursor = rng.nextInt(0, SYNTH_SAMPLE_RATE / 2)
            while (cursor < out.size) {
                val syllables = (0 until rng.nextInt(3, 6)).map {
                    val vowels = listOf("a", "e", "i", "o", "u")
                    Phoneme(PhonemeKind.VOWEL, vowels.random(rng), rng.nextInt(70, 140))
                }
                val rendered = FormantSynth.renderWord(syllables, speaker, rng, leadMs = 0, tailMs = 40)
                val gain = rng.nextDouble(0.25, 0.6)
                for (i in rendered.indices) {
                    val target = cursor + i
                    if (target >= out.size) break
                    out[target] += rendered[i] / 32768.0 * gain
                }
                cursor += rendered.size + rng.nextInt(0, 1200)
            }
        }
        // Recortar al tamaño pedido conservando el nivel.
        return DoubleArray(samples) { out[it] }
    }

    /**
     * Mezcla [signal] con [noise] a la SNR pedida.
     *
     * La potencia de la señal se mide solo en las muestras activas: incluir el
     * silencio de entrada haría que la SNR real fuese mucho más alta que la
     * nominal y la prueba mentiría a favor del detector.
     */
    fun mixAtSnr(signal: ShortArray, noise: DoubleArray, snrDb: Int): ShortArray {
        val normalized = DoubleArray(signal.size) { signal[it] / 32768.0 }
        val peak = normalized.maxOfOrNull { abs(it) } ?: 0.0
        val activeThreshold = peak * 0.06
        var signalSquares = 0.0
        var activeCount = 0
        for (value in normalized) {
            if (abs(value) >= activeThreshold) {
                signalSquares += value * value
                activeCount++
            }
        }
        val signalRms = sqrt(signalSquares / activeCount.coerceAtLeast(1)).coerceAtLeast(1e-6)

        var noiseSquares = 0.0
        for (value in noise) noiseSquares += value * value
        val noiseRms = sqrt(noiseSquares / noise.size.coerceAtLeast(1)).coerceAtLeast(1e-9)

        val gain = signalRms / (noiseRms * 10.0.pow(snrDb / 20.0))
        return ShortArray(signal.size) { index ->
            val mixed = normalized[index] + noise[index] * gain
            (mixed.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }
    }

    fun snrOf(signal: ShortArray, noise: DoubleArray): Double {
        val normalized = DoubleArray(signal.size) { signal[it] / 32768.0 }
        val peak = normalized.maxOfOrNull { abs(it) } ?: return Double.NaN
        val threshold = peak * 0.06
        var s = 0.0
        var n = 0
        for (value in normalized) {
            if (abs(value) >= threshold) {
                s += value * value
                n++
            }
        }
        var noiseSquares = 0.0
        for (value in noise) noiseSquares += value * value
        val signalRms = sqrt(s / n.coerceAtLeast(1)).coerceAtLeast(1e-6)
        val noiseRms = sqrt(noiseSquares / noise.size.coerceAtLeast(1)).coerceAtLeast(1e-9)
        return 20.0 * log10(signalRms / noiseRms)
    }
}
