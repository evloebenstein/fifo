package com.fifo.voicepipeline.pipeline.session.wakeword

import com.fifo.voicepipeline.pipeline.session.WakeWordDetection
import com.fifo.voicepipeline.pipeline.session.WakeWordDetector
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Detector de referencia del arnés (ADR-0044).
 *
 * Es la versión honesta de la idea de "huella acústica": un banco de 16 bandas
 * logarítmicas por trama, normalización por media y varianza (CMVN), vector L2 y
 * comparación por coseno contra plantillas de "fifo" grabadas de varios
 * locutores. No envía audio a ningún sitio y no se usa en producción: sirve para
 * medir la costura, comparar umbrales y demostrar con números por qué un
 * template espectral se cae con ruido y un KWS entrenado no.
 *
 * Detalles que importan:
 *
 * - La ventana activa se recorta por energía antes de re-muestrear a [GRID]
 *   puntos, de modo que la duración del habla no fija el tamaño del vector.
 * - La CMVN elimina el nivel global: la comparación mide forma espectral, no
 *   volumen.
 * - Hay refractario: un "fifo" produce una detección, no una ráfaga de ellas.
 */
internal class SpectralTemplateWakeWordDetector(
    private val templates: List<SpectralTemplate>,
    private val threshold: Double,
    override val modelId: String = "spectral-template-v1",
    /**
     * Observador de scores para el arnés: recibe el coseno de cada trama aunque
     * no cruce el umbral, de modo que una sola pasada permite calibrar todos los
     * umbrales sin volver a analizar el corpus. En producción es `null`.
     */
    private val onScore: ((score: Double, atMillis: Long) -> Unit)? = null
) : WakeWordDetector {

    private val frontend = SpectralFrontend()
    private val frames = ArrayDeque<FrameFeature>()
    private var lastDetectionMillis = NEVER
    private var lastScoreMillis = NEVER

    override fun accept(frame: ByteArray, nowMillis: Long): WakeWordDetection? {
        var index = 0
        while (index + 1 < frame.size) {
            val raw = ((frame[index].toInt() and 0xFF) or (frame[index + 1].toInt() shl 8)).toShort()
            frontend.push(raw / 32768f)

            val feature = frontend.pollFrame()
            if (feature != null) {
                frames.addLast(feature)
                if (frames.size > MAX_WINDOW_FRAMES) frames.removeFirst()
            }
            index += 2
        }

        if (frames.size < MIN_WINDOW_FRAMES) return null
        if (lastDetectionMillis != NEVER && nowMillis - lastDetectionMillis < REFRACTORY_MS) return null
        if (nowMillis - lastScoreMillis < SCORE_INTERVAL_MS) return null
        lastScoreMillis = nowMillis

        val score = scoreWindow()
        onScore?.invoke(score, nowMillis)
        if (score < threshold) return null

        lastDetectionMillis = nowMillis
        return WakeWordDetection(
            score = score.coerceIn(0.0, 1.0),
            detectedAtMillis = nowMillis,
            modelId = modelId
        )
    }

    override fun reset() {
        frontend.reset()
        frames.clear()
        lastDetectionMillis = NEVER
        lastScoreMillis = NEVER
    }

    /**
     * Mejor coseno sobre una rejilla de duraciones posibles.
     *
     * El wake word no dura lo mismo en todos los locutores (aquí varía entre
     * ~370 y ~520 ms), y alinear la plantilla con una ventana fija deja el
     * coseno por el suelo. En vez de un VAD —que en ruido no separa nada— se
     * prueban varias longitudes y gana la mejor: es la versión barata de DTW.
     *
     * Si la ventana no tiene energía por encima del silencio, el score es 0 y no
     * se evalúa: evita que el ruido numérico de las bandas en silencio se
     * amplifique al normalizar.
     */
    internal fun scoreWindow(): Double {
        if (frames.size < MIN_WINDOW_FRAMES) return 0.0
        if (frames.last().energy < SILENCE_GATE_NATS) return 0.0

        val snapshot = frames.toList()
        var best = 0.0
        for (length in WINDOW_FRAMES) {
            if (snapshot.size < length) continue
            val vector = SpectralFeatures.resample(snapshot, snapshot.size - length, snapshot.size)
                ?: continue
            for (template in templates) {
                val score = SpectralFeatures.cosine(vector, template.weights)
                if (score > best) best = score
            }
        }
        return best
    }

    private companion object {
        const val MAX_WINDOW_FRAMES = 70          // 700 ms a 10 ms por trama
        const val MIN_WINDOW_FRAMES = 30          // 300 ms: la palabra más corta
        const val SILENCE_GATE_NATS = -12.0       // ln de la potencia media (~-52 dBFS)
        const val REFRACTORY_MS = 800L
        const val SCORE_INTERVAL_MS = 32L         // una puntuación por trama de captura

        /** Marca de "nunca": restar `Long.MIN_VALUE` desborda a negativo. */
        const val NEVER = Long.MIN_VALUE / 2

        /** Duraciones candidatas del wake word, en tramas de 10 ms. */
        val WINDOW_FRAMES = (30..66 step 2).toList().toIntArray()
    }
}

