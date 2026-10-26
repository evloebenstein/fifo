package com.fifo.voicepipeline.pipeline.session.wakeword

import kotlin.random.Random

/**
 * Corpus sintético del arnés de invocación (ADR-0044 y ADR-0045).
 *
 * Tres familias de estímulo, cada una con un propósito distinto:
 *
 * - **Positivos**: "fifo" dicho por seis locutores simulados (tres "enrollados"
 *   y tres que el detector nunca vio) en cinco niveles de SNR y tres tipos de
 *   ruido. Miden FRR y latencia.
 * - **Negativos difíciles**: palabras que comparten fonemas con "fifo" (`fino`,
 *   `hijo`, `vivo`, `fifa`, `fito`, `pito`, `fido`, `cinco`). Miden si el
 *   detector distingue una palabra de su vecina.
 * - **Conversación**: habla continua sin la invocación. Mide falsos aceptos por
 *   hora, que es la métrica que de verdad sufre el usuario.
 *
 * El corpus base es de campo cercano y sin reverberación: mide el techo del
 * enfoque por plantilla. El **corpus de campo** (`field*`) añade sala,
 * esfuerzo vocal (Lombard) y locutores derivados por VTLP, que es lo que se
 * acerca al uso real (ADR-0045).
 *
 * Todo es determinista: las semillas se derivan del identificador del caso, así
 * que dos ejecuciones producen el mismo audio y las mismas métricas.
 */
internal data class CorpusCondition(
    val id: String,
    val snrDb: Int?,
    val noise: NoiseKind,
    /** Sala por la que pasa la voz antes del ruido. `null` = campo cercano. */
    val room: RoomProfile? = null,
    /** Si la persona habla con esfuerzo (efecto Lombard). */
    val lombard: Boolean = false
) {
    val label: String
        get() = buildString {
            append(room?.id ?: "dry")
            append('-')
            append(if (snrDb == null) "clean" else "${noise.name.lowercase()}-${snrDb}db")
            if (lombard) append("-lombard")
        }
}

internal data class WakeWordCase(
    val id: String,
    val speaker: SimulatedSpeaker,
    val enrolled: Boolean,
    val condition: CorpusCondition,
    val pcm: ShortArray,
    /** Señal antes de mezclar ruido: sirve para comprobar que la mezcla se aplicó. */
    val preNoisePcm: ShortArray,
    val wordStartSample: Int?,
    val wordEndSample: Int?
) {
    val durationMs: Long
        get() = pcm.size * 1000L / SYNTH_SAMPLE_RATE

    val wordStartMs: Long?
        get() = wordStartSample?.let { it * 1000L / SYNTH_SAMPLE_RATE }

    val wordEndMs: Long?
        get() = wordEndSample?.let { it * 1000L / SYNTH_SAMPLE_RATE }
}

internal object WakeWordCorpus {

    val ENROLLED: List<SimulatedSpeaker> = listOf(
        SimulatedSpeaker(name = "enrolled-a", f0Hz = 112.0, formantScale = 1.08, rate = 0.95),
        SimulatedSpeaker(name = "enrolled-b", f0Hz = 168.0, formantScale = 0.93, rate = 1.06),
        SimulatedSpeaker(name = "enrolled-c", f0Hz = 214.0, formantScale = 1.01, rate = 1.12)
    )

    /** Locutores que el detector nunca escuchó: miden generalización, no memoria. */
    val UNSEEN: List<SimulatedSpeaker> = listOf(
        SimulatedSpeaker(name = "unseen-a", f0Hz = 98.0, formantScale = 1.13, rate = 0.88),
        SimulatedSpeaker(name = "unseen-b", f0Hz = 145.0, formantScale = 0.90, rate = 1.00),
        SimulatedSpeaker(name = "unseen-c", f0Hz = 236.0, formantScale = 1.05, rate = 1.16)
    )

    val EVALUATED: List<SimulatedSpeaker> = ENROLLED + UNSEEN

    /**
     * Locutores derivados por VTLP: un tracto corto (niño) y uno largo (voz
     * grande). Nunca se enrollan, así que miden generalización a voces que el
     * detector no ha oído jamás.
     */
    val VARIED: List<SimulatedSpeaker> = listOf(
        SpeakerVariation.vtlp(ENROLLED[1], alpha = 1.20, name = "vtlp-child"),
        SpeakerVariation.vtlp(UNSEEN[0], alpha = 0.86, name = "vtlp-large")
    )

    /** Locutores del arnés de campo: los seis base más los derivados. */
    val FIELD_SPEAKERS: List<SimulatedSpeaker> = EVALUATED + VARIED

