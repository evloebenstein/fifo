package com.fifo.voicepipeline.pipeline.session.wakeword

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Corpus de entrenamiento del clasificador (ADR-0046).
 *
 * No reutiliza los casos de evaluación: los locutores, las semillas y las
 * posiciones de sala son distintos. Si el modelo viera el mismo audio que se
 * usa para medirlo, el número no significaría nada.
 *
 * La aumentación es la misma que ya vive en el arnés de campo (ADR-0045): salas
 * con T60 de 0,25 a 0,50 s, efecto Lombard, tractos cortos y largos, y ruido
 * blanco, rosa y balbuceo. La única diferencia es que aquí cada clip sortea una
 * posición nueva dentro de la sala, de modo que el modelo ve muchas respuestas
 * al impulso y no tres.
 */
internal data class TrainingClip(
    val id: String,
    val pcm: ShortArray,
    val wordStartSample: Int?,
    val wordEndSample: Int?,
    val positive: Boolean
)

internal object WakeWordTrainingCorpus {

    /**
     * Doce locutores de entrenamiento. Los tractos se sortean en un rango más
     * ancho que el de evaluación para cubrir niños y voces grandes sin depender
     * de un VTLP explícito.
     */
    val TRAIN_SPEAKERS: List<SimulatedSpeaker> = speakers("train", 40, baseSeed = 0x51A7_0001L)

    /** Ocho locutores que el modelo nunca ve: validan el umbral. */
    val VALIDATION_SPEAKERS: List<SimulatedSpeaker> = speakers("validation", 8, baseSeed = 0x51A7_0002L)

    private val DRY_CLEAN = CorpusCondition(id = "train-dry-clean", snrDb = null, noise = NoiseKind.WHITE)

    /**
     * Nueve condiciones. El modelo tiene que aprender la palabra, no el canal:
     * hay salas, ruidos, SNRs y esfuerzo vocal mezclados a propósito.
     */
    val TRAIN_CONDITIONS: List<CorpusCondition> = listOf(
        DRY_CLEAN,
        CorpusCondition(id = "train-dry-pink-20", snrDb = 20, noise = NoiseKind.PINK),
        CorpusCondition(id = "train-dry-pink-10", snrDb = 10, noise = NoiseKind.PINK),
        CorpusCondition(id = "train-dry-white-10", snrDb = 10, noise = NoiseKind.WHITE),
        CorpusCondition(id = "train-dry-white-5", snrDb = 5, noise = NoiseKind.WHITE),
        CorpusCondition(id = "train-dry-babble-10", snrDb = 10, noise = NoiseKind.BABBLE),
        CorpusCondition(id = "train-office", snrDb = null, noise = NoiseKind.WHITE, room = Rooms.OFFICE),
        CorpusCondition(
            id = "train-living-pink-20",
            snrDb = 20,
            noise = NoiseKind.PINK,
            room = Rooms.LIVING_ROOM,
            lombard = true
        ),
        CorpusCondition(
            id = "train-kitchen-babble-10",
            snrDb = 10,
            noise = NoiseKind.BABBLE,
            room = Rooms.KITCHEN,
            lombard = true
        )
    )

    /** Validación: tres condiciones que no repiten las de entrenamiento. */
    val VALIDATION_CONDITIONS: List<CorpusCondition> = listOf(
        CorpusCondition(id = "validation-dry-white-15", snrDb = 15, noise = NoiseKind.WHITE),
        CorpusCondition(
            id = "validation-office-pink-15",
            snrDb = 15,
            noise = NoiseKind.PINK,
            room = Rooms.OFFICE
        ),
        CorpusCondition(
            id = "validation-kitchen-babble-15",
            snrDb = 15,
            noise = NoiseKind.BABBLE,
            room = Rooms.KITCHEN,
            lombard = true
        )
    )

    private const val CONVERSATION_SYLLABLES = 70

    fun trainingClips(): List<TrainingClip> = clips(TRAIN_SPEAKERS, TRAIN_CONDITIONS, "train")

    fun validationClips(): List<TrainingClip> = clips(VALIDATION_SPEAKERS, VALIDATION_CONDITIONS, "validation")

