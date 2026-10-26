package com.fifo.voicepipeline.pipeline.session.wakeword

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Corpus sintético para el arnés de medición del wake word (ADR-0044).
 *
 * No sustituye a un corpus de campo: es un **banco de pruebas determinista** que
 * permite medir la costura, comparar motores y detectar regresiones sin depender
 * de grabaciones ni de descargas. Sintetiza voz por filtro de formantes
 * (fuente glotal + resonadores) y variaciones de locutor, que es suficiente para
 * que "fifo" se distinga de "fino", "hijo", "vivo" o "FIFA".
 *
 * Cada caso se genera con una semilla fija: la misma entrada produce el mismo
 * audio y, por tanto, las mismas métricas.
 */
internal const val SYNTH_SAMPLE_RATE = 16_000

internal enum class PhonemeKind { VOWEL, FRICATIVE, STOP, NASAL }

internal data class Phoneme(
    val kind: PhonemeKind,
    val symbol: String,
    val durationMs: Int
)

/**
 * Locutor simulado. El acento está en lo que hace difícil un wake word:
 * tono, escala formántica (tamaño del tracto) y velocidad.
 */
internal data class SimulatedSpeaker(
    val name: String,
    val f0Hz: Double,
    val formantScale: Double,
    val rate: Double
) {
    fun durationScale(): Double = 1.0 / rate
}

internal object SimulatedWords {

    private val CONSONANTS = listOf("k", "m", "s", "l", "t", "n", "p", "b", "d", "x")
    private val VOWELS = listOf("a", "e", "i", "o", "u")

    private fun vowel(symbol: String, ms: Int) = Phoneme(PhonemeKind.VOWEL, symbol, ms)
    private fun fricative(symbol: String, ms: Int) = Phoneme(PhonemeKind.FRICATIVE, symbol, ms)
    private fun stop(symbol: String, ms: Int) = Phoneme(PhonemeKind.STOP, symbol, ms)
    private fun nasal(symbol: String, ms: Int) = Phoneme(PhonemeKind.NASAL, symbol, ms)

    /** Objetivo: /f i f o/. */
    val FIFO = listOf(fricative("f", 85), vowel("i", 110), fricative("f", 85), vowel("o", 150))

    /** Negativos difíciles: mismos fonemas vocálicos, distinta consonante o vocal final. */
    val HARD_NEGATIVES: Map<String, List<Phoneme>> = linkedMapOf(
        "fino" to listOf(fricative("f", 85), vowel("i", 105), nasal("n", 80), vowel("o", 145)),
        "hijo" to listOf(fricative("x", 80), vowel("i", 100), fricative("x", 75), vowel("o", 145)),
        "vivo" to listOf(stop("b", 55), vowel("i", 105), stop("b", 55), vowel("o", 150)),
        "fifa" to listOf(fricative("f", 85), vowel("i", 110), fricative("f", 85), vowel("a", 150)),
        "fito" to listOf(fricative("f", 80), vowel("i", 100), stop("t", 55), vowel("o", 145)),
        "pito" to listOf(stop("p", 55), vowel("i", 105), stop("t", 55), vowel("o", 145)),
        "fido" to listOf(fricative("f", 80), vowel("i", 100), stop("d", 55), vowel("o", 145)),
        "cinco" to listOf(fricative("s", 80), vowel("i", 90), nasal("n", 70), stop("k", 55), vowel("o", 140))
    )

    /**
     * Habla continua sin invocación, para medir falsos aceptos.
     *
     * La secuencia es aleatoria pero determinista para una semilla dada; las
     * vocales y consonantes se eligen de un inventario cerrado (español) y no se
     * genera nunca la secuencia /f i f o/.
     */
    fun sentence(rng: Random, syllables: Int): List<Phoneme> = buildList {
        repeat(syllables) {
            val consonant = CONSONANTS.random(rng)
            add(
                when (consonant) {
                    "m", "n", "l" -> nasal(consonant, rng.nextInt(45, 80))
                    "s", "x" -> fricative(consonant, rng.nextInt(50, 85))
                    else -> stop(consonant, rng.nextInt(40, 70))
                }
            )
            add(vowel(VOWELS.random(rng), rng.nextInt(70, 140)))
        }
    }
}

/**
 * Sintetizador Klatt-lite: tren de impulsos glotales o ruido, filtrado por
 * resonadores con formantes interpolados en la transición.
 *
 * Lo que importa para el arnés no es que suene natural, sino que las clases
 * acústicas sean las correctas: /f/ es ruido débil de banda alta, /x/ es ruido
 * de banda media, /s/ es ruido de banda muy alta y las vocales se distinguen por
 * sus dos primeros formantes. Sin esa separación, "fifo" y "hijo" serían el
 * mismo estímulo y las métricas no significarían nada.
 */
internal object FormantSynth {

    private data class VowelTargets(val f1: Double, val f2: Double, val f3: Double)