    /** Condición de enrollamiento: voz limpia, sin ruido. */
    val ENROLLMENT_CONDITION = CorpusCondition(id = "clean", snrDb = null, noise = NoiseKind.WHITE)

    private val POSITIVE_SNRS = listOf(20, 10, 5, 0)
    private val NOISE_KINDS = listOf(NoiseKind.WHITE, NoiseKind.PINK, NoiseKind.BABBLE)

    /** Condiciones de los positivos: limpio y luego cada SNR con cada ruido. */
    val POSITIVE_CONDITIONS: List<CorpusCondition> = buildList {
        add(ENROLLMENT_CONDITION)
        for (snr in POSITIVE_SNRS) {
            for (kind in NOISE_KINDS) {
                add(CorpusCondition(id = "${kind.name.lowercase()}-$snr", snrDb = snr, noise = kind))
            }
        }
    }

    /** Negativos difíciles: limpio, 10 dB rosa y 5 dB blanco. */
    val NEGATIVE_CONDITIONS: List<CorpusCondition> = listOf(
        ENROLLMENT_CONDITION,
        CorpusCondition(id = "pink-10", snrDb = 10, noise = NoiseKind.PINK),
        CorpusCondition(id = "white-5", snrDb = 5, noise = NoiseKind.WHITE)
    )

    /**
     * Conversación: limpio, 10 dB rosa, 5 dB blanco y 0 dB blanco.
     *
     * Se excluye el balbuceo aquí a propósito: sintetizar veinte segundos de
     * cinco voces por caso multiplicaría el tiempo de la prueba sin cambiar la
     * conclusión (el balbuceo ya está en positivos y negativos).
     */
    val CONVERSATION_CONDITIONS: List<CorpusCondition> = listOf(
        ENROLLMENT_CONDITION,
        CorpusCondition(id = "pink-10", snrDb = 10, noise = NoiseKind.PINK),
        CorpusCondition(id = "white-5", snrDb = 5, noise = NoiseKind.WHITE),
        CorpusCondition(id = "white-0", snrDb = 0, noise = NoiseKind.WHITE)
    )

    /**
     * Condiciones de campo: tres escalones de dificultad creciente.
     *
     * 1. Despacho pequeño y silencio: solo reverberación.
     * 2. Salón con nevera o televisión baja (rosa a 20 dB) y esfuerzo vocal.
     * 3. Cocina con gente (balbuceo a 10 dB) y esfuerzo vocal.
     *
     * Los T60 son deliberadamente conservadores (0,25–0,50 s): un salón amueblado
     * está en ese orden, no en el de una catedral.
     */
    val FIELD_CONDITIONS: List<CorpusCondition> = listOf(
        CorpusCondition(
            id = "office-clean",
            snrDb = null,
            noise = NoiseKind.WHITE,
            room = Rooms.OFFICE
        ),
        CorpusCondition(
            id = "living-pink-20",
            snrDb = 20,
            noise = NoiseKind.PINK,
            room = Rooms.LIVING_ROOM,
            lombard = true
        ),
        CorpusCondition(
            id = "kitchen-babble-10",
            snrDb = 10,
            noise = NoiseKind.BABBLE,
            room = Rooms.KITCHEN,
            lombard = true
        )
    )

    /** Condiciones de campo con ruido: negativos y conversación. */
    val FIELD_NOISY_CONDITIONS: List<CorpusCondition> =
        FIELD_CONDITIONS.filter { it.snrDb != null }

    /** ~15 s de habla continua por clip. */
    private const val CONVERSATION_SYLLABLES = 90

    fun positives(): List<WakeWordCase> = buildList {
        for (condition in POSITIVE_CONDITIONS) {
            for (speaker in EVALUATED) {
                add(
                    buildCase(
                        id = "pos-${speaker.name}-${condition.id}",
                        speaker = speaker,
                        condition = condition,
                        phonemes = SimulatedWords.FIFO,
                        saysWakeWord = true,
                        seed = Seeding.of("pos", speaker.name, condition.id)
                    )
                )
            }
        }
    }

    fun hardNegatives(): List<WakeWordCase> = buildList {
        for (condition in NEGATIVE_CONDITIONS) {
            for (speaker in EVALUATED) {
                for ((word, phonemes) in SimulatedWords.HARD_NEGATIVES) {
                    add(
                        buildCase(
                            id = "neg-$word-${speaker.name}-${condition.id}",
                            speaker = speaker,
                            condition = condition,
                            phonemes = phonemes,
                            saysWakeWord = false,
                            seed = Seeding.of("neg", word, speaker.name, condition.id)
                        )
                    )
                }
            }
        }
    }

