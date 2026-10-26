package com.fifo.voicepipeline.pipeline.session.wakeword

import com.fifo.voicepipeline.audio.AudioConfig
import com.fifo.voicepipeline.pipeline.session.NoopWakeWordDetector
import com.fifo.voicepipeline.pipeline.session.WakeWordDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Arnés de medición de la invocación (ADR-0044).
 *
 * Mide la costura real —`WakeWordDetector.accept()` alimentado con tramas del
 * tamaño que entrega `PhoneMicRecorder`— contra el corpus sintético de
 * [WakeWordCorpus]. No hay binarios, ni descargas, ni emulador: la misma semilla
 * produce el mismo audio y las mismas métricas.
 *
 * El detector que se mide aquí es un **detector de referencia** por plantilla
 * espectral (ver [SpectralTemplateWakeWordDetector]). Su valor no es ser bueno:
 * es poner un número al techo del enfoque "huella acústica" y demostrar que hace
 * falta un modelo entrenado. Los números miden el arnés y el corpus, no el campo.
 */
class WakeWordHarnessTest {

    private data class ScoredFrame(val timeMs: Long, val score: Double)

    private data class Outcome(val detected: Boolean, val latencyMs: Long?)

    private data class Metrics(
        val threshold: Double,
        val frrBySnr: Map<String, Double>,
        val frrSameSpeakerClean: Double,
        val frrCrossSpeakerClean: Double,
        val medianCleanScoreSameSpeaker: Double,
        val medianCleanScoreCrossSpeaker: Double,
        val medianBestScoreBySnr: Map<String, Double>,
        val medianHardNegativeScore: Double,
        val medianConversationScore: Double,
        val hardNegativeAcceptRate: Double,
        val falseAcceptsPerHour: Double,
        val p50LatencyMs: Long,
        val p95LatencyMs: Long
    )

    // ── Corpus ────────────────────────────────────────────────────────────

    @Test
    fun `el corpus etiqueta la ventana de la palabra y respeta el audio seco`() {
        val positives = POSITIVES
        assertEquals(
            "6 locutores x 13 condiciones",
            WakeWordCorpus.EVALUATED.size * WakeWordCorpus.POSITIVE_CONDITIONS.size,
            positives.size
        )

        positives.forEach { case ->
            val start = requireNotNull(case.wordStartSample) { "positivo sin inicio: ${case.id}" }
            val end = requireNotNull(case.wordEndSample) { "positivo sin fin: ${case.id}" }
            assertTrue("ventana vacía en ${case.id}", end > start)
            assertTrue("ventana fuera del audio en ${case.id}", end < case.pcm.size)
            assertEquals("el audio seco cambia de tamaño en ${case.id}", case.dryPcm.size, case.pcm.size)

            val wordMs = (end - start) * 1000L / SYNTH_SAMPLE_RATE
            assertTrue("palabra demasiado corta en ${case.id}: $wordMs ms", wordMs >= 250)
            assertTrue("palabra demasiado larga en ${case.id}: $wordMs ms", wordMs <= 900)
        }

        val noisy = positives.filter { it.condition.snrDb != null }
        assertTrue("faltan casos con ruido", noisy.size >= 60)
        noisy.forEach { case ->
            assertTrue("el ruido no se aplicó en ${case.id}", !case.pcm.contentEquals(case.dryPcm))
        }

        val conversations = CONVERSATIONS
        assertTrue("faltan conversaciones", conversations.size >= 20)
        conversations.forEach { case ->
            val seconds = case.durationMs / 1000.0
            assertTrue("clip de conversación demasiado corto: ${case.id} = $seconds s", seconds >= 12.0)
            assertTrue("clip de conversación demasiado largo: ${case.id} = $seconds s", seconds <= 30.0)
        }

        val negatives = NEGATIVES
        assertEquals(
            "8 palabras x 6 locutores x 3 condiciones",
            SimulatedWords.HARD_NEGATIVES.size * WakeWordCorpus.EVALUATED.size *
                WakeWordCorpus.NEGATIVE_CONDITIONS.size,
            negatives.size
        )
        assertTrue("los negativos no pueden etiquetar palabra", negatives.all { it.wordStartSample == null })
    }

