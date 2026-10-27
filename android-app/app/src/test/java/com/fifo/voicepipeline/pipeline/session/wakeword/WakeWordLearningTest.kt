package com.fifo.voicepipeline.pipeline.session.wakeword

import com.fifo.voicepipeline.audio.AudioConfig
import com.fifo.voicepipeline.pipeline.session.WakeWordDetector
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Entrenamiento y evaluación del clasificador (ADR-0046).
 *
 * La pregunta es concreta: con las mismas features que usa el detector de
 * plantilla, ¿basta entrenar con sala, ruido, Lombard y tractos distintos para
 * dejar de confundir el canal con la palabra?
 *
 * El modelo se entrena con locutores y posiciones de sala que **no** aparecen en
 * el corpus de evaluación. El umbral se calibra con un tercer conjunto
 * (validación) y solo después se mide el campo. Si se calibrara con el mismo
 * conjunto que se reporta, el número sería una profecía.
 *
 * Usa la copia congelada de 40 + 8 locutores (`adr0046*Clips`) para que la
 * tabla de ADR-0046 siga siendo reproducible aunque el corpus de exportación de
 * ADR-0047 haya crecido.
 */
class WakeWordLearningTest {

    private data class ScoredFrame(val timeMs: Long, val score: Double)

    private data class FieldRow(
        val id: String,
        val cases: Int,
        val frr: Double,
        val medianScore: Double,
        val p95LatencyMs: Long
    )