    fun conversations(): List<WakeWordCase> = buildList {
        for (condition in CONVERSATION_CONDITIONS) {
            for (speaker in EVALUATED) {
                val seed = Seeding.of("talk", speaker.name, condition.id)
                add(
                    buildCase(
                        id = "talk-${speaker.name}-${condition.id}",
                        speaker = speaker,
                        condition = condition,
                        phonemes = SimulatedWords.sentence(Random(seed), CONVERSATION_SYLLABLES),
                        saysWakeWord = false,
                        seed = seed,
                        leadMs = 300,
                        tailMs = 300
                    )
                )
            }
        }
    }

    // ── Corpus de campo (ADR-0045) ─────────────────────────────────────────

    fun fieldPositives(): List<WakeWordCase> = buildList {
        for (condition in FIELD_CONDITIONS) {
            for (speaker in FIELD_SPEAKERS) {
                add(
                    buildCase(
                        id = "field-pos-${speaker.name}-${condition.id}",
                        speaker = speaker,
                        condition = condition,
                        phonemes = SimulatedWords.FIFO,
                        saysWakeWord = true,
                        seed = Seeding.of("field-pos", speaker.name, condition.id)
                    )
                )
            }
        }
    }

    fun fieldHardNegatives(): List<WakeWordCase> = buildList {
        for (condition in FIELD_NOISY_CONDITIONS) {
            for (speaker in FIELD_SPEAKERS) {
                for ((word, phonemes) in SimulatedWords.HARD_NEGATIVES) {
                    add(
                        buildCase(
                            id = "field-neg-$word-${speaker.name}-${condition.id}",
                            speaker = speaker,
                            condition = condition,
                            phonemes = phonemes,
                            saysWakeWord = false,
                            seed = Seeding.of("field-neg", word, speaker.name, condition.id)
                        )
                    )
                }
            }
        }
    }

    fun fieldConversations(): List<WakeWordCase> = buildList {
        for (condition in FIELD_NOISY_CONDITIONS) {
            for (speaker in FIELD_SPEAKERS) {
                val seed = Seeding.of("field-talk", speaker.name, condition.id)
                add(
                    buildCase(
                        id = "field-talk-${speaker.name}-${condition.id}",
                        speaker = speaker,
                        condition = condition,
                        phonemes = SimulatedWords.sentence(Random(seed), CONVERSATION_SYLLABLES),
                        saysWakeWord = false,
                        seed = seed,
                        leadMs = 300,
                        tailMs = 300
                    )
                )
            }
        }
    }

    /**
     * Rendición de "fifo" limpia para construir la plantilla del detector de
     * referencia. Usa una semilla distinta de la de los positivos: la plantilla
     * no puede ser literalmente el mismo audio que se va a evaluar.
     *
     * No lleva sala ni esfuerzo vocal: el enrolamiento es de campo cercano.
     */
    fun enrollmentClip(speaker: SimulatedSpeaker): ShortArray {
        val seed = Seeding.of("enroll", speaker.name)
        return FormantSynth.renderWord(SimulatedWords.FIFO, speaker, Random(seed))
    }

    fun enrollmentClipId(speaker: SimulatedSpeaker): String = "enroll-${speaker.name}"

    fun enrollmentWordBounds(speaker: SimulatedSpeaker): IntRange =
        FormantSynth.wordBounds(SimulatedWords.FIFO, speaker)

    private fun buildCase(
        id: String,
        speaker: SimulatedSpeaker,
        condition: CorpusCondition,
        phonemes: List<Phoneme>,
        saysWakeWord: Boolean,
        seed: Long,
        leadMs: Int = 250,
        tailMs: Int = 350
    ): WakeWordCase {
        val enrolled = speaker in ENROLLED
        val rendered = if (condition.lombard) LombardEffect.adapt(speaker) else speaker

        var signal = FormantSynth.renderWord(phonemes, rendered, Random(seed), leadMs, tailMs)
        if (condition.lombard) signal = LombardEffect.applyEffort(signal)
        condition.room?.let { room ->
            signal = RoomAcoustics.reverberate(signal, room, Random(Seeding.of("room", room.id)))
        }

        val preNoise = signal
        val pcm = condition.snrDb?.let { snr ->
            val noise = SimulatedNoise.generate(condition.noise, preNoise.size, Random(seed * 31 + 7))
            SimulatedNoise.mixAtSnr(preNoise, noise, snr)
        } ?: preNoise

        val bounds = if (saysWakeWord) FormantSynth.wordBounds(phonemes, rendered, leadMs) else null

        return WakeWordCase(
            id = id,
            speaker = rendered,
            enrolled = enrolled,
            condition = condition,
            pcm = pcm,
            preNoisePcm = preNoise,
            wordStartSample = bounds?.first,
            wordEndSample = bounds?.last
        )
    }
}
