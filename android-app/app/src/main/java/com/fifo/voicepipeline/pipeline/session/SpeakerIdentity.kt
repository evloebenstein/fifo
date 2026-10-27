package com.fifo.voicepipeline.pipeline.session

/** Identificador del motor ausente: no hay modelo de voz cargado. */
internal const val NOOP_SPEAKER_MODEL_ID = "noop"

/**
 * Relación entre la voz que acaba de hablar y los perfiles enrolados en el
 * dispositivo (ADR-0048).
 *
 * La identidad de voz es **personalización y atribución de turno**, no
 * autenticación: ninguna acción sensible (llamadas, pagos, datos privados) se
 * autoriza solo porque la voz suene como el dueño.
 */
enum class SpeakerRelation {
    /** La voz coincide con el perfil del usuario principal. */
    OWNER,

    /** La voz coincide con otra persona enrolada. */
    KNOWN,

    /** Hay voz, pero no se parece a ningún perfil con confianza suficiente. */
    UNKNOWN,

    /** La evidencia es ambigua o el audio es demasiado corto para decidir. */
    UNCERTAIN,

    /** No hay motor de identidad o no hay perfiles: no se puede atribuir. */
    UNAVAILABLE
}

/**
 * Resultado de atribuir un turno a una voz.
 *
 * [confidence] es la similitud (0..1) del mejor candidato, no una probabilidad
 * calibrada. [modelId] viaja con cada decisión para que un cambio de modelo sin
 * cambiar el id invalide la medición.
 */
data class SpeakerIdentity(
    val relation: SpeakerRelation,
    val profileId: String? = null,
    val displayName: String? = null,
    val confidence: Double = 0.0,
    val modelId: String = NOOP_SPEAKER_MODEL_ID
) {
    init {
        require(confidence in 0.0..1.0) { "confidence fuera de 0..1: $confidence" }
        require(relation == SpeakerRelation.OWNER || relation == SpeakerRelation.KNOWN || profileId == null) {
            "solo OWNER/KNOWN pueden llevar profileId"
        }
    }

    /** True solo cuando el turno se puede personalizar con confianza. */
    val isPersonal: Boolean
        get() = relation == SpeakerRelation.OWNER || relation == SpeakerRelation.KNOWN

    companion object {
        fun unavailable(modelId: String = NOOP_SPEAKER_MODEL_ID): SpeakerIdentity =
            SpeakerIdentity(relation = SpeakerRelation.UNAVAILABLE, modelId = modelId)
    }
}

/** Un perfil compara contra la voz observada; la lista va ordenada de mayor a menor. */
data class SpeakerCandidate(
    val profileId: String,
    val score: Double
) {
    init {
        require(profileId.isNotBlank()) { "profileId vacío" }
        require(score in 0.0..1.0) { "score fuera de 0..1: $score" }
    }
}

/**
 * Evidencia cruda que produce un motor de identidad.
 *
 * No es una decisión: la toma [SpeakerIdentityPolicy] con el umbral, el margen
 * y la duración mínima. Separar evidencia de decisión permite recalibrar sin
 * tocar el motor y medir el mismo motor contra políticas distintas.
 */
data class SpeakerEvidence(
    val candidates: List<SpeakerCandidate>,
    val durationMillis: Long,
    val observedAtMillis: Long,
    val modelId: String,
    /** Riesgo de spoofing 0..1, o `null` si no hay contramedida medida. */
    val spoofRisk: Double? = null,
    /** Modelo que produjo [spoofRisk]; `null` cuando no hay medición. */
    val spoofModelId: String? = null
) {
    init {
        require(durationMillis >= 0) { "durationMillis negativo: $durationMillis" }
        require(candidates.zipWithNext().all { (first, second) -> first.score >= second.score }) {
            "los candidatos deben venir ordenados de mayor a menor"
        }
        require(spoofRisk == null || spoofRisk in 0.0..1.0) { "spoofRisk fuera de 0..1: $spoofRisk" }
        require((spoofRisk == null) == (spoofModelId == null)) {
            "spoofRisk y spoofModelId deben venir juntos"
        }
    }
}

/**
 * Perfil de voz persistible.
 *
 * Se guarda el **embedding**, nunca el audio crudo del enrolamiento. El perfil
 * es dato biométrico: su almacén debe cifrarlo en reposo, pedir consentimiento
 * explícito y permitir borrarlo (ADR-0048).
 */
data class VoiceProfile(
    val id: String,
    val displayName: String,
    val embedding: FloatArray,
    val modelId: String,
    val enrolledAtMillis: Long,
    val sampleCount: Int
) {
    init {
        require(id.isNotBlank()) { "id vacío" }
        require(displayName.isNotBlank()) { "displayName vacío" }
        require(embedding.isNotEmpty()) { "embedding vacío" }
        require(modelId.isNotBlank()) { "modelId vacío" }
        require(sampleCount > 0) { "sampleCount debe ser positivo" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VoiceProfile) return false
        return id == other.id &&
            displayName == other.displayName &&
            embedding.contentEquals(other.embedding) &&
            modelId == other.modelId &&
            enrolledAtMillis == other.enrolledAtMillis &&
            sampleCount == other.sampleCount
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + modelId.hashCode()
        result = 31 * result + enrolledAtMillis.hashCode()
        result = 31 * result + sampleCount
        return result
    }
}

/**
 * Umbrales de decisión. Son configuración, no constantes del motor: cada modelo
 * trae su propia distribución de similitudes y debe recalibrarse con el arnés
 * antes de enviarse (ADR-0048).
 */