    private val VOWEL_FORMANTS = mapOf(
        "a" to VowelTargets(730.0, 1090.0, 2440.0),
        "e" to VowelTargets(530.0, 1840.0, 2480.0),
        "i" to VowelTargets(270.0, 2290.0, 3010.0),
        "o" to VowelTargets(570.0, 840.0, 2410.0),
        "u" to VowelTargets(300.0, 870.0, 2240.0)
    )

    private data class NoiseSource(val centerHz: Double, val bandwidthHz: Double)

    private val FRICATIVES = mapOf(
        // /f/: energía alta y débil, la que el ruido enmascara primero.
        "f" to NoiseSource(4800.0, 3000.0),
        "s" to NoiseSource(6400.0, 2200.0),
        "x" to NoiseSource(1600.0, 1200.0)
    )

    private val STOPS = mapOf(
        "p" to NoiseSource(900.0, 700.0),
        "t" to NoiseSource(3600.0, 1600.0),
        "k" to NoiseSource(2200.0, 1300.0),
        "b" to NoiseSource(400.0, 300.0),
        "d" to NoiseSource(1600.0, 700.0)
    )

    private val VOWEL_BANDWIDTHS = doubleArrayOf(70.0, 110.0, 160.0)

    /** Nivel RMS al que se lleva cada clase no sonora (relativo a la voz). */
    private const val FRICATIVE_RMS = 0.05
    private const val STOP_RMS = 0.10

    /**
     * Renderiza una palabra con silencio de entrada y salida.
     *
     * El silencio de entrada importa: el arnés mide latencia y el detector no
     * debe "ver" la palabra empezando en la muestra 0.
     */
    fun renderWord(
        phonemes: List<Phoneme>,
        speaker: SimulatedSpeaker,
        rng: Random,
        leadMs: Int = 250,
        tailMs: Int = 350
    ): ShortArray {
        val scale = speaker.durationScale()
        var cursor = leadMs * SYNTH_SAMPLE_RATE / 1000
        val segments = ArrayList<Segment>(phonemes.size)

        for (phoneme in phonemes) {
            val duration = (phoneme.durationMs * scale * SYNTH_SAMPLE_RATE / 1000).toInt().coerceAtLeast(1)
            segments += Segment(cursor, cursor + duration, phoneme, targetsFor(phoneme))
            cursor += duration
        }

        val total = cursor + tailMs * SYNTH_SAMPLE_RATE / 1000
        val out = DoubleArray(total)

        val voiced = Resonator3()
        val unvoiced = Resonator(centerHz = 2_000.0, bandwidthHz = 1_500.0)
        var phase = 0.0
        var previousTargets: DoubleArray? = null

        for (segment in segments) {
            val length = segment.end - segment.start
            val buffer = DoubleArray(length)
            val kind = segment.phoneme.kind
            val isVoiced = kind == PhonemeKind.VOWEL || kind == PhonemeKind.NASAL
            val from = previousTargets ?: segment.targets
            val blendSamples = (length * 0.35).toInt().coerceAtLeast(1)
            val fade = (0.003 * SYNTH_SAMPLE_RATE).toInt().coerceAtMost(length / 2)
            var retuneCountdown = 0

            if (isVoiced) {
                unvoiced.reset()
            } else {
                val source = FRICATIVES[segment.phoneme.symbol] ?: STOPS[segment.phoneme.symbol]
                    ?: NoiseSource(2_000.0, 1_500.0)
                unvoiced.retune(source.centerHz, source.bandwidthHz)
                voiced.reset()
            }

            for (pos in 0 until length) {
                val sample = segment.start + pos
                if (retuneCountdown <= 0) {
                    val progress = (pos.toDouble() / blendSamples).coerceIn(0.0, 1.0)
                    val targets = DoubleArray(3) { index ->
                        (from[index] + (segment.targets[index] - from[index]) * progress) * speaker.formantScale
                    }
                    voiced.retune(targets, VOWEL_BANDWIDTHS)
                    retuneCountdown = 16
                }
                retuneCountdown--

                val f0 = speaker.f0Hz * (1.0 + 0.06 * sin(2.0 * PI * sample / (SYNTH_SAMPLE_RATE * 0.35)))

                val excitation = when (kind) {
                    PhonemeKind.VOWEL, PhonemeKind.NASAL -> {
                        phase += f0 / SYNTH_SAMPLE_RATE
                        val impulse = if (phase >= 1.0) {
                            phase -= 1.0
                            1.0 + rng.nextDouble(-0.05, 0.05)
                        } else {
                            0.0
                        }
                        impulse + rng.nextDouble(-0.004, 0.004)
                    }

                    PhonemeKind.FRICATIVE -> rng.nextDouble(-1.0, 1.0)
                    PhonemeKind.STOP -> rng.nextDouble(-1.0, 1.0) * 0.6
                }

                val filtered = if (isVoiced) voiced.process(excitation) else unvoiced.process(excitation)

                val attack = if (fade > 0) (pos.toDouble() / fade).coerceIn(0.0, 1.0) else 1.0
                val release = if (fade > 0) ((length - pos).toDouble() / fade).coerceIn(0.0, 1.0) else 1.0
                buffer[pos] = filtered * minOf(attack, release) * amplitudeFor(kind)
            }

            if (!isVoiced) normaliseRms(buffer, if (kind == PhonemeKind.FRICATIVE) FRICATIVE_RMS else STOP_RMS)
            buffer.copyInto(out, segment.start)
            previousTargets = segment.targets
        }

        normalisePeak(out, 0.7)
        applyEdgeFades(out)
        return toPcm16(out, rng)
    }