    private fun clips(
        speakers: List<SimulatedSpeaker>,
        conditions: List<CorpusCondition>,
        tag: String
    ): List<TrainingClip> = buildList {
        val words = SimulatedWords.HARD_NEGATIVES.entries.toList()

        for ((speakerIndex, speaker) in speakers.withIndex()) {
            for ((conditionIndex, condition) in conditions.withIndex()) {
                add(
                    render(
                        id = "$tag-pos-${speaker.name}-${condition.id}",
                        seed = Seeding.of(tag, "pos", speaker.name, condition.id),
                        speaker = speaker,
                        condition = condition,
                        phonemes = SimulatedWords.FIFO,
                        isWord = true,
                        positive = true
                    )
                )

                // Cuatro negativos por combinación, rotando las ocho palabras:
                // así todas aparecen y el conjunto no se dispara de tamaño.
                for (offset in 0 until 4) {
                    val (word, phonemes) = words[(speakerIndex * 4 + conditionIndex + offset) % words.size]
                    add(
                        render(
                            id = "$tag-neg-$word-${speaker.name}-${condition.id}",
                            seed = Seeding.of(tag, "neg", word, speaker.name, condition.id),
                            speaker = speaker,
                            condition = condition,
                            phonemes = phonemes,
                            isWord = true,
                            positive = false
                        )
                    )
                }
            }

            // Conversación en las condiciones con ruido: es el material que
            // calibra el umbral de falsos aceptos. En validación se incluye
            // también la condición con Lombard, que es la que más se le escapa
            // a un modelo entrenado sin ella.
            val conversationConditions = conditions.filter { it.snrDb != null }
                .let { if (tag == "validation") it else it.take(2) }
            for (condition in conversationConditions) {
                val seed = Seeding.of(tag, "talk", speaker.name, condition.id)
                add(
                    render(
                        id = "$tag-talk-${speaker.name}-${condition.id}",
                        seed = seed,
                        speaker = speaker,
                        condition = condition,
                        phonemes = SimulatedWords.sentence(Random(seed), CONVERSATION_SYLLABLES),
                        isWord = false,
                        positive = false,
                        leadMs = 300,
                        tailMs = 300
                    )
                )
            }
        }
    }

    private fun render(
        id: String,
        seed: Long,
        speaker: SimulatedSpeaker,
        condition: CorpusCondition,
        phonemes: List<Phoneme>,
        isWord: Boolean,
        positive: Boolean,
        leadMs: Int = 250,
        tailMs: Int = 350
    ): TrainingClip {
        val rng = Random(seed)
        val rendered = if (condition.lombard) LombardEffect.adapt(speaker) else speaker

        var signal = FormantSynth.renderWord(phonemes, rendered, rng, leadMs, tailMs)
        if (condition.lombard) signal = LombardEffect.applyEffort(signal)

        // Posición nueva por clip: el modelo no puede memorizar tres RIRs.
        condition.room?.let { room ->
            signal = RoomAcoustics.reverberate(signal, room, Random(Seeding.of(id, "room")))
        }

        val pcm = condition.snrDb?.let { snr ->
            val noise = SimulatedNoise.generate(condition.noise, signal.size, Random(seed * 31 + 7))
            SimulatedNoise.mixAtSnr(signal, noise, snr)
        } ?: signal

        val bounds = if (isWord) FormantSynth.wordBounds(phonemes, rendered, leadMs) else null
        return TrainingClip(
            id = id,
            pcm = pcm,
            wordStartSample = bounds?.first,
            wordEndSample = bounds?.last,
            positive = positive
        )
    }

    private fun speakers(prefix: String, count: Int, baseSeed: Long): List<SimulatedSpeaker> {
        val rng = Random(baseSeed)
        return (0 until count).map { index ->
            val formantScale = when {
                index % 5 == 0 -> rng.nextDouble(1.16, 1.24) // tracto corto: voz infantil
                index % 7 == 0 -> rng.nextDouble(0.82, 0.90) // tracto largo: voz grande
                else -> rng.nextDouble(0.88, 1.14)
            }
            SimulatedSpeaker(
                name = "$prefix-%02d".format(index),
                f0Hz = rng.nextDouble(90.0, 240.0),
                formantScale = formantScale,
                rate = rng.nextDouble(0.86, 1.16)
            )
        }
    }
}