    @Test
    fun `el modelo entrenado con aumentacion supera al template en campo`() {
        assumeTrue(
            "experimento offline; ejecuta con FIFO_WAKEWORD_EXPERIMENT=1",
            System.getenv("FIFO_WAKEWORD_EXPERIMENT") == "1"
        )

        val threshold = THRESHOLD

        val learned = FIELD_CONDITIONS.map { condition ->
            val group = FIELD_POSITIVE_TRACES.filter { (case, _) -> case.condition.id == condition.id }
            row(condition.id, group, threshold)
        }
        val template = FIELD_CONDITIONS.map { condition ->
            val group = TEMPLATE_POSITIVE_TRACES.filter { (case, _) -> case.condition.id == condition.id }
            row(condition.id, group, TEMPLATE_THRESHOLD)
        }

        val learnedNegatives = FIELD_NEGATIVE_TRACES.count { (_, frames) -> frames.any { it.score >= threshold } }
            .toDouble() / FIELD_NEGATIVE_TRACES.size.coerceAtLeast(1)
        val learnedFalseAccepts = FIELD_CONVERSATION_TRACES.sumOf { (_, frames) -> countEvents(frames, threshold) } /
            conversationHours(FIELD_CONVERSATION_TRACES.map { it.first }).coerceAtLeast(1e-6)

        val templateNegatives = TEMPLATE_NEGATIVE_TRACES.count { (_, frames) ->
            frames.any { it.score >= TEMPLATE_THRESHOLD }
        }.toDouble() / TEMPLATE_NEGATIVE_TRACES.size.coerceAtLeast(1)
        val templateFalseAccepts =
            TEMPLATE_CONVERSATION_TRACES.sumOf { (_, frames) -> countEvents(frames, TEMPLATE_THRESHOLD) } /
                conversationHours(TEMPLATE_CONVERSATION_TRACES.map { it.first }).coerceAtLeast(1e-6)

        val baseFrr = BASE_POSITIVE_TRACES.count { (case, frames) ->
            outcome(case.wordStartMs, case.wordEndMs, frames, threshold) == null
        }.toDouble() / BASE_POSITIVE_TRACES.size.coerceAtLeast(1)
        val baseFalseAccepts = BASE_CONVERSATION_TRACES.sumOf { (_, frames) -> countEvents(frames, threshold) } /
            conversationHours(BASE_CONVERSATION_TRACES.map { it.first }).coerceAtLeast(1e-6)
        val baseNegativeAccept = BASE_NEGATIVE_TRACES.count { (_, frames) ->
            frames.any { it.score >= threshold }
        }.toDouble() / BASE_NEGATIVE_TRACES.size.coerceAtLeast(1)

        val report = StringBuilder()
        report.appendLine(
            "Entrenamiento: ${TRAIN_SAMPLES} ventanas (${TRAIN_POSITIVES} positivas, " +
                "${TRAIN_SAMPLES - TRAIN_POSITIVES} negativas) de " +
                "${TRAIN_CLIPS.size} clips"
        )
        val trainPositiveScores = TRAIN_SET.filter { it.label == 1 }.map { MODEL.score(it.vector) }.sorted()
        val trainNegativeScores = TRAIN_SET.filter { it.label == 0 }.map { MODEL.score(it.vector) }.sorted()
        report.appendLine(
            "  ajuste en entrenamiento: positivos mediana=${fmt(median(trainPositiveScores))} " +
                "(min=${fmt(trainPositiveScores.first())}) · " +
                "negativos mediana=${fmt(median(trainNegativeScores))} " +
                "(p99=${fmt(percentileDouble(trainNegativeScores, 0.99))})"
        )
        report.appendLine("Umbral calibrado en validación: ${fmt(threshold)}")
        report.appendLine("Condición               casos   FRR-L   FRR-T   score-L  p95-L")
        FIELD_CONDITIONS.forEachIndexed { index, condition ->
            val l = learned[index]
            val t = template[index]
            report.appendLine(
                String.format(
                    Locale.ROOT,
                    "%-21s  %2d   %s   %s   %s   %4d ms",
                    condition.id, l.cases, pct(l.frr), pct(t.frr), fmt(l.medianScore), l.p95LatencyMs
                )
            )
        }
        report.appendLine(
            "  modelo:  negativos aceptados=${pct(learnedNegatives)} · FA/h=${oneDecimal(learnedFalseAccepts)}"
        )
        report.appendLine(
            "  modelo en corpus base: FRR=${pct(baseFrr)} · negativos=${pct(baseNegativeAccept)} · " +
                "FA/h=${oneDecimal(baseFalseAccepts)}"
        )
        report.appendLine(
            "  template: negativos aceptados=${pct(templateNegatives)} · FA/h=${oneDecimal(templateFalseAccepts)}"
        )
        println(report)
        System.out.flush()

        val learnedOffice = learned.first { it.id == "office-clean" }
        val templateOffice = template.first { it.id == "office-clean" }

        assertEquals(
            "cada condición debe cubrir todos los locutores de campo",
            WakeWordCorpus.FIELD_SPEAKERS.size,
            learnedOffice.cases
        )
        // Este experimento no afirma que el modelo sea bueno: imprime la tabla
        // que sostiene ADR-0046. La barra de producción (≤1 FA/h) no se cumple,
        // y esa es precisamente la conclusión documentada.
        println(
            "Conclusión: modelo FRR=${pct(learnedOffice.frr)} en despacho " +
                "(template ${pct(templateOffice.frr)}) y ${oneDecimal(learnedFalseAccepts)} FA/h; " +
                "no sustituye a Noop."
        )
    }

    // ── Trazas ────────────────────────────────────────────────────────────

    private fun row(
        id: String,
        group: List<Pair<WakeWordCase, List<ScoredFrame>>>,
        threshold: Double
    ): FieldRow {
        val outcomes = group.map { (case, frames) ->
            outcome(case.wordStartMs, case.wordEndMs, frames, threshold)
        }
        return FieldRow(
            id = id,
            cases = group.size,
            frr = outcomes.count { it == null }.toDouble() / outcomes.size.coerceAtLeast(1),
            medianScore = medianBest(group),
            p95LatencyMs = percentile(outcomes.mapNotNull { it }.sorted(), 0.95)
        )
    }

    private fun outcome(
        startMs: Long?,
        endMs: Long?,
        frames: List<ScoredFrame>,
        threshold: Double
    ): Long? {
        if (startMs == null || endMs == null) return null
        return frames.firstOrNull {
            it.score >= threshold && it.timeMs in (startMs - PRE_WINDOW_MS)..(endMs + POST_WINDOW_MS)
        }?.let { it.timeMs - endMs }
    }