    /** Muestra de inicio y fin de la palabra dentro del array renderizado. */
    fun wordBounds(phonemes: List<Phoneme>, speaker: SimulatedSpeaker, leadMs: Int = 250): IntRange {
        val lead = leadMs * SYNTH_SAMPLE_RATE / 1000
        val duration = phonemes.sumOf {
            (it.durationMs * speaker.durationScale() * SYNTH_SAMPLE_RATE / 1000).toInt().coerceAtLeast(1)
        }
        return lead until (lead + duration)
    }

    private data class Segment(
        val start: Int,
        val end: Int,
        val phoneme: Phoneme,
        val targets: DoubleArray
    )

    private fun targetsFor(phoneme: Phoneme): DoubleArray = when (phoneme.kind) {
        PhonemeKind.VOWEL -> VOWEL_FORMANTS[phoneme.symbol]?.let {
            doubleArrayOf(it.f1, it.f2, it.f3)
        } ?: doubleArrayOf(500.0, 1500.0, 2500.0)

        PhonemeKind.NASAL -> doubleArrayOf(250.0, 1400.0, 2400.0)
        PhonemeKind.FRICATIVE -> doubleArrayOf(500.0, 1500.0, 2500.0)
        PhonemeKind.STOP -> doubleArrayOf(500.0, 1500.0, 2500.0)
    }

    private fun amplitudeFor(kind: PhonemeKind): Double = when (kind) {
        PhonemeKind.VOWEL -> 1.0
        PhonemeKind.NASAL -> 0.55
        PhonemeKind.FRICATIVE -> 1.0
        PhonemeKind.STOP -> 1.0
    }

    /** Resonador de dos polos; `retune` conserva el estado para no chasquear. */
    private class Resonator(centerHz: Double, bandwidthHz: Double) {
        private var a1 = 0.0
        private var a2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        init {
            retune(centerHz, bandwidthHz)
        }

        fun retune(centerHz: Double, bandwidthHz: Double) {
            val r = exp(-PI * bandwidthHz / SYNTH_SAMPLE_RATE)
            val theta = 2.0 * PI * centerHz / SYNTH_SAMPLE_RATE
            a1 = 2.0 * r * cos(theta)
            a2 = -r * r
        }

        fun process(excitation: Double): Double {
            val output = excitation + a1 * y1 + a2 * y2
            y2 = y1
            y1 = output
            return output
        }

        fun reset() {
            y1 = 0.0
            y2 = 0.0
        }
    }

    /** Tres resonadores en cascada, uno por formante. */
    private class Resonator3 {
        private val stages = Array(3) { Resonator(500.0, 70.0) }

        fun retune(formants: DoubleArray, bandwidths: DoubleArray) {
            for (i in 0..2) {
                stages[i].retune(formants[i].coerceIn(120.0, 7_600.0), bandwidths[i])
            }
        }

        fun process(excitation: Double): Double {
            var value = excitation
            for (stage in stages) value = stage.process(value)
            return value
        }

        fun reset() {
            for (stage in stages) stage.reset()
        }
    }

    private fun normaliseRms(buffer: DoubleArray, target: Double) {
        var squares = 0.0
        for (value in buffer) squares += value * value
        val rms = sqrt(squares / buffer.size.coerceAtLeast(1)).coerceAtLeast(1e-9)
        val gain = target / rms
        for (i in buffer.indices) buffer[i] *= gain
    }

    private fun normalisePeak(buffer: DoubleArray, peak: Double) {
        val max = buffer.maxOfOrNull { kotlin.math.abs(it) } ?: 0.0
        if (max <= 0.0) return
        val gain = peak / max
        for (i in buffer.indices) buffer[i] *= gain
    }

    private fun applyEdgeFades(buffer: DoubleArray) {
        val fade = (0.010 * SYNTH_SAMPLE_RATE).toInt().coerceAtMost(buffer.size / 2)
        for (i in 0 until fade) {
            val gain = i.toDouble() / fade
            buffer[i] *= gain
            buffer[buffer.size - 1 - i] *= gain
        }
    }

    private fun toPcm16(buffer: DoubleArray, rng: Random): ShortArray = ShortArray(buffer.size) { index ->
        val dither = rng.nextDouble(-1e-4, 1e-4)
        ((buffer[index] + dither).coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
    }
}