data class SpeakerPolicyConfig(
    val minSpeechMillis: Long = 1_200L,
    val minAcceptScore: Double = 0.70,
    val minScoreMargin: Double = 0.06,
    /** Riesgo de spoofing a partir del cual la identidad deja de personalizar. */
    val maxSpoofRisk: Double = 0.50
) {
    init {
        require(minSpeechMillis >= 0) { "minSpeechMillis negativo" }
        require(minAcceptScore in 0.0..1.0) { "minAcceptScore fuera de 0..1" }
        require(minScoreMargin in 0.0..1.0) { "minScoreMargin fuera de 0..1" }
        require(maxSpoofRisk in 0.0..1.0) { "maxSpoofRisk fuera de 0..1" }
    }
}

/**
 * Política pura de atribución de locutor (ADR-0048).
 *
 * Reglas, en orden:
 *
 * 1. Sin evidencia o sin perfiles → `UNAVAILABLE`.
 * 2. Audio más corto que [SpeakerPolicyConfig.minSpeechMillis] → `UNCERTAIN`
 *    (una palabra suelta no basta para atribuir un turno).
 * 3. Riesgo de spoofing igual o mayor que [SpeakerPolicyConfig.maxSpoofRisk] →
 *    `UNCERTAIN` (voz sintética/reproducida, aunque el score sea alto).
 * 4. Mejor similitud por debajo de [SpeakerPolicyConfig.minAcceptScore] →
 *    `UNKNOWN`.
 * 5. Diferencia con el segundo perfil menor que
 *    [SpeakerPolicyConfig.minScoreMargin] → `UNCERTAIN` (dos voces parecidas).
 * 6. Coincide con el usuario principal → `OWNER`; cualquier otro perfil
 *    enrolado → `KNOWN`.
 *
 * La política clasifica; no habla, no guarda perfiles y no autoriza acciones.
 */
object SpeakerIdentityPolicy {

    fun resolve(
        evidence: SpeakerEvidence?,
        ownerProfileId: String?,
        profiles: List<VoiceProfile> = emptyList(),
        config: SpeakerPolicyConfig = SpeakerPolicyConfig()
    ): SpeakerIdentity {
        if (evidence == null) return SpeakerIdentity.unavailable()
        val modelId = evidence.modelId

        if (evidence.durationMillis < config.minSpeechMillis) {
            return SpeakerIdentity(
                relation = SpeakerRelation.UNCERTAIN,
                confidence = evidence.candidates.firstOrNull()?.score ?: 0.0,
                modelId = modelId
            )
        }

        val spoofRisk = evidence.spoofRisk
        if (spoofRisk != null && spoofRisk >= config.maxSpoofRisk) {
            return SpeakerIdentity(
                relation = SpeakerRelation.UNCERTAIN,
                confidence = evidence.candidates.firstOrNull()?.score ?: 0.0,
                modelId = modelId
            )
        }

        val best = evidence.candidates.firstOrNull()
            ?: return SpeakerIdentity.unavailable(modelId)

        if (best.score < config.minAcceptScore) {
            return SpeakerIdentity(
                relation = SpeakerRelation.UNKNOWN,
                confidence = best.score,
                modelId = modelId
            )
        }

        val runnerUp = evidence.candidates.getOrNull(1)
        if (runnerUp != null && best.score - runnerUp.score < config.minScoreMargin) {
            return SpeakerIdentity(
                relation = SpeakerRelation.UNCERTAIN,
                confidence = best.score,
                modelId = modelId
            )
        }

        val profile = profiles.firstOrNull { it.id == best.profileId }
        val relation = if (ownerProfileId != null && best.profileId == ownerProfileId) {
            SpeakerRelation.OWNER
        } else {
            SpeakerRelation.KNOWN
        }
        return SpeakerIdentity(
            relation = relation,
            profileId = best.profileId,
            displayName = profile?.displayName,
            confidence = best.score,
            modelId = modelId
        )
    }
}

/**
 * Contexto textual de locutor para el prompt del LLM.
 *
 * No es una respuesta hablada: es una etiqueta que el modelo recibe como dato.
 * El LLM decide cómo tratar el turno; la política no redacta locuciones.
 */
object SpeakerPromptContext {

    fun build(identity: SpeakerIdentity?): String? = when (identity?.relation) {
        SpeakerRelation.OWNER -> {
            val name = identity.displayName?.takeIf { it.isNotBlank() }
            if (name == null) {
                "HABLANTE: la voz corresponde al usuario principal. Personaliza el trato con su perfil y su memoria."
            } else {
                "HABLANTE: la voz corresponde al usuario principal ($name). Personaliza el trato con su perfil y su memoria."
            }
        }

        SpeakerRelation.KNOWN -> {
            val name = identity.displayName?.takeIf { it.isNotBlank() } ?: "una persona conocida"
            "HABLANTE: la voz corresponde a $name, una persona conocida distinta del usuario principal. " +
                "Atiende el turno, pero no mezcles su conversación con la memoria privada del usuario principal " +
                "ni reveles datos suyos."
        }

        SpeakerRelation.UNKNOWN ->
            "HABLANTE: voz no reconocida (posible invitado). Atiende con cortesía, no la atribuyas al " +
                "usuario principal y no reveles datos privados."

        SpeakerRelation.UNCERTAIN ->
            "HABLANTE: identificación de voz ambigua o audio insuficiente. No atribuyas el turno al " +
                "usuario principal sin confirmación."

        SpeakerRelation.UNAVAILABLE, null -> null
    }
}