/** Plantilla espectral ya normalizada (CMVN + L2), lista para coseno. */
internal data class SpectralTemplate(val id: String, val weights: FloatArray)

/** Trama analizada: 16 energías de banda y su energía media en escala log. */
internal data class FrameFeature(val bands: FloatArray, val energy: Double)

/**
 * Frente de análisis en streaming: 32 ms de ventana (512 muestras), 10 ms de
 * salto (160 muestras) y 16 bandas logarítmicas calculadas con Goertzel.
 */
internal class SpectralFrontend {
    private val ring = FloatArray(FRAME_SIZE)
    private val window = FloatArray(FRAME_SIZE)
    private val goertzel = GoertzelBank(BANDS)
    private var writeIndex = 0
    private var totalSamples = 0L

    fun push(sample: Float) {
        ring[writeIndex] = sample
        writeIndex = (writeIndex + 1) % FRAME_SIZE
        totalSamples++
    }

    /** Devuelve una trama nueva si el salto lo pide; si no, null. */
    fun pollFrame(): FrameFeature? {
        if (totalSamples < FRAME_SIZE) return null
        if ((totalSamples - FRAME_SIZE) % HOP_SIZE != 0L) return null

        var cursor = writeIndex
        for (i in 0 until FRAME_SIZE) {
            window[i] = ring[cursor] * HANN[i]
            cursor = (cursor + 1) % FRAME_SIZE
        }
        val bands = goertzel.energies(window)
        var squares = 0.0
        for (value in window) squares += value * value
        val energy = ln(squares / FRAME_SIZE + 1e-12)
        return FrameFeature(bands = bands, energy = energy)
    }

    fun reset() {
        ring.fill(0f)
        window.fill(0f)
        writeIndex = 0
        totalSamples = 0L
    }

    internal companion object {
        const val FRAME_SIZE = 512
        const val HOP_SIZE = 160
        const val BANDS = 16

        private val HANN = FloatArray(FRAME_SIZE) { i ->
            (0.5 - 0.5 * cos(2.0 * PI * i / (FRAME_SIZE - 1))).toFloat()
        }
    }
}

internal object SpectralFeatures {

    /** Re-muestrea la ventana activa a [SpectralFrontend.BANDS] x [GRID] y normaliza. */
    fun resample(frames: List<FrameFeature>, start: Int, endExclusive: Int): FloatArray? {
        val count = endExclusive - start
        if (count < 2) return null

        val bands = SpectralFrontend.BANDS
        val vector = FloatArray(GRID * bands)

        for (g in 0 until GRID) {
            val position = if (GRID == 1) 0.0 else g.toDouble() / (GRID - 1) * (count - 1)
            val low = position.toInt().coerceIn(0, count - 1)
            val high = (low + 1).coerceAtMost(count - 1)
            val weight = (position - low).toFloat()
            val a = frames[start + low].bands
            val b = frames[start + high].bands
            for (band in 0 until bands) {
                vector[g * bands + band] = a[band] * (1f - weight) + b[band] * weight
            }
        }
        return normalize(vector)
    }

