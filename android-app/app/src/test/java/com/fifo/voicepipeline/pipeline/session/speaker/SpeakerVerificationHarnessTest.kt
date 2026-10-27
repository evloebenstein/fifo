package com.fifo.voicepipeline.pipeline.session.speaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Locale

/**
 * Arnés de verificación de locutor (ADR-0048).
 *
 * Mide el flujo completo —enrolamiento, embeddings, coseno y política— sobre el
 * corpus sintético de [SpeakerVerificationCorpus]. No hay binarios, descargas ni
 * modelo de producción: el extractor medido es [SpeakerEmbeddingReference], una
 * referencia simple que solo demuestra que el arnés separa voces.
 *
 * **Los números no certifican campo.** La voz sintética por formantes es más
 * fácil que una persona con televisión, micrófono de móvil y esfuerzo vocal. La
 * tabla sirve para detectar regresiones y para decidir cuándo hace falta un
 * modelo entrenado (ReDimNet/ECAPA/Eagle), no para afirmar un EER real.
 */
class SpeakerVerificationHarnessTest {

    @Test
    fun `el corpus y el extractor de referencia son deterministas`() {
        val tests = SpeakerVerificationCorpus.tests()
        assertTrue("El corpus debe tener pruebas de varios locutores", tests.size >= 32)

        val first = SpeakerVerificationCorpus.tests().first()
        val repeated = SpeakerVerificationCorpus.tests().first()
        assertTrue("Misma semilla, mismo PCM", first.pcm.contentEquals(repeated.pcm))
        assertTrue(
            "El mismo PCM debe producir el mismo embedding",
            SpeakerEmbeddingReference.embed(first.pcm)
                .contentEquals(SpeakerEmbeddingReference.embed(repeated.pcm))
        )
        assertEquals(first.durationMillis, repeated.durationMillis)
    }

    @Test
    fun `la referencia separa una voz propia de una ajena en limpio`() {
        val enrollment = SpeakerVerificationCorpus.enrollment()
        val tests = SpeakerVerificationCorpus.tests()
            .filter { it.condition.isClean && it.syllables == 9 }

        val genuineSpeaker = SpeakerVerificationCorpus.ENROLLED.first().name
        val otherSpeaker = SpeakerVerificationCorpus.ENROLLED.last().name
        val genuine = tests.first { it.speaker.name == genuineSpeaker }
        val impostor = tests.first { it.speaker.name == otherSpeaker }

        val centroid = SpeakerEmbeddingReference.centroid(
            enrollment.getValue(genuineSpeaker).map { SpeakerEmbeddingReference.embed(it.pcm) }
        )
        val genuineScore = com.fifo.voicepipeline.pipeline.session.cosineSimilarity(
            SpeakerEmbeddingReference.embed(genuine.pcm),
            centroid
        )
        val impostorScore = com.fifo.voicepipeline.pipeline.session.cosineSimilarity(
            SpeakerEmbeddingReference.embed(impostor.pcm),
            centroid
        )

        assertTrue(
            "La voz propia debe puntuar más alto que la ajena ($genuineScore vs $impostorScore)",
            genuineScore > impostorScore
        )
    }

    @Test
    fun `el EER del simulador y la tabla por condicion`() {
        assumeTrue(
            "experimento de locutor; ejecuta con FIFO_SPEAKER_EXPERIMENT=1",
            System.getenv("FIFO_SPEAKER_EXPERIMENT") == "1"
        )

        val enrollment = SpeakerVerificationCorpus.enrollment()
        val tests = SpeakerVerificationCorpus.tests()
        val trials = SpeakerVerificationEvaluator.scoreTrials(enrollment, tests)

        // El umbral se calibra con el punto de EER del propio simulador. No es
        // un umbral de producción: cada modelo real trae otra distribución de
        // similitudes y debe fijarse con su validación (ADR-0048).
        val cleanTrials = trials.filter { it.conditionId == "clean" }
        val cleanReference = SpeakerVerificationEvaluator.metrics(cleanTrials, 0.0)
        val policyThreshold = cleanReference.eerThreshold
        val global = SpeakerVerificationEvaluator.metrics(trials, policyThreshold)

        println("=== Verificación de locutor sintética (ADR-0048) ===")
        println("trials=${global.trials} genuine=${global.genuineTrials} impostor=${global.impostorTrials}")
        println(
            String.format(
                Locale.US,
                "global EER=%.1f%% (thr=%.3f) FRR@%.3f=%.1f%% FAR@%.3f=%.1f%%",
                global.eer * 100,
                global.eerThreshold,
                policyThreshold,
                global.frrAtPolicy * 100,
                policyThreshold,
                global.farAtPolicy * 100
            )
        )

        println("cond                    trials  EER      FRR@thr   FAR@thr")
        for (condition in SpeakerVerificationCorpus.CONDITIONS) {
            val subset = trials.filter { it.conditionId == condition.id }
            val metrics = SpeakerVerificationEvaluator.metrics(subset, policyThreshold)
            println(
                String.format(
                    Locale.US,
                    "%-23s %6d  %6.1f%%  %7.1f%%  %7.1f%%",
                    condition.id,
                    metrics.trials,
                    metrics.eer * 100,
                    metrics.frrAtPolicy * 100,
                    metrics.farAtPolicy * 100
                )
            )
        }

        println("duración                trials  EER      FRR@thr   FAR@thr")
        for (syllables in SpeakerVerificationCorpus.TEST_SYLLABLES) {
            val subset = trials.filter { it.syllables == syllables }
            val metrics = SpeakerVerificationEvaluator.metrics(subset, policyThreshold)
            val durationMs = tests.first { it.syllables == syllables }.durationMillis
            println(
                String.format(
                    Locale.US,
                    "%-23s %6d  %6.1f%%  %7.1f%%  %7.1f%%",
                    "sílabas=$syllables (~${durationMs}ms)",
                    metrics.trials,
                    metrics.eer * 100,
                    metrics.frrAtPolicy * 100,
                    metrics.farAtPolicy * 100
                )
            )
        }

        val unseenNames = SpeakerVerificationCorpus.UNSEEN.map { it.name }.toSet()
        val unseenTrials = trials.filter { it.speakerName in unseenNames }
        val unseen = SpeakerVerificationEvaluator.metrics(unseenTrials, policyThreshold)
        println(
            String.format(
                Locale.US,
                "locutores no enrolados: trials=%d FAR@%.3f=%.1f%%",
                unseen.trials,
                policyThreshold,
                unseen.farAtPolicy * 100
            )
        )

        val clean = SpeakerVerificationEvaluator.metrics(cleanTrials, policyThreshold)
        assertTrue(
            "La referencia debe separar voces sintéticas limpias: EER=${clean.eer}",
            clean.eer < 0.10
        )
        assertTrue(
            "Con el umbral calibrado, el FRR limpio no debe superar 10 %: ${clean.frrAtPolicy}",
            clean.frrAtPolicy < 0.10
        )
        assertTrue(
            "Con el umbral calibrado, el FAR limpio no debe superar 10 %: ${clean.farAtPolicy}",
            clean.farAtPolicy < 0.10
        )
    }
}