    @Test
    fun `la mezcla baja la relacion senal-ruido hasta el valor pedido`() {
        val signal = FormantSynth.renderWord(
            SimulatedWords.FIFO,
            WakeWordCorpus.ENROLLED.first(),
            kotlin.random.Random(7)
        )
        val noise = SimulatedNoise.white(signal.size, kotlin.random.Random(11))

        var previous = Double.MAX_VALUE
        for (snr in listOf(20, 10, 5, 0)) {
            val mixed = SimulatedNoise.mixAtSnr(signal, noise, snr)
            val measured = measuredSnr(signal, mixed)
            assertEquals("la SNR medida se aleja de la nominal a $snr dB", snr.toDouble(), measured, 2.0)
            assertTrue("la SNR no baja al empeorar la condición: $measured >= $previous", measured < previous)
            previous = measured
        }
    }

    @Test
    fun `un detector ausente no despierta nunca`() {
        val missed = POSITIVES.count { streamWith(NoopWakeWordDetector, it.pcm).isEmpty() }
        assertEquals("Noop debe fallar todos los positivos", POSITIVES.size, missed)
    }

    // ── Métricas ──────────────────────────────────────────────────────────

    @Test
    fun `el detector de referencia mide FRR por SNR y falsos aceptos por hora`() {
        val positives = POSITIVE_TRACES
        val negatives = NEGATIVE_TRACES
        val conversations = CONVERSATION_TRACES

        val report = StringBuilder()
        report.appendLine(
            "Corpus: ${positives.size} positivos, ${negatives.size} negativos difíciles, " +
                "${conversations.size} conversaciones " +
                "(${String.format(Locale.ROOT, "%.3f", conversationHours(conversations.map { it.first }))} h)"
        )
        report.appendLine(
            "Umbral  FRR-limpio  FRR-20dB  FRR-10dB  FRR-5dB  FRR-0dB   FA-neg   FA/h    p50     p95"
        )

        val thresholds = listOf(0.40, 0.45, 0.50, 0.55, 0.60, 0.65, 0.70, 0.72, 0.74, 0.76, 0.78)
        val evaluated = thresholds.map { threshold ->
            val metrics = metrics(threshold, positives, negatives, conversations)
            report.appendLine(formatMetrics(metrics))
            metrics
        }
        report.appendLine()

        val production = evaluated.first { it.threshold == REFERENCE_THRESHOLD }
        report.appendLine("Umbral de referencia elegido: ${production.threshold}")
        report.appendLine(
            "  scores medianos: " + production.medianBestScoreBySnr.entries.joinToString(" · ") {
                "${it.key}=${fmt(it.value)}"
            } + " · negativos=${fmt(production.medianHardNegativeScore)}" +
                " · charla=${fmt(production.medianConversationScore)}"
        )
        report.appendLine(
            "  limpio: mismo locutor FRR=${pct(production.frrSameSpeakerClean)} " +
                "(score ${fmt(production.medianCleanScoreSameSpeaker)}) · " +
                "locutor no visto FRR=${pct(production.frrCrossSpeakerClean)} " +
                "(score ${fmt(production.medianCleanScoreCrossSpeaker)})"
        )
        report.appendLine(
            "  negativos difíciles aceptados=${pct(production.hardNegativeAcceptRate)} · " +
                "latencia p50=${production.p50LatencyMs} ms p95=${production.p95LatencyMs} ms"
        )

        val budget = budgetThreshold(positives, negatives, conversations)
        report.appendLine("  umbral para <=1 FA/h: ${fmt(budget.first)} -> FRR limpio=${pct(budget.second)}")
        println(report)
        System.out.flush()

        // El arnés no puede terminar sin señal: el detector de referencia tiene
        // que discriminar en limpio y degradarse con el ruido.
        assertTrue(
            "el detector de referencia falla demasiado en limpio: ${production.frrBySnr["clean"]}",
            production.frrBySnr.getValue("clean") <= 0.20
        )
        assertTrue(
            "el punto de trabajo no respeta el presupuesto de falsos aceptos: " +
                "${production.falseAcceptsPerHour} FA/h",
            production.falseAcceptsPerHour <= 1.0
        )
        assertTrue(
            "los scores deben bajar al bajar la SNR: ${production.medianBestScoreBySnr}",
            listOf("clean", "20", "10", "5", "0")
                .map { production.medianBestScoreBySnr.getValue(it) }
                .zipWithNext()
                .all { (better, worse) -> better > worse }
        )
        assertTrue(
            "latencia p95 fuera de lo razonable: ${production.p95LatencyMs} ms",
            production.p95LatencyMs in 0..250
        )
        assertTrue(
            "un locutor no visto supera el 50% de FRR en limpio: " +
                "${production.frrCrossSpeakerClean}",
            production.frrCrossSpeakerClean <= 0.50
        )
    }

