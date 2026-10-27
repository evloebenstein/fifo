package com.fifo.voicepipeline.pipeline.session

import kotlin.math.sqrt

/**
 * Costura de identidad de locutor (ADR-0048).
 *
 * Igual que `WakeWordDetector`, es un **generador de evidencia**, nunca una
 * autoridad:
 *
 * - No abre micrófono: `VoiceSession` le entrega el WAV que ya cerró
 *   `SpeechSegmenter`.
 * - No habla ni autoriza acciones. La decisión de cómo tratar el turno la toma
 *   el LLM con [SpeakerPromptContext]; las acciones sensibles siguen exigiendo
 *   confirmación explícita.
 * - Corre con timeout y nunca bloquea el turno: si tarda o falla, el turno se
 *   procesa como `UNAVAILABLE`.
 * - Guarda embeddings, no audio. El perfil es dato biométrico y su almacén debe
 *   cifrarlo en reposo con consentimiento explícito (ADR-0048).
 */
interface SpeakerIdentityProvider {

    /** Identificador estable del modelo y su versión. */
    val modelId: String

    /**
     * Atribuye el WAV a un perfil. Devuelve `UNAVAILABLE` cuando no hay motor,
     * perfiles o audio suficiente; nunca lanza para no romper el turno.
     */
    suspend fun identify(wavData: ByteArray, nowMillis: Long): SpeakerIdentity
}

/**
 * Identidad ausente: todos los turnos quedan sin atribuir.
 *
 * Es el valor por defecto mientras no exista un modelo de embeddings medido y
 * un almacén cifrado de perfiles. El comportamiento actual no cambia.
 */
object NoopSpeakerIdentityProvider : SpeakerIdentityProvider {
    override val modelId: String = NOOP_SPEAKER_MODEL_ID

    override suspend fun identify(wavData: ByteArray, nowMillis: Long): SpeakerIdentity =
        SpeakerIdentity.unavailable(modelId)
}

/**
 * Extractor de embeddings de voz.
 *
 * Es la única pieza que depende de un modelo real (WeSpeaker/ReDimNet/ECAPA en
 * ONNX, o un SDK local como Picovoice Eagle). Vive detrás de esta interfaz para
 * que el resto del pipeline se pruebe en JVM con un extractor falso y para que
 * cambiar de motor no toque `VoiceSession`.
 */
interface SpeakerEmbeddingExtractor {

    /** Debe cambiar cuando cambie el modelo o su versión. */
    val modelId: String

    /**
     * Devuelve un vector L2-normalizable o `null` si el audio no alcanza.
     * La implementación real debe correr en CPU/dispositivo, nunca subir el
     * audio a un servidor.
     */
    suspend fun embed(wavData: ByteArray): FloatArray?
}

/**
 * Almacén de perfiles de voz. La implementación de producción debe cifrar en
 * reposo (Android Keystore + AES-GCM o SQLCipher) y permitir borrado completo.
 */
interface VoiceProfileStore {
    suspend fun loadAll(): List<VoiceProfile>
    suspend fun upsert(profile: VoiceProfile)
    suspend fun delete(profileId: String)
    suspend fun clear()
}

/**
 * Proveedor de identidad por embeddings + similitud coseno.
 *
 * Carga los perfiles del modelo activo, extrae el embedding del turno y compara
 * contra cada perfil. La decisión final es de [SpeakerIdentityPolicy]; este
 * proveedor solo produce evidencia ordenada.
 *
 * No se envía todavía: le falta un [SpeakerEmbeddingExtractor] real y un
 * [VoiceProfileStore] cifrado. Se incluye para fijar el contrato y poder medir
 * el flujo completo con un extractor falso.
 */
