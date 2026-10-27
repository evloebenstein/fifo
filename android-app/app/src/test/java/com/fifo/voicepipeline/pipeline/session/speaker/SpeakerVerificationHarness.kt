package com.fifo.voicepipeline.pipeline.session.speaker

import com.fifo.voicepipeline.pipeline.session.cosineSimilarity
import com.fifo.voicepipeline.pipeline.session.l2Normalise
import com.fifo.voicepipeline.pipeline.session.wakeword.FormantSynth
import com.fifo.voicepipeline.pipeline.session.wakeword.LombardEffect
import com.fifo.voicepipeline.pipeline.session.wakeword.NoiseKind
import com.fifo.voicepipeline.pipeline.session.wakeword.RoomAcoustics
import com.fifo.voicepipeline.pipeline.session.wakeword.RoomProfile
import com.fifo.voicepipeline.pipeline.session.wakeword.Rooms
import com.fifo.voicepipeline.pipeline.session.wakeword.Seeding
import com.fifo.voicepipeline.pipeline.session.wakeword.SimulatedNoise
import com.fifo.voicepipeline.pipeline.session.wakeword.SimulatedSpeaker
import com.fifo.voicepipeline.pipeline.session.wakeword.SimulatedWords
import com.fifo.voicepipeline.pipeline.session.wakeword.SpectralFrontend
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

private const val SPEAKER_SYNTH_SAMPLE_RATE = 16_000

/**
 * Corpus sintético de verificación de locutor (ADR-0048).
 *
 * No es un corpus de campo ni entrena un modelo: es el mismo tipo de banco de
 * pruebas determinista que ADR-0044/0045, aplicado a "¿esta voz es la misma
 * persona?". Sirve para fijar la costura, comparar extractores y comprobar que
 * la política no se rompe; sus números **no certifican** EER real con
 * micrófonos, televisión ni voces grabadas.
 *
 * Cada locutor tiene tres frases de enrolamiento y dos frases de prueba por
 * condición. Las frases de prueba nunca coinciden con las de enrolamiento: se
 * mide generalización a texto distinto, no memoria de una grabación.
 */
internal data class SpeakerCondition(
    val id: String,
    val snrDb: Int?,
    val noise: NoiseKind,
    val room: RoomProfile? = null,
    val lombard: Boolean = false
) {
    val isClean: Boolean
        get() = snrDb == null && room == null && !lombard
}

internal data class SpeakerUtterance(
    val id: String,
    val speaker: SimulatedSpeaker,
    val condition: SpeakerCondition,
    val syllables: Int,
    val pcm: ShortArray
) {
    val durationMillis: Long
        get() = pcm.size * 1000L / SPEAKER_SYNTH_SAMPLE_RATE
}

internal object SpeakerVerificationCorpus {

    /**
     * Ocho tractos distintos. Los dos últimos no se enrolan: si el sistema solo
     * acierta con voces que ya vio, no generaliza.
     */
    val SPEAKERS: List<SimulatedSpeaker> = listOf(
        SimulatedSpeaker("spk-a", f0Hz = 104.0, formantScale = 1.13, rate = 0.94),
        SimulatedSpeaker("spk-b", f0Hz = 124.0, formantScale = 1.05, rate = 1.02),
        SimulatedSpeaker("spk-c", f0Hz = 148.0, formantScale = 0.98, rate = 1.08),
        SimulatedSpeaker("spk-d", f0Hz = 172.0, formantScale = 0.91, rate = 0.99),
        SimulatedSpeaker("spk-e", f0Hz = 198.0, formantScale = 1.08, rate = 1.04),
        SimulatedSpeaker("spk-f", f0Hz = 228.0, formantScale = 0.89, rate = 1.12),
        SimulatedSpeaker("spk-g", f0Hz = 114.0, formantScale = 0.95, rate = 0.90),
        SimulatedSpeaker("spk-h", f0Hz = 188.0, formantScale = 1.01, rate = 1.00)
    )

    val ENROLLED: List<SimulatedSpeaker> = SPEAKERS.take(6)
    val UNSEEN: List<SimulatedSpeaker> = SPEAKERS.drop(6)