/**
 * Extrae las tramas espectrales de un clip y las ventanas que ve el detector.
 *
 * El detector real evalúa una ventana deslizante que termina en cada trama. Para
 * entrenar no hace falta recorrer el audio: se conoce la etiqueta temporal, así
 * que se generan ventanas positivas (las que terminan en el final de la palabra)
 * y negativas (antes, después, a mitad y en habla continua).
 */
internal object WakeWordWindows {

    const val MIN_FRAMES = 30
    const val MAX_FRAMES = 66
    const val STEP_FRAMES = 2

    /** Duraciones que prueba el detector, en tramas de 10 ms. */
    val LENGTHS: IntArray = IntArray((MAX_FRAMES - MIN_FRAMES) / STEP_FRAMES + 1) {
        MIN_FRAMES + it * STEP_FRAMES
    }

    /** Duraciones de entrenamiento: más gruesas, para no duplicar casi lo mismo. */
    val TRAIN_LENGTHS: IntArray = IntArray((MAX_FRAMES - MIN_FRAMES) / 4 + 1) { MIN_FRAMES + it * 4 }

    /** Umbral de silencio compartido con el detector de plantilla. */
    const val SILENCE_GATE_NATS = -12.0

    fun framesOf(pcm: ShortArray): List<FrameFeature> {
        val frontend = SpectralFrontend()
        val frames = ArrayList<FrameFeature>(pcm.size / SpectralFrontend.HOP_SIZE + 1)
        for (sample in pcm) {
            frontend.push(sample / 32768f)
            frontend.pollFrame()?.let { frames += it }
        }
        return frames
    }

    /** Índice de la trama cuyo centro cae más cerca de una muestra. */
    fun frameIndexForSample(sample: Int): Int {
        val offset = (sample - SpectralFrontend.FRAME_SIZE / 2).coerceAtLeast(0)
        return (offset + SpectralFrontend.HOP_SIZE / 2) / SpectralFrontend.HOP_SIZE
    }

    /**
     * Vector de la ventana que termina en [endFrame] (incluido), o `null` si no
     * hay suficientes tramas o la última es silencio.
     */
    fun vector(frames: List<FrameFeature>, endFrame: Int, length: Int): FloatArray? {
        val end = endFrame.coerceIn(0, frames.size - 1)
        val start = end - length + 1
        if (start < 0) return null
        if (frames[end].energy < SILENCE_GATE_NATS) return null
        val spectral = SpectralFeatures.resample(frames, start, end + 1) ?: return null

        // La duración entra como variable explícita: sin ella, el modelo tiene
        // que adivinar si una ventana es la palabra completa o una sílaba, y
        // aprende atajos que disparan en habla continua.
        val vector = FloatArray(spectral.size + 1)
        spectral.copyInto(vector)
        vector[spectral.size] = ((length - LENGTH_MEAN) / LENGTH_STD).toFloat()
        return vector
    }

    private const val LENGTH_MEAN = 48.0
    private const val LENGTH_STD = 18.0
}

/** Clasificador que puntúa un vector de ventana. */
internal interface WakeWordScoreModel {
    fun score(vector: FloatArray): Double
}

/** Clasificador logístico: `sigmoid(w · x + b)`, con vector ya normalizado. */
internal data class LogisticWakeWordModel(
    val weights: DoubleArray,
    val bias: Double
) : WakeWordScoreModel {
    override fun score(vector: FloatArray): Double {
        if (vector.size != weights.size) return 0.0
        var dot = bias
        for (i in vector.indices) dot += weights[i] * vector[i]
        return sigmoid(dot)
    }

    private fun sigmoid(value: Double): Double =
        if (value >= 0.0) 1.0 / (1.0 + kotlin.math.exp(-value))
        else {
            val exp = kotlin.math.exp(value)
            exp / (1.0 + exp)
        }
}

/**
 * Red pequeña de una capa oculta (ReLU). Más capacidad que la logística para
 * separar "fifo" de sus vecinas y del habla continua sin depender de una
 * frontera lineal.
 */