    /**
     * La traza fuera de línea y el `accept()` real tienen que coincidir: si no,
     * las métricas estarían midiendo otro detector.
     */
    @Test
    fun `el detector real detecta el positivo limpio en la costura`() {
        val clean = POSITIVES.first { it.condition.snrDb == null }
        val detector = SpectralTemplateWakeWordDetector(
            templates = TEMPLATES,
            threshold = REFERENCE_THRESHOLD
        )

        val detections = streamWith(detector, clean.pcm)
        assertTrue("el positivo limpio debe detectarse", detections.isNotEmpty())

        val start = requireNotNull(clean.wordStartMs)
        val end = requireNotNull(clean.wordEndMs)
        assertTrue(
            "la detección debe caer en la ventana de la palabra: $detections vs [$start,$end]",
            detections.any { it in (start - PRE_WINDOW_MS)..(end + POST_WINDOW_MS) }
        )
    }

    // ── Costura ───────────────────────────────────────────────────────────

    private fun metrics(
        threshold: Double,
        positives: List<Pair<WakeWordCase, List<ScoredFrame>>>,
        negatives: List<Pair<WakeWordCase, List<ScoredFrame>>>,
        conversations: List<Pair<WakeWordCase, List<ScoredFrame>>>
    ): Metrics {
        val outcomes = positives.map { (case, frames) -> case to outcome(case, frames, threshold) }

        val frrBySnr = positives.groupBy { (case, _) -> snrKey(case.condition) }
            .mapValues { (_, group) ->
                val detected = group.count { (case, frames) -> outcome(case, frames, threshold).detected }
                1.0 - detected.toDouble() / group.size
            }

        val cleanOutcomes = outcomes.filter { (case, _) -> case.condition.snrDb == null }
        val cleanTraces = positives.filter { (case, _) -> case.condition.snrDb == null }

        val hardNegativeAcceptRate = negatives.count { (_, frames) ->
            frames.any { it.score >= threshold }
        }.toDouble() / negatives.size.coerceAtLeast(1)

        val conversationEvents = conversations.sumOf { (_, frames) -> countEvents(frames, threshold) }
        val falseAcceptsPerHour = conversationEvents /
            conversationHours(conversations.map { it.first }).coerceAtLeast(1e-6)

        val latencies = outcomes.mapNotNull { (_, outcome) -> outcome.latencyMs }.sorted()

        return Metrics(
            threshold = threshold,
            frrBySnr = frrBySnr,
            frrSameSpeakerClean = missRate(cleanOutcomes.filter { (case, _) -> case.enrolled }),
            frrCrossSpeakerClean = missRate(cleanOutcomes.filter { (case, _) -> !case.enrolled }),
            medianCleanScoreSameSpeaker = medianBestScore(cleanTraces.filter { (case, _) -> case.enrolled }),
            medianCleanScoreCrossSpeaker = medianBestScore(cleanTraces.filter { (case, _) -> !case.enrolled }),
            medianBestScoreBySnr = positives.groupBy { (case, _) -> snrKey(case.condition) }
                .mapValues { (_, group) -> medianBestScore(group) },
            medianHardNegativeScore = medianBestScore(negatives),
            medianConversationScore = medianBestScore(conversations),
            hardNegativeAcceptRate = hardNegativeAcceptRate,
            falseAcceptsPerHour = falseAcceptsPerHour,
            p50LatencyMs = percentile(latencies, 0.50),
            p95LatencyMs = percentile(latencies, 0.95)
        )
    }

    private fun outcome(case: WakeWordCase, frames: List<ScoredFrame>, threshold: Double): Outcome {
        val start = requireNotNull(case.wordStartMs) { "positivo sin etiqueta: ${case.id}" }
        val end = requireNotNull(case.wordEndMs) { "positivo sin etiqueta: ${case.id}" }
        val hit = frames.firstOrNull {
            it.score >= threshold && it.timeMs in (start - PRE_WINDOW_MS)..(end + POST_WINDOW_MS)
        }
        return Outcome(detected = hit != null, latencyMs = hit?.let { it.timeMs - end })
    }