    val CONDITIONS: List<SpeakerCondition> = listOf(
        SpeakerCondition("clean", snrDb = null, noise = NoiseKind.WHITE),
        SpeakerCondition("pink-10", snrDb = 10, noise = NoiseKind.PINK),
        SpeakerCondition("white-5", snrDb = 5, noise = NoiseKind.WHITE),
        SpeakerCondition(
            "living-lombard-pink-20",
            snrDb = 20,
            noise = NoiseKind.PINK,
            room = Rooms.LIVING_ROOM,
            lombard = true
        )
    )

    /** Duraciones de prueba: una frase corta y una larga. */
    val TEST_SYLLABLES: List<Int> = listOf(4, 9)

    private const val ENROLLMENT_SYLLABLES = 9

    fun enrollment(): Map<String, List<SpeakerUtterance>> = ENROLLED.associate { speaker ->
        speaker.name to (0 until 3).map { index ->
            render(
                id = "enroll-${speaker.name}-$index",
                speaker = speaker,
                condition = SpeakerCondition("clean", null, NoiseKind.WHITE),
                syllables = ENROLLMENT_SYLLABLES,
                seed = Seeding.of("speaker-enroll", speaker.name, index.toString())
            )
        }
    }

    fun tests(): List<SpeakerUtterance> = buildList {
        for (speaker in SPEAKERS) {
            for (condition in CONDITIONS) {
                for ((index, syllables) in TEST_SYLLABLES.withIndex()) {
                    add(
                        render(
                            id = "test-${speaker.name}-${condition.id}-$index",
                            speaker = speaker,
                            condition = condition,
                            syllables = syllables,
                            seed = Seeding.of("speaker-test", speaker.name, condition.id, index.toString())
                        )
                    )
                }
            }
        }
    }

    private fun render(
        id: String,
        speaker: SimulatedSpeaker,
        condition: SpeakerCondition,
        syllables: Int,
        seed: Long
    ): SpeakerUtterance {
        val rendered = if (condition.lombard) LombardEffect.adapt(speaker) else speaker
        var signal = FormantSynth.renderWord(
            phonemes = SimulatedWords.sentence(Random(seed), syllables),
            speaker = rendered,
            rng = Random(seed),
            leadMs = 150,
            tailMs = 250
        )
        if (condition.lombard) signal = LombardEffect.applyEffort(signal)
        condition.room?.let { room ->
            signal = RoomAcoustics.reverberate(signal, room, Random(Seeding.of("speaker-room", room.id)))
        }
        condition.snrDb?.let { snr ->
            val noise = SimulatedNoise.generate(
                kind = condition.noise,
                samples = signal.size,
                rng = Random(seed * 31 + 11)
            )
            signal = SimulatedNoise.mixAtSnr(signal, noise, snr)
        }
        return SpeakerUtterance(
            id = id,
            speaker = rendered,
            condition = condition,
            syllables = syllables,
            pcm = signal
        )
    }
}

/**
 * Extractor de referencia, no de producción.
 *
 * Reutiliza el frente espectral del arnés de wake word (16 bandas logarítmicas
 * por trama, 32 ms / 10 ms) y resume la frase como media + desviación por banda.
 * Es deliberadamente simple: sirve para medir el flujo y la política, no para
 * competir con ReDimNet/ECAPA. Si ni siquiera separa voces sintéticas, el arnés
 * no sirve; si las separa demasiado bien, tampoco: recuerda que la voz sintética
 * es más fácil que una persona real.
 */
internal object SpeakerEmbeddingReference {

    private const val SILENCE_GATE_NATS = -12.0

    fun embed(pcm: ShortArray): FloatArray {
        val frontend = SpectralFrontend()
        val frames = ArrayList<FloatArray>(pcm.size / SpectralFrontend.HOP_SIZE)
        for (sample in pcm) {
            frontend.push(sample / 32768f)
            val feature = frontend.pollFrame() ?: continue
            if (feature.energy > SILENCE_GATE_NATS) frames += feature.bands
        }
        if (frames.isEmpty()) return FloatArray(0)

        val bands = SpectralFrontend.BANDS
        val mean = FloatArray(bands)
        val squares = FloatArray(bands)
        for (frame in frames) {
            for (band in 0 until bands) {
                mean[band] += frame[band]
                squares[band] += frame[band] * frame[band]
            }
        }
        val count = frames.size.toFloat()
        for (band in 0 until bands) {
            mean[band] /= count
            val variance = (squares[band] / count - mean[band] * mean[band]).coerceAtLeast(0f)
            squares[band] = sqrt(variance.toDouble()).toFloat()
        }

        val vector = FloatArray(bands * 2)
        mean.copyInto(vector, 0)
        squares.copyInto(vector, bands)
        return l2Normalise(vector)
    }

