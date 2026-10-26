package com.fifo.voicepipeline.pipeline.session

/**
 * Candidato acústico de invocación ("Fifo") emitido por un [WakeWordDetector].
 *
 * No es una decisión: es la evidencia de que en esa ventana de audio hubo algo
 * que se parece a la palabra de activación. Quien decide es
 * [UtterancePolicy.resolveWake] combinándola con la transcripción.
 *
 * [score] va en 0..1 para poder ajustar umbrales y medir calibración sin tocar
 * el detector. [modelId] identifica la versión del modelo: un cambio de modelo
 * sin cambiar el id invalida las pruebas de regresión.
 */
data class WakeWordDetection(
    val score: Double,
    val detectedAtMillis: Long,
    val modelId: String
) {
    init {
        require(score in 0.0..1.0) { "score fuera de 0..1: $score" }
    }
}

/**
 * Costura del detector acústico de invocación (ADR-0043).
 *
 * El detector es un **generador de candidatos**, nunca una autoridad:
 *
 * - No abre micrófono ni conoce Android: la sesión le entrega las mismas tramas
 *   que ya captura `CaptureArbiter` (16 kHz, 16 bits, mono, little endian).
 * - No contiene VAD ni pre-roll: eso sigue siendo de [SpeechSegmenter].
 * - No habla, no despierta por sí solo y **jamás autoriza una acción de
 *   llamada**. Esa sigue siendo competencia exclusiva de
 *   [UtterancePolicy.classifyCallAction] sobre la transcripción.
 * - Solo se evalúa mientras Fifo duerme o espera: durante una locución la
 *   propia voz produciría falsos positivos ("Fifo contesta", "Fifo cuelga").
 *
 * Se implementa detrás de esta interfaz para poder probar la cascada en JVM con
 * un detector falso y para poder cambiar de motor (openWakeWord, microWakeWord)
 * sin tocar la sesión.
 */
interface WakeWordDetector {

    /** Identificador estable del modelo y su versión, para telemetría y regresión. */
    val modelId: String

    /**
     * Procesa una trama de audio cruda y devuelve un candidato si la ventana
     * deslizante cruza el umbral. Debe ser barato: se llama por cada trama.
     */
    fun accept(frame: ByteArray, nowMillis: Long): WakeWordDetection?

    /**
     * Descarta el estado interno de la ventana (pre-roll del detector).
     * Se llama al dormir, al silenciar el micrófono y al reiniciar la sesión.
     */
    fun reset()
}

/**
 * Detector ausente: el camino léxico sigue siendo el único que despierta a Fifo.
 *
 * Es el valor por defecto de la sesión para que el comportamiento actual no
 * cambie hasta que exista un modelo entrenado y medido (ADR-0043).
 */
object NoopWakeWordDetector : WakeWordDetector {
    override val modelId: String = "noop"

    override fun accept(frame: ByteArray, nowMillis: Long): WakeWordDetection? = null

    override fun reset() = Unit
}