    private fun missRate(outcomes: List<Pair<WakeWordCase, Outcome>>): Double {
        if (outcomes.isEmpty()) return 0.0
        return outcomes.count { (_, outcome) -> !outcome.detected }.toDouble() / outcomes.size
    }

    /** Une tramas consecutivas sobre el umbral: un falso acepto es un evento, no una ráfaga. */
    private fun countEvents(frames: List<ScoredFrame>, threshold: Double): Int {
        var events = 0
        var lastAbove = Long.MIN_VALUE
        for (frame in frames) {
            if (frame.score < threshold) continue
            if (lastAbove == Long.MIN_VALUE || frame.timeMs - lastAbove > MERGE_GAP_MS) events++
            lastAbove = frame.timeMs
        }
        return events
    }

    /** Umbral mínimo que respeta 1 FA/h y el FRR limpio que cuesta. */
    private fun budgetThreshold(
        positives: List<Pair<WakeWordCase, List<ScoredFrame>>>,
        negatives: List<Pair<WakeWordCase, List<ScoredFrame>>>,
        conversations: List<Pair<WakeWordCase, List<ScoredFrame>>>
    ): Pair<Double, Double> {
        var threshold = 0.10
        while (threshold < 0.999) {
            val metrics = metrics(threshold, positives, negatives, conversations)
            if (metrics.falseAcceptsPerHour <= 1.0) {
                return threshold to metrics.frrBySnr.getValue("clean")
            }
            threshold += 0.005
        }
        return 0.999 to metrics(0.999, positives, negatives, conversations).frrBySnr.getValue("clean")
    }

    private fun conversationHours(cases: List<WakeWordCase>): Double =
        cases.sumOf { it.durationMs }.toDouble() / 3_600_000.0

    private fun medianBestScore(cases: List<Pair<WakeWordCase, List<ScoredFrame>>>): Double {
        val scores = cases.map { (_, frames) -> frames.maxOfOrNull { it.score } ?: 0.0 }.sorted()
        if (scores.isEmpty()) return 0.0
        return scores[scores.size / 2]
    }