    fun centroid(embeddings: List<FloatArray>): FloatArray {
        val usable = embeddings.filter { it.isNotEmpty() }
        if (usable.isEmpty()) return FloatArray(0)
        val dimension = usable.first().size
        if (usable.any { it.size != dimension }) return FloatArray(0)
        return l2Normalise(
            FloatArray(dimension) { index ->
                (usable.sumOf { it[index].toDouble() } / usable.size).toFloat()
            }
        )
    }
}

internal data class SpeakerTrialScore(
    val speakerName: String,
    val enrolled: Boolean,
    val conditionId: String,
    val syllables: Int,
    val genuine: Boolean,
    val score: Double
)

internal data class SpeakerMetrics(
    val trials: Int,
    val genuineTrials: Int,
    val impostorTrials: Int,
    val eer: Double,
    val eerThreshold: Double,
    val frrAtPolicy: Double,
    val farAtPolicy: Double
)

internal object SpeakerVerificationEvaluator {

    fun scoreTrials(
        enrollment: Map<String, List<SpeakerUtterance>>,
        tests: List<SpeakerUtterance>
    ): List<SpeakerTrialScore> {
        val centroids = enrollment.mapValues { (_, utterances) ->
            SpeakerEmbeddingReference.centroid(utterances.map { SpeakerEmbeddingReference.embed(it.pcm) })
        }

        return buildList {
            for (test in tests) {
                val query = SpeakerEmbeddingReference.embed(test.pcm)
                if (query.isEmpty()) continue
                val enrolledNames = centroids.filterValues { it.isNotEmpty() }.keys
                for (profileId in enrolledNames) {
                    val reference = centroids.getValue(profileId)
                    if (reference.size != query.size) continue
                    add(
                        SpeakerTrialScore(
                            speakerName = test.speaker.name,
                            enrolled = test.speaker.name in enrolledNames,
                            conditionId = test.condition.id,
                            syllables = test.syllables,
                            genuine = profileId == test.speaker.name,
                            score = cosineSimilarity(query, reference).coerceIn(0.0, 1.0)
                        )
                    )
                }
            }
        }
    }

    fun metrics(trials: List<SpeakerTrialScore>, policyThreshold: Double): SpeakerMetrics {
        val genuine = trials.filter { it.genuine }.map { it.score }
        val impostor = trials.filterNot { it.genuine }.map { it.score }
        if (genuine.isEmpty() || impostor.isEmpty()) {
            return SpeakerMetrics(trials.size, genuine.size, impostor.size, 1.0, 1.0, 1.0, 1.0)
        }

        var bestThreshold = 0.0
        var bestEer = 1.0
        var bestGap = Double.MAX_VALUE
        for (step in 0..1000) {
            val threshold = step / 1000.0
            val frr = genuine.count { it < threshold }.toDouble() / genuine.size
            val far = impostor.count { it >= threshold }.toDouble() / impostor.size
            val gap = abs(frr - far)
            if (gap < bestGap) {
                bestGap = gap
                bestThreshold = threshold
                bestEer = (frr + far) / 2.0
            }
        }

        val frrAtPolicy = genuine.count { it < policyThreshold }.toDouble() / genuine.size
        val farAtPolicy = impostor.count { it >= policyThreshold }.toDouble() / impostor.size
        return SpeakerMetrics(
            trials = trials.size,
            genuineTrials = genuine.size,
            impostorTrials = impostor.size,
            eer = bestEer,
            eerThreshold = bestThreshold,
            frrAtPolicy = frrAtPolicy,
            farAtPolicy = farAtPolicy
        )
    }
}