internal data class MlpWakeWordModel(
    val hiddenWeights: Array<DoubleArray>,
    val hiddenBias: DoubleArray,
    val outputWeights: DoubleArray,
    val outputBias: Double
) : WakeWordScoreModel {

    override fun score(vector: FloatArray): Double {
        if (vector.size != hiddenWeights.first().size) return 0.0
        var output = outputBias
        for (h in hiddenWeights.indices) {
            var sum = hiddenBias[h]
            val row = hiddenWeights[h]
            for (i in vector.indices) sum += row[i] * vector[i]
            if (sum > 0.0) output += outputWeights[h] * sum
        }
        return sigmoid(output)
    }

    private fun sigmoid(value: Double): Double =
        if (value >= 0.0) 1.0 / (1.0 + kotlin.math.exp(-value))
        else {
            val exp = kotlin.math.exp(value)
            exp / (1.0 + exp)
        }
}

/**
 * Entrenador determinista del clasificador (ADR-0046).
 *
 * Es un descenso de gradiente con Adam sobre regresión logística. No pretende
 * ser un framework: pretende ser reproducible, caber en una prueba y no añadir
 * una dependencia de Python al repositorio. La pregunta que responde es si la
 * aumentación de campo basta para que un modelo lineal aprenda la palabra en
 * lugar del canal.
 */
internal object WakeWordTrainer {

    internal data class Sample(val vector: FloatArray, val label: Int)

    fun train(
        clips: List<TrainingClip>,
        epochs: Int = 240,
        learningRate: Double = 0.06,
        l2: Double = 1e-3,
        positiveWeight: Double = 20.0,
        seed: Long = 0x4C4F_4749L
    ): LogisticWakeWordModel = trainSamples(
        samples = samples(clips),
        epochs = epochs,
        learningRate = learningRate,
        l2 = l2,
        positiveWeight = positiveWeight,
        seed = seed
    )

    fun trainSamples(
        samples: List<Sample>,
        epochs: Int = 240,
        learningRate: Double = 0.06,
        l2: Double = 1e-3,
        positiveWeight: Double = 20.0,
        seed: Long = 0x4C4F_4749L
    ): LogisticWakeWordModel {
        require(samples.any { it.label == 1 }) { "el corpus de entrenamiento no tiene positivos" }
        require(samples.any { it.label == 0 }) { "el corpus de entrenamiento no tiene negativos" }

        val dimensions = samples.first().vector.size
        val weights = DoubleArray(dimensions)
        val bias = 0.0
        var biasValue = bias
        val firstMoment = DoubleArray(dimensions)
        val secondMoment = DoubleArray(dimensions)
        var firstBiasMoment = 0.0
        var secondBiasMoment = 0.0
        val gradient = DoubleArray(dimensions)
        val rng = Random(seed)

        var positives = 0
        var negatives = 0
        for (sample in samples) if (sample.label == 1) positives++ else negatives++
        val weightPositive = positiveWeight.coerceAtLeast(1.0)
        val totalWeight = positives * weightPositive + negatives
        val order = IntArray(samples.size) { it }

        for (epoch in 1..epochs) {
            // Barajado determinista: sin él, Adam ve primero todos los positivos.
            for (i in order.indices.reversed()) {
                val j = rng.nextInt(i + 1)
                val swap = order[i]
                order[i] = order[j]
                order[j] = swap
            }

            gradient.fill(0.0)
            var gradientBias = 0.0
            var seenWeight = 0.0

            for (index in order) {
                val sample = samples[index]
                val weight = if (sample.label == 1) weightPositive else 1.0
                var dot = biasValue
                for (i in 0 until dimensions) dot += weights[i] * sample.vector[i]
                val probability = sigmoid(dot)
                val error = (probability - sample.label) * weight
                for (i in 0 until dimensions) gradient[i] += error * sample.vector[i]
                gradientBias += error
                seenWeight += weight
            }

            val scale = 1.0 / seenWeight.coerceAtLeast(1e-9)
            for (i in 0 until dimensions) {
                val g = gradient[i] * scale + l2 * weights[i]
                firstMoment[i] = BETA1 * firstMoment[i] + (1.0 - BETA1) * g
                secondMoment[i] = BETA2 * secondMoment[i] + (1.0 - BETA2) * g * g
                val mHat = firstMoment[i] / (1.0 - BETA1.pow(epoch))
                val vHat = secondMoment[i] / (1.0 - BETA2.pow(epoch))
                weights[i] -= learningRate * mHat / (sqrt(vHat) + EPSILON)
            }

            val gBias = gradientBias * scale
            firstBiasMoment = BETA1 * firstBiasMoment + (1.0 - BETA1) * gBias
            secondBiasMoment = BETA2 * secondBiasMoment + (1.0 - BETA2) * gBias * gBias
            val mHatBias = firstBiasMoment / (1.0 - BETA1.pow(epoch))
            val vHatBias = secondBiasMoment / (1.0 - BETA2.pow(epoch))
            biasValue -= learningRate * mHatBias / (sqrt(vHatBias) + EPSILON)
        }

        return LogisticWakeWordModel(weights = weights, bias = biasValue)
    }