    private fun medianBest(group: List<Pair<WakeWordCase, List<ScoredFrame>>>): Double {
        val scores = group.map { (_, frames) -> frames.maxOfOrNull { it.score } ?: 0.0 }.sorted()
        if (scores.isEmpty()) return 0.0
        return scores[scores.size / 2]
    }

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

    private fun percentile(sorted: List<Long>, fraction: Double): Long {
        if (sorted.isEmpty()) return 0
        val index = ((sorted.size - 1) * fraction).roundToInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    private fun conversationHours(cases: List<WakeWordCase>): Double =
        cases.sumOf { it.durationMs }.toDouble() / 3_600_000.0

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        return values[values.size / 2]
    }

    private fun percentileDouble(sorted: List<Double>, fraction: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val index = ((sorted.size - 1) * fraction).roundToInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    private fun pct(value: Double): String = String.format(Locale.ROOT, "%5.1f%%", value * 100.0)

    private fun fmt(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

    private fun oneDecimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

    private companion object {

        const val TEMPLATE_THRESHOLD = 0.65
        const val PRE_WINDOW_MS = 300L
        const val POST_WINDOW_MS = 500L
        const val MERGE_GAP_MS = 400L

        val TRAIN_CLIPS: List<TrainingClip> by lazy { WakeWordTrainingCorpus.adr0046TrainingClips() }
        val TRAIN_SET: List<WakeWordTrainer.Sample> by lazy { WakeWordTrainer.samples(TRAIN_CLIPS) }

        val MODEL: WakeWordScoreModel by lazy {
            WakeWordTrainer.trainSamples(TRAIN_SET, positiveWeight = 30.0, l2 = 1e-3)
        }

        val TRAIN_SAMPLES: Int by lazy { TRAIN_SET.size }
        val TRAIN_POSITIVES: Int by lazy { TRAIN_SET.count { it.label == 1 } }

        /**
         * Umbral elegido con validación: el más bajo que respeta 1 FA/h en las
         * conversaciones de locutores que el modelo no vio. No usa el campo.
         */
        val THRESHOLD: Double by lazy { calibrate(MODEL) }

        val TEMPLATES: List<SpectralTemplate> by lazy {
            WakeWordCorpus.ENROLLED.map { speaker ->
                SpectralTemplateBuilder.fromPcm(
                    id = WakeWordCorpus.enrollmentClipId(speaker),
                    pcm = WakeWordCorpus.enrollmentClip(speaker),
                    wordRange = WakeWordCorpus.enrollmentWordBounds(speaker)
                )
            }
        }

        val FIELD_POSITIVES: List<WakeWordCase> by lazy { WakeWordCorpus.fieldPositives() }
        val FIELD_NEGATIVES: List<WakeWordCase> by lazy { WakeWordCorpus.fieldHardNegatives() }
        val FIELD_CONVERSATIONS: List<WakeWordCase> by lazy { WakeWordCorpus.fieldConversations() }

        val BASE_POSITIVES: List<WakeWordCase> by lazy { WakeWordCorpus.positives() }
        val BASE_NEGATIVES: List<WakeWordCase> by lazy { WakeWordCorpus.hardNegatives() }
        val BASE_CONVERSATIONS: List<WakeWordCase> by lazy { WakeWordCorpus.conversations() }

        val BASE_POSITIVE_TRACES: List<Pair<WakeWordCase, List<ScoredFrame>>> by lazy {
            BASE_POSITIVES.map { it to learnedTrace(it) }
        }
        val BASE_NEGATIVE_TRACES: List<Pair<WakeWordCase, List<ScoredFrame>>> by lazy {
            BASE_NEGATIVES.map { it to learnedTrace(it) }
        }
        val BASE_CONVERSATION_TRACES: List<Pair<WakeWordCase, List<ScoredFrame>>> by lazy {
            BASE_CONVERSATIONS.map { it to learnedTrace(it) }
        }

        val FIELD_CONDITIONS: List<CorpusCondition>
            get() = WakeWordCorpus.FIELD_CONDITIONS

        val FIELD_POSITIVE_TRACES: List<Pair<WakeWordCase, List<ScoredFrame>>> by lazy {
            FIELD_POSITIVES.map { it to learnedTrace(it) }
        }
        val FIELD_NEGATIVE_TRACES: List<Pair<WakeWordCase, List<ScoredFrame>>> by lazy {
            FIELD_NEGATIVES.map { it to learnedTrace(it) }
        }
        val FIELD_CONVERSATION_TRACES: List<Pair<WakeWordCase, List<ScoredFrame>>> by lazy {
            FIELD_CONVERSATIONS.map { it to learnedTrace(it) }
        }

        val TEMPLATE_POSITIVE_TRACES: List<Pair<WakeWordCase, List<ScoredFrame>>> by lazy {
            FIELD_POSITIVES.map { it to templateTrace(it) }
        }
        val TEMPLATE_NEGATIVE_TRACES: List<Pair<WakeWordCase, List<ScoredFrame>>> by lazy {
            FIELD_NEGATIVES.map { it to templateTrace(it) }
        }
        val TEMPLATE_CONVERSATION_TRACES: List<Pair<WakeWordCase, List<ScoredFrame>>> by lazy {
            FIELD_CONVERSATIONS.map { it to templateTrace(it) }
        }

        fun learnedTrace(case: WakeWordCase): List<ScoredFrame> {
            val frames = ArrayList<ScoredFrame>()
            val detector = LearnedWakeWordDetector(
                model = MODEL,
                threshold = 1.0,
                onScore = { score, atMillis -> frames += ScoredFrame(atMillis, score) }
            )
            stream(detector, case.pcm)
            return frames
        }

        fun templateTrace(case: WakeWordCase): List<ScoredFrame> {
            val frames = ArrayList<ScoredFrame>()
            val detector = SpectralTemplateWakeWordDetector(
                templates = TEMPLATES,
                threshold = 1.0,
                onScore = { score, atMillis -> frames += ScoredFrame(atMillis, score) }
            )
            stream(detector, case.pcm)
            return frames
        }

        fun stream(detector: WakeWordDetector, pcm: ShortArray) {
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
                detector.accept(frame, endSample * 1000L / SYNTH_SAMPLE_RATE)
                offset += size
            }
        }

        /**
         * Traza de calibración: ventanas de conversación de validación, a umbral
         * inalcanzable para conservar todos los scores.
         */
        fun calibrate(model: WakeWordScoreModel): Double {
            val clips = WakeWordTrainingCorpus.adr0046ValidationClips().filter { it.id.contains("-talk-") }
            val traces = ArrayList<ScoredFrame>()
            for (clip in clips) {
                val detector = LearnedWakeWordDetector(
                    model = model,
                    threshold = 1.0,
                    onScore = { score, atMillis -> traces += ScoredFrame(atMillis, score) }
                )
                stream(detector, clip.pcm)
            }
            val hours = clips.sumOf { it.pcm.size }.toDouble() / SYNTH_SAMPLE_RATE / 3600.0
            var threshold = 0.05
            while (threshold < 0.95) {
                val events = countEventsStatic(traces, threshold)
                if (events / hours.coerceAtLeast(1e-6) <= 1.0) return threshold
                threshold += 0.01
            }
            return 0.90
        }

        fun countEventsStatic(frames: List<ScoredFrame>, threshold: Double): Int {
            var events = 0
            var lastAbove = Long.MIN_VALUE
            for (frame in frames) {
                if (frame.score < threshold) continue
                if (lastAbove == Long.MIN_VALUE || frame.timeMs - lastAbove > MERGE_GAP_MS) events++
                lastAbove = frame.timeMs
            }
            return events
        }
    }
}
