package com.fifo.voicepipeline.pipeline.session

/**
 * Contramedida de spoofing (ADR-0048).
 *
 * La identidad de voz **personaliza, no autentica**, pero una voz sintética o
 * reproducida no debería atribuir un turno al dueño. Este detector produce una
 * probabilidad de ataque (0 = voz genuina, 1 = ataque) para que la política
 * degrade el turno a `UNCERTAIN` cuando la evidencia no es fiable.
 *
 * Reglas:
 *
 * - Recibe el WAV que ya cerró `SpeechSegmenter`; no abre micrófono ni habla.
 * - Corre con timeout junto a la extracción del embedding y nunca bloquea el
 *   turno: si falla o tarda, [SpeakerEvidence.spoofRisk] queda `null`.
 * - No autoriza ni rechaza acciones por sí solo. La decisión es de
 *   [SpeakerIdentityPolicy]; las acciones sensibles siguen exigiendo
 *   confirmación explícita.
 * - [NoopSpoofingDetector] es el valor por defecto: no hay modelo de spoofing
 *   validado y no se inventa una protección que no existe.
 */
interface SpoofingDetector {

    /** Identificador estable del modelo y su versión. */
    val modelId: String

    /** Riesgo de ataque en 0..1, o `null` si no hay modelo o no se pudo medir. */
    suspend fun assess(wavData: ByteArray): SpoofingAssessment?
}

/**
 * Evidencia de spoofing. [risk] es una probabilidad del modelo, no una verdad
 * absoluta; se guarda con su [modelId] para invalidarla cuando cambie el motor.
 */
data class SpoofingAssessment(
    val risk: Double,
    val modelId: String
) {
    init {
        require(risk in 0.0..1.0) { "risk fuera de 0..1: $risk" }
        require(modelId.isNotBlank()) { "modelId vacío" }
    }
}

/** Sin contramedida: el riesgo es desconocido y la política no cambia. */
object NoopSpoofingDetector : SpoofingDetector {
    override val modelId: String = "noop"

    override suspend fun assess(wavData: ByteArray): SpoofingAssessment? = null
}