    /**
     * Red de una capa oculta. Se entrena con una muestra balanceada de negativos
     * (los positivos son pocos y, sin recorte, el gradiente se los come).
     */
    fun trainMlp(
        samples: List<Sample>,
        hiddenUnits: Int = 24,
        epochs: Int = 35,
        learningRate: Double = 0.0015,
        l2: Double = 1e-4,
        negativeRatio: Int = 6,
        positiveWeight: Double = 6.0,
        seed: Long = 0x4D4C_5001L
    ): MlpWakeWordModel {
        val positives = samples.filter { it.label == 1 }
        val negatives = samples.filter { it.label == 0 }
        require(positives.isNotEmpty()) { "el corpus de entrenamiento no tiene positivos" }
        require(negatives.isNotEmpty()) { "el corpus de entrenamiento no tiene negativos" }

        val rng = Random(seed)
        val selected = positives + negatives.shuffled(rng).take(minOf(negatives.size, positives.size * negativeRatio))
        val dimensions = selected.first().vector.size
        val hidden = hiddenUnits.coerceAtLeast(2)

        val limitHidden = sqrt(6.0 / dimensions)
        val hiddenWeights = Array(hidden) { DoubleArray(dimensions) { rng.nextDouble(-limitHidden, limitHidden) } }
        val hiddenBias = DoubleArray(hidden)
        val limitOutput = sqrt(6.0 / hidden)
        val outputWeights = DoubleArray(hidden) { rng.nextDouble(-limitOutput, limitOutput) }
        var outputBias = 0.0

        val mHiddenW = Array(hidden) { DoubleArray(dimensions) }
        val vHiddenW = Array(hidden) { DoubleArray(dimensions) }
        val mHiddenB = DoubleArray(hidden)
        val vHiddenB = DoubleArray(hidden)
        val mOutputW = DoubleArray(hidden)
        val vOutputW = DoubleArray(hidden)
        var mOutputB = 0.0
        var vOutputB = 0.0

        val preActivation = DoubleArray(hidden)
        val activation = DoubleArray(hidden)
        val gradientHiddenW = Array(hidden) { DoubleArray(dimensions) }
        val gradientHiddenB = DoubleArray(hidden)
        val gradientOutputW = DoubleArray(hidden)

        val order = IntArray(selected.size) { it }
        var step = 0

        for (epoch in 1..epochs) {
            for (i in order.indices.reversed()) {
                val j = rng.nextInt(i + 1)
                val swap = order[i]
                order[i] = order[j]
                order[j] = swap
            }

            for (index in order) {
                val sample = selected[index]
                val vector = sample.vector

                for (h in 0 until hidden) {
                    var sum = hiddenBias[h]
                    val row = hiddenWeights[h]
                    for (i in 0 until dimensions) sum += row[i] * vector[i]
                    preActivation[h] = sum
                    activation[h] = if (sum > 0.0) sum else 0.0
                }

                var logit = outputBias
                for (h in 0 until hidden) logit += outputWeights[h] * activation[h]
                val probability = sigmoid(logit)
                val weight = if (sample.label == 1) positiveWeight else 1.0
                val error = (probability - sample.label) * weight

                for (h in 0 until hidden) gradientOutputW[h] = error * activation[h]
                val gradientOutputB = error

                for (h in 0 until hidden) {
                    if (preActivation[h] <= 0.0) {
                        gradientHiddenB[h] = 0.0
                        gradientHiddenW[h].fill(0.0)
                    } else {
                        val g = error * outputWeights[h]
                        gradientHiddenB[h] = g
                        val row = gradientHiddenW[h]
                        for (i in 0 until dimensions) row[i] = g * vector[i]
                    }
                }

                step++
                val biasCorrection1 = 1.0 - BETA1.pow(step)
                val biasCorrection2 = 1.0 - BETA2.pow(step)

                for (h in 0 until hidden) {
                    val gOut = gradientOutputW[h] + l2 * outputWeights[h]
                    mOutputW[h] = BETA1 * mOutputW[h] + (1.0 - BETA1) * gOut
                    vOutputW[h] = BETA2 * vOutputW[h] + (1.0 - BETA2) * gOut * gOut
                    outputWeights[h] -= learningRate *
                        (mOutputW[h] / biasCorrection1) / (sqrt(vOutputW[h] / biasCorrection2) + EPSILON)

                    val gBiasOut = gradientOutputB
                    mOutputB = BETA1 * mOutputB + (1.0 - BETA1) * gBiasOut
                    vOutputB = BETA2 * vOutputB + (1.0 - BETA2) * gBiasOut * gBiasOut
                    outputBias -= learningRate *
                        (mOutputB / biasCorrection1) / (sqrt(vOutputB / biasCorrection2) + EPSILON)

                    val gHiddenB = gradientHiddenB[h]
                    mHiddenB[h] = BETA1 * mHiddenB[h] + (1.0 - BETA1) * gHiddenB
                    vHiddenB[h] = BETA2 * vHiddenB[h] + (1.0 - BETA2) * gHiddenB * gHiddenB
                    hiddenBias[h] -= learningRate *
                        (mHiddenB[h] / biasCorrection1) / (sqrt(vHiddenB[h] / biasCorrection2) + EPSILON)

                    val rowW = hiddenWeights[h]
                    val rowM = mHiddenW[h]
                    val rowV = vHiddenW[h]
                    val rowG = gradientHiddenW[h]
                    for (i in 0 until dimensions) {
                        val g = rowG[i] + l2 * rowW[i]
                        rowM[i] = BETA1 * rowM[i] + (1.0 - BETA1) * g
                        rowV[i] = BETA2 * rowV[i] + (1.0 - BETA2) * g * g
                        rowW[i] -= learningRate *
                            (rowM[i] / biasCorrection1) / (sqrt(rowV[i] / biasCorrection2) + EPSILON)
                    }
                }
            }
        }

        return MlpWakeWordModel(
            hiddenWeights = hiddenWeights,
            hiddenBias = hiddenBias,
            outputWeights = outputWeights,
            outputBias = outputBias
        )
    }