    /**
     * CMVN por banda con suelo de desviación y L2 global.
     *
     * El suelo es lo que separa una banda con habla de una banda vacía: sin él,
     * una banda que en limpio está siempre en el suelo numérico tiene desviación
     * ~0 y, al dividir por ella, el ruido de fondo se amplifica hasta dominar el
     * vector. Con el suelo, las bandas sin energía real aportan poco y la
     * comparación mide las bandas donde sí hay habla.
     */
    fun normalize(vector: FloatArray): FloatArray {
        val bands = SpectralFrontend.BANDS
        val grid = vector.size / bands

        for (band in 0 until bands) {
            var mean = 0.0
            for (g in 0 until grid) mean += vector[g * bands + band]
            mean /= grid

            var variance = 0.0
            for (g in 0 until grid) {
                val delta = vector[g * bands + band] - mean
                variance += delta * delta
            }
            val std = sqrt(variance / grid).coerceAtLeast(MIN_BAND_STD)

            for (g in 0 until grid) {
                vector[g * bands + band] = ((vector[g * bands + band] - mean) / std).toFloat()
            }
        }

        var norm = 0.0
        for (value in vector) norm += value.toDouble() * value
        norm = sqrt(norm).coerceAtLeast(1e-6)
        for (i in vector.indices) vector[i] = (vector[i] / norm).toFloat()
        return vector
    }

    fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return 0.0
        var dot = 0.0
        for (i in a.indices) dot += a[i].toDouble() * b[i]
        return dot
    }

    internal const val GRID = 16

    /** Suelo de desviación por banda, en nats (~2,2 dB). */
    private const val MIN_BAND_STD = 0.5
}

/** Construye plantillas a partir de audio PCM ya recortado a la palabra. */
internal object SpectralTemplateBuilder {

    fun fromPcm(id: String, pcm: ShortArray, wordRange: IntRange): SpectralTemplate {
        val frontend = SpectralFrontend()
        val frames = mutableListOf<FrameFeature>()
        var frameIndex = 0

        for (sample in pcm) {
            frontend.push(sample / 32768f)
            val feature = frontend.pollFrame() ?: continue
            // Cada trama cubre 512 muestras con 160 de salto: su centro es
            // frameIndex * 160 + 256.
            val center = frameIndex * SpectralFrontend.HOP_SIZE + SpectralFrontend.FRAME_SIZE / 2
            frameIndex++
            if (center >= wordRange.first && center <= wordRange.last) {
                frames += feature
            }
        }

        require(frames.size >= 2) { "No hay tramas suficientes para la plantilla $id" }
        val vector = SpectralFeatures.resample(frames, 0, frames.size)
            ?: error("No se pudo normalizar la plantilla $id")
        return SpectralTemplate(id = id, weights = vector)
    }
}

/** Banco de 16 bandas logarítmicas mediante la recurrencia de Goertzel. */
internal class GoertzelBank(private val bands: Int) {

    private val coefficients = DoubleArray(bands) { index ->
        val frequency = MIN_HZ * (MAX_HZ / MIN_HZ).pow(index.toDouble() / (bands - 1))
        2.0 * cos(2.0 * PI * frequency / SYNTH_SAMPLE_RATE)
    }

    fun energies(window: FloatArray): FloatArray {
        val out = FloatArray(bands)
        var peak = Double.NEGATIVE_INFINITY
        for (band in 0 until bands) {
            val coefficient = coefficients[band]
            var s1 = 0.0
            var s2 = 0.0
            for (sample in window) {
                val s0 = sample + coefficient * s1 - s2
                s2 = s1
                s1 = s0
            }
            val power = s1 * s1 + s2 * s2 - coefficient * s1 * s2
            val value = ln(power.coerceAtLeast(1e-9))
            out[band] = value.toFloat()
            if (value > peak) peak = value
        }

        // Rango dinámico relativo a la banda más fuerte de la trama. Sin esto,
        // una banda vacía vale -20 nats y el ruido de fondo la sube a -10: la
        // diferencia aplasta la comparación. Con el recorte, todo lo que está
        // más de [DYNAMIC_RANGE_NATS] por debajo del pico es "fondo" y no
        // participa, tanto en la plantilla como en la consulta.
        for (band in 0 until bands) {
            out[band] = maxOf(out[band] - peak.toFloat(), -DYNAMIC_RANGE_NATS.toFloat())
        }
        return out
    }

    private companion object {
        const val MIN_HZ = 150.0
        const val MAX_HZ = 7_500.0
        const val DYNAMIC_RANGE_NATS = 6.0
    }
}