class EmbeddingSpeakerIdentityProvider(
    private val extractor: SpeakerEmbeddingExtractor,
    private val store: VoiceProfileStore,
    private val ownerProfileIdProvider: () -> String? = { null },
    private val config: SpeakerPolicyConfig = SpeakerPolicyConfig(),
    private val maxCandidates: Int = 3
) : SpeakerIdentityProvider {

    init {
        require(maxCandidates > 0) { "maxCandidates debe ser positivo" }
    }

    override val modelId: String
        get() = extractor.modelId

    override suspend fun identify(wavData: ByteArray, nowMillis: Long): SpeakerIdentity {
        val profiles = try {
            store.loadAll().filter { it.modelId == extractor.modelId }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return SpeakerIdentity.unavailable(extractor.modelId)
        }
        if (profiles.isEmpty()) return SpeakerIdentity.unavailable(extractor.modelId)

        val embedding = try {
            extractor.embed(wavData)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return SpeakerIdentity.unavailable(extractor.modelId)

        val query = l2Normalise(embedding)
        if (query.isEmpty()) return SpeakerIdentity.unavailable(extractor.modelId)

        val candidates = profiles.mapNotNull { profile ->
            val reference = l2Normalise(profile.embedding)
            if (reference.size != query.size) return@mapNotNull null
            SpeakerCandidate(
                profileId = profile.id,
                score = cosineSimilarity(query, reference).coerceIn(0.0, 1.0)
            )
        }
            .sortedByDescending { it.score }
            .take(maxCandidates)

        val evidence = SpeakerEvidence(
            candidates = candidates,
            durationMillis = wavDurationMillis(wavData),
            observedAtMillis = nowMillis,
            modelId = extractor.modelId
        )
        return SpeakerIdentityPolicy.resolve(
            evidence = evidence,
            ownerProfileId = ownerProfileIdProvider(),
            profiles = profiles,
            config = config
        )
    }
}

/**
 * Enrolamiento de un perfil a partir de varias muestras.
 *
 * Guarda el centroide de los embeddings, no las grabaciones. Exige al menos
 * [minSamples] muestras para que un único audio no defina una identidad.
 */
class SpeakerEnrollment(
    private val extractor: SpeakerEmbeddingExtractor,
    private val store: VoiceProfileStore,
    private val minSamples: Int = 3
) {
    init {
        require(minSamples > 0) { "minSamples debe ser positivo" }
    }

    /**
     * Devuelve el perfil guardado o `null` si no hay muestras suficientes o los
     * embeddings tienen dimensiones distintas (modelo cambiado a mitad).
     */
    suspend fun enroll(
        profileId: String,
        displayName: String,
        samples: List<ByteArray>,
        nowMillis: Long
    ): VoiceProfile? {
        val embeddings = samples.mapNotNull { sample ->
            try {
                extractor.embed(sample)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }.filter { it.isNotEmpty() }

        if (embeddings.size < minSamples) return null
        val dimension = embeddings.first().size
        if (embeddings.any { it.size != dimension }) return null

        val centroid = FloatArray(dimension) { index ->
            (embeddings.sumOf { it[index].toDouble() } / embeddings.size).toFloat()
        }
        val profile = VoiceProfile(
            id = profileId,
            displayName = displayName,
            embedding = l2Normalise(centroid),
            modelId = extractor.modelId,
            enrolledAtMillis = nowMillis,
            sampleCount = embeddings.size
        )
        store.upsert(profile)
        return profile
    }
}

/** Duración aproximada de un WAV PCM16 mono a 16 kHz con cabecera estándar. */
internal fun wavDurationMillis(wavData: ByteArray): Long {
    if (wavData.size <= WAV_HEADER_BYTES) return 0L
    return (wavData.size - WAV_HEADER_BYTES) * 1_000L / (SAMPLE_RATE * BYTES_PER_SAMPLE)
}

internal fun l2Normalise(values: FloatArray): FloatArray {
    var squares = 0.0
    for (value in values) squares += value.toDouble() * value
    val norm = sqrt(squares)
    if (norm <= 1e-9) return FloatArray(0)
    return FloatArray(values.size) { (values[it] / norm).toFloat() }
}

internal fun cosineSimilarity(first: FloatArray, second: FloatArray): Double {
    if (first.isEmpty() || first.size != second.size) return 0.0
    var dot = 0.0
    var firstSquares = 0.0
    var secondSquares = 0.0
    for (index in first.indices) {
        val a = first[index].toDouble()
        val b = second[index].toDouble()
        dot += a * b
        firstSquares += a * a
        secondSquares += b * b
    }
    val denominator = sqrt(firstSquares) * sqrt(secondSquares)
    return if (denominator <= 1e-9) 0.0 else dot / denominator
}

private const val WAV_HEADER_BYTES = 44
private const val SAMPLE_RATE = 16_000L
private const val BYTES_PER_SAMPLE = 2L