    fun samples(clips: List<TrainingClip>): List<Sample> = buildList {
        for (clip in clips) {
            val frames = WakeWordWindows.framesOf(clip.pcm)
            if (frames.size < WakeWordWindows.MIN_FRAMES) continue

            val startSample = clip.wordStartSample
            val endSample = clip.wordEndSample

            if (startSample != null && endSample != null) {
                val startFrame = WakeWordWindows.frameIndexForSample(startSample)
                val endFrame = WakeWordWindows.frameIndexForSample(endSample)
                val wordFrames = (endFrame - startFrame + 1).coerceAtLeast(2)

                for (length in WakeWordWindows.TRAIN_LENGTHS) {
                    // Un "fifo" positivo solo lo es si la ventana cubre la
                    // palabra completa; una ventana más corta es una sílaba, y
                    // una más larga arrastra el silencio o la palabra anterior.
                    val label = if (clip.positive && length in (wordFrames - 6)..(wordFrames + 4)) 1 else 0
                    for (jitter in intArrayOf(0, -1)) {
                        val vector = WakeWordWindows.vector(frames, endFrame + jitter, length) ?: continue
                        add(Sample(vector, label))
                    }
                }

                // La palabra a medias y el silencio que la rodea no son la palabra.
                for (end in intArrayOf(endFrame + 8, endFrame + 16, startFrame - 8, startFrame - 16)) {
                    if (end !in frames.indices) continue
                    for (length in WakeWordWindows.TRAIN_LENGTHS) {
                        WakeWordWindows.vector(frames, end, length)?.let { add(Sample(it, 0)) }
                    }
                }
            } else {
                // Conversación: posiciones y duraciones variadas, siempre negativas.
                val rng = Random(Seeding.of(clip.id, "windows"))
                repeat(8) {
                    val end = rng.nextInt(WakeWordWindows.MIN_FRAMES - 1, frames.size)
                    for (length in WakeWordWindows.TRAIN_LENGTHS) {
                        WakeWordWindows.vector(frames, end, length)?.let { add(Sample(it, 0)) }
                    }
                }
            }
        }
    }

