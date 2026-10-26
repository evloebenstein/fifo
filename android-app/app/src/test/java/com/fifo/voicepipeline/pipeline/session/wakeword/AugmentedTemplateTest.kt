package com.fifo.voicepipeline.pipeline.session.wakeword

import com.fifo.voicepipeline.audio.AudioConfig
import com.fifo.voicepipeline.pipeline.session.WakeWordDetector
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Locale
import kotlin.random.Random

/**
 * Prueba exploratoria (ADR-0046): ¿qué parte del fallo de campo se arregla
 * aumentando el enrolamiento?
 *
 * Dos bancos:
 *
 * - **Salas**: un "fifo" seco se convoluciona con cada sala. Arregla el canal,
 *   pero no el ruido ni el esfuerzo vocal.
 * - **Condiciones**: el "fifo" se genera con el mismo esfuerzo, sala y ruido que
 *   la condición de uso, con otra semilla. Es el equivalente a pedirle al
 *   usuario que repita la palabra en su salón con la televisión puesta.
 *
 * Ninguno se envía a producción: miden cuánto del problema es de canal y cuánto
 * exige un modelo entrenado con muchas voces.
 */
class AugmentedTemplateTest {

    private data class ScoredFrame(val timeMs: Long, val score: Double)

    @Test
    fun `el enrolamiento por condicion recupera sala y ruido`() {
        assumeTrue(
            "experimento offline; ejecuta con FIFO_WAKEWORD_EXPERIMENT=1",
            System.getenv("FIFO_WAKEWORD_EXPERIMENT") == "1"
        )

        val report = StringBuilder()

        for ((name, templates) in listOf(
            "salas" to AUGMENTED_TEMPLATES,
            "condiciones" to CONDITION_TEMPLATES
        )) {
            val positiveTraces = POSITIVES.map { it to trace(it, templates) }
            val negativeTraces = NEGATIVES.map { it to trace(it, templates) }
            val conversationTraces = CONVERSATIONS.map { it to trace(it, templates) }

            report.appendLine("Banco: $name (${templates.size} plantillas)")
            for (threshold in listOf(0.55, 0.60, 0.65, 0.70, 0.75)) {
                val rows = WakeWordCorpus.FIELD_CONDITIONS.map { condition ->
                    val group = positiveTraces.filter { (case, _) -> case.condition.id == condition.id }
                    val frr = group.count { (case, frames) -> !detected(case, frames, threshold) }
                        .toDouble() / group.size
                    val scores = group.map { (_, frames) -> frames.maxOf { it.score } }.sorted()
                    Triple(condition.id, frr, scores[scores.size / 2])
                }
                val negatives = negativeTraces.count { (_, frames) -> frames.any { it.score >= threshold } }
                    .toDouble() / negativeTraces.size
                val events = conversationTraces.sumOf { (_, frames) -> countEvents(frames, threshold) }
                val hours = conversationTraces.sumOf { (case, _) -> case.durationMs }.toDouble() / 3_600_000.0
                val overall = positiveTraces.count { (case, frames) -> !detected(case, frames, threshold) }
                    .toDouble() / positiveTraces.size

                report.appendLine(
                    "  ${fmt(threshold)}: FRR=${pct(overall)} negativos=${pct(negatives)} " +
                        "FA/h=${fmt(events / hours)} · " +
                        rows.joinToString(" · ") { (id, frr, _) -> "$id=${pct(frr)}" }
                )
            }
        }
        println(report)
        System.out.flush()
    }

    private fun detected(case: WakeWordCase, frames: List<ScoredFrame>, threshold: Double): Boolean {
        val start = requireNotNull(case.wordStartMs)
        val end = requireNotNull(case.wordEndMs)
        return frames.any { it.score >= threshold && it.timeMs in (start - 300)..(end + 500) }
    }

    private fun countEvents(frames: List<ScoredFrame>, threshold: Double): Int {
        var events = 0
        var lastAbove = Long.MIN_VALUE
        for (frame in frames) {
            if (frame.score < threshold) continue
            if (lastAbove == Long.MIN_VALUE || frame.timeMs - lastAbove > 400) events++
            lastAbove = frame.timeMs
        }
        return events
    }