    private fun percentile(sorted: List<Long>, fraction: Double): Long {
        if (sorted.isEmpty()) return 0
        val index = ((sorted.size - 1) * fraction).roundToInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    private fun formatMetrics(metrics: Metrics): String = String.format(
        Locale.ROOT,
        "%.2f       %s     %s     %s     %s     %s     %s     %5.1f   %4d ms  %4d ms",
        metrics.threshold,
        pct(metrics.frrBySnr.getValue("clean")),
        pct(metrics.frrBySnr.getValue("20")),
        pct(metrics.frrBySnr.getValue("10")),
        pct(metrics.frrBySnr.getValue("5")),
        pct(metrics.frrBySnr.getValue("0")),
        pct(metrics.hardNegativeAcceptRate),
        metrics.falseAcceptsPerHour,
        metrics.p50LatencyMs,
        metrics.p95LatencyMs
    )

    private fun snrKey(condition: CorpusCondition): String = condition.snrDb?.toString() ?: "clean"

    private fun pct(value: Double): String = String.format(Locale.ROOT, "%5.1f%%", value * 100.0)

    private fun fmt(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

    private companion object {

        /**
         * Umbral del detector de referencia. No es el umbral de un modelo de
         * producción: es el punto de trabajo con el que se documenta el techo del
         * enfoque por plantilla espectral (ver ADR-0044).
         */
        const val REFERENCE_THRESHOLD = 0.65

        /** La detección vale si cae en la palabra o poco antes/después. */
        const val PRE_WINDOW_MS = 300L
        const val POST_WINDOW_MS = 500L

        /** Dos tramas sobre el umbral separadas por menos de esto son un solo evento. */
        const val MERGE_GAP_MS = 400L

        // El corpus se construye una sola vez por clase: JUnit instancia la
        // prueba para cada método y regenerar 12 minutos de audio por método
        // haría el arnés inútil como prueba de regresión.
        val POSITIVES: List<WakeWordCase> by lazy { WakeWordCorpus.positives() }
        val NEGATIVES: List<WakeWordCase> by lazy { WakeWordCorpus.hardNegatives() }
        val CONVERSATIONS: List<WakeWordCase> by lazy { WakeWordCorpus.conversations() }

        val TEMPLATES: List<SpectralTemplate> by lazy {
            WakeWordCorpus.ENROLLED.map { speaker ->
                SpectralTemplateBuilder.fromPcm(
                    id = WakeWordCorpus.enrollmentClipId(speaker),
                    pcm = WakeWordCorpus.enrollmentClip(speaker),
                    wordRange = WakeWordCorpus.enrollmentWordBounds(speaker)
                )
            }
        }

        val POSITIVE_TRACES: List<Pair<WakeWordCase, List<ScoredFrame>>> by lazy {
            POSITIVES.map { it to trace(it, TEMPLATES) }
        }
        val NEGATIVE_TRACES: List<Pair<WakeWordCase, List<ScoredFrame>>> by lazy {
            NEGATIVES.map { it to trace(it, TEMPLATES) }
        }
        val CONVERSATION_TRACES: List<Pair<WakeWordCase, List<ScoredFrame>>> by lazy {
            CONVERSATIONS.map { it to trace(it, TEMPLATES) }
        }

        /**
         * Alimenta el PCM como lo haría `PhoneMicRecorder`: tramas de
         * [AudioConfig.CHUNK_BYTES] con marca de tiempo de fin de trama.
         */
        fun streamWith(detector: WakeWordDetector, pcm: ShortArray): List<Long> {
            val detections = ArrayList<Long>()
            val totalBytes = pcm.size * 2
            var offset = 0
            while (offset < totalBytes) {
                val size = minOf(AudioConfig.CHUNK_BYTES, totalBytes - offset)
                val frame = ByteArray(size)
                var byte = 0
                while (byte < size) {
                    val sample = pcm[(offset + byte) / 2].toInt()
                    frame[byte] = (sample and 0xFF).toByte()
                    if (byte + 1 < size) frame[byte + 1] = ((sample shr 8) and 0xFF).toByte()
                    byte += 2
                }
                val endSample = (offset + size) / 2
                val nowMillis = endSample * 1000L / SYNTH_SAMPLE_RATE
                detector.accept(frame, nowMillis)?.let { detections += nowMillis }
                offset += size
            }
            return detections
        }

        /**
         * Una pasada con umbral inalcanzable: el detector puntúa cada trama y el
         * observador la guarda, de modo que todos los umbrales se calibran sin
         * volver a analizar el audio.
         */
        fun trace(case: WakeWordCase, templates: List<SpectralTemplate>): List<ScoredFrame> {
            val frames = ArrayList<ScoredFrame>()
            val detector = SpectralTemplateWakeWordDetector(
                templates = templates,
                threshold = 1.0,
                onScore = { score, atMillis -> frames += ScoredFrame(atMillis, score) }
            )
            streamWith(detector, case.pcm)
            return frames
        }

        /**
         * SNR medida sobre la señal mezclada: se resta la señal seca y se compara
         * la potencia de la señal con la del residuo (ruido escalado).
         */
        fun measuredSnr(signal: ShortArray, mixed: ShortArray): Double {
            val dry = DoubleArray(signal.size) { signal[it] / 32768.0 }
            val peak = dry.maxOfOrNull { abs(it) } ?: 0.0
            val activeThreshold = peak * 0.06

            var signalSquares = 0.0
            var activeCount = 0
            var residualSquares = 0.0
            for (i in signal.indices) {
                val mixedValue = mixed[i] / 32768.0
                if (abs(dry[i]) >= activeThreshold) {
                    signalSquares += dry[i] * dry[i]
                    activeCount++
                }
                val residual = mixedValue - dry[i]
                residualSquares += residual * residual
            }

            val signalRms = kotlin.math.sqrt(signalSquares / activeCount.coerceAtLeast(1)).coerceAtLeast(1e-9)
            val residualRms = kotlin.math.sqrt(residualSquares / signal.size.coerceAtLeast(1)).coerceAtLeast(1e-9)
            return 20.0 * kotlin.math.log10(signalRms / residualRms)
        }
    }
}