    private fun sigmoid(value: Double): Double =
        if (value >= 0.0) 1.0 / (1.0 + kotlin.math.exp(-value))
        else {
            val exp = kotlin.math.exp(value)
            exp / (1.0 + exp)
        }

    private fun Double.pow(exponent: Int): Double {
        var result = 1.0
        repeat(exponent) { result *= this }
        return result
    }

    private const val BETA1 = 0.9
    private const val BETA2 = 0.999
    private const val EPSILON = 1e-8
}

/**
 * Detector entrenado (ADR-0046). Misma costura que el de plantilla y misma
 * ventana deslizante; cambia el comparador: en vez de un coseno contra una
 * plantilla, una probabilidad logística sobre el vector CMVN.
 *
 * Vive en `test` a propósito: el modelo se entrena con voz sintética y todavía
 * no ha visto una grabación real. Enchufarlo a producción es una línea, pero
 * hacerlo antes de la validación de campo sería vender una medida por un hecho.
 */
internal class LearnedWakeWordDetector(
    private val model: WakeWordScoreModel,
    private val threshold: Double,
    override val modelId: String = "logistic-synthetic-v1",
    private val onScore: ((score: Double, atMillis: Long) -> Unit)? = null
) : com.fifo.voicepipeline.pipeline.session.WakeWordDetector {

    private val frontend = SpectralFrontend()
    private val frames = ArrayDeque<FrameFeature>()
    private var lastDetectionMillis = NEVER
    private var lastScoreMillis = NEVER

    override fun accept(frame: ByteArray, nowMillis: Long): com.fifo.voicepipeline.pipeline.session.WakeWordDetection? {
        var index = 0
        while (index + 1 < frame.size) {
            val raw = ((frame[index].toInt() and 0xFF) or (frame[index + 1].toInt() shl 8)).toShort()
            frontend.push(raw / 32768f)
            val feature = frontend.pollFrame()
            if (feature != null) {
                frames.addLast(feature)
                if (frames.size > WakeWordWindows.MAX_FRAMES) frames.removeFirst()
            }
            index += 2
        }

        if (frames.size < WakeWordWindows.MIN_FRAMES) return null
        if (lastDetectionMillis != NEVER && nowMillis - lastDetectionMillis < REFRACTORY_MS) return null
        if (nowMillis - lastScoreMillis < SCORE_INTERVAL_MS) return null
        lastScoreMillis = nowMillis

        val score = scoreWindow()
        onScore?.invoke(score, nowMillis)
        if (score < threshold) return null

        lastDetectionMillis = nowMillis
        return com.fifo.voicepipeline.pipeline.session.WakeWordDetection(
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

    internal fun scoreWindow(): Double {
        if (frames.size < WakeWordWindows.MIN_FRAMES) return 0.0
        if (frames.last().energy < WakeWordWindows.SILENCE_GATE_NATS) return 0.0

        val snapshot = frames.toList()
        val end = snapshot.size - 1
        var best = 0.0
        for (length in WakeWordWindows.LENGTHS) {
            val vector = WakeWordWindows.vector(snapshot, end, length) ?: continue
            val score = model.score(vector)
            if (score > best) best = score
        }
        return best
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE / 2
        const val REFRACTORY_MS = 800L
        const val SCORE_INTERVAL_MS = 32L
    }
}