    private fun trace(case: WakeWordCase, templates: List<SpectralTemplate>): List<ScoredFrame> {
        val frames = ArrayList<ScoredFrame>()
        val detector = SpectralTemplateWakeWordDetector(
            templates = templates,
            threshold = 1.0,
            onScore = { score, atMillis -> frames += ScoredFrame(atMillis, score) }
        )
        stream(detector, case.pcm)
        return frames
    }

    private fun stream(detector: WakeWordDetector, pcm: ShortArray) {
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

    private fun fmt(value: Double): String = String.format(Locale.ROOT, "%.3f", value)
    private fun pct(value: Double): String = String.format(Locale.ROOT, "%5.1f%%", value * 100.0)

    private companion object {
        val POSITIVES: List<WakeWordCase> by lazy { WakeWordCorpus.fieldPositives() }
        val NEGATIVES: List<WakeWordCase> by lazy { WakeWordCorpus.fieldHardNegatives() }
        val CONVERSATIONS: List<WakeWordCase> by lazy { WakeWordCorpus.fieldConversations() }

        /** Plantillas de sala: seco + cada sala + cada sala con esfuerzo vocal. */
        val AUGMENTED_TEMPLATES: List<SpectralTemplate> by lazy {
            buildList {
                for (speaker in WakeWordCorpus.ENROLLED) {
                    val dry = WakeWordCorpus.enrollmentClip(speaker)
                    val bounds = WakeWordCorpus.enrollmentWordBounds(speaker)
                    add(SpectralTemplateBuilder.fromPcm("${speaker.name}-dry", dry, bounds))

                    for (room in Rooms.ALL) {
                        val wet = RoomAcoustics.reverberate(
                            dry,
                            room,
                            Random(Seeding.of("augmented", room.id, speaker.name))
                        )
                        add(SpectralTemplateBuilder.fromPcm("${speaker.name}-${room.id}", wet, bounds))
                    }

                    val effortSpeaker = LombardEffect.adapt(speaker)
                    val effort = LombardEffect.applyEffort(
                        FormantSynth.renderWord(
                            SimulatedWords.FIFO,
                            effortSpeaker,
                            Random(Seeding.of("augmented-effort", speaker.name))
                        )
                    )
                    val effortBounds = FormantSynth.wordBounds(SimulatedWords.FIFO, effortSpeaker)
                    add(SpectralTemplateBuilder.fromPcm("${speaker.name}-effort", effort, effortBounds))

                    for (room in Rooms.ALL) {
                        val wet = RoomAcoustics.reverberate(
                            effort,
                            room,
                            Random(Seeding.of("augmented-effort-room", room.id, speaker.name))
                        )
                        add(SpectralTemplateBuilder.fromPcm("${speaker.name}-effort-${room.id}", wet, effortBounds))
                    }
                }
            }
        }

        /**
         * Plantillas de condición: se sintetiza "fifo" con la misma sala, ruido,
         * SNR y esfuerzo que el caso de uso, pero con otra semilla y otra
         * posición. No es la grabación de evaluación.
         */
        val CONDITION_TEMPLATES: List<SpectralTemplate> by lazy {
            buildList {
                for (speaker in WakeWordCorpus.ENROLLED) {
                    for (condition in WakeWordCorpus.FIELD_CONDITIONS) {
                        val id = Seeding.of("condition-enroll", speaker.name, condition.id)
                        val rng = Random(id)
                        val rendered = if (condition.lombard) LombardEffect.adapt(speaker) else speaker

                        var signal = FormantSynth.renderWord(SimulatedWords.FIFO, rendered, rng)
                        if (condition.lombard) signal = LombardEffect.applyEffort(signal)
                        condition.room?.let { room ->
                            signal = RoomAcoustics.reverberate(signal, room, Random(id xor 0x5bd1e995))
                        }
                        val pcm = condition.snrDb?.let { snr ->
                            val noise = SimulatedNoise.generate(
                                condition.noise,
                                signal.size,
                                Random(id xor 0x27d4eb2d)
                            )
                            SimulatedNoise.mixAtSnr(signal, noise, snr)
                        } ?: signal

                        val bounds = FormantSynth.wordBounds(SimulatedWords.FIFO, rendered)
                        add(
                            SpectralTemplateBuilder.fromPcm(
                                "${speaker.name}-${condition.id}",
                                pcm,
                                bounds
                            )
                        )
                    }
                }
            }
        }
    }
}
