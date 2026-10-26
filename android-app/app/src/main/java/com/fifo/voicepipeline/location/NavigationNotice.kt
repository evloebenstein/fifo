package com.fifo.voicepipeline.location

/**
 * Tipo de aviso autónomo que el motor de navegación detecta por sí mismo
 * (ADR-0040). El motor no redacta: entrega el hecho y el LLM decide cómo decirlo.
 */
enum class NavigationNoticeKind {
    ARRIVED,
    OFF_COURSE,
    APPROACHING_100M,
    APPROACHING_50M,
    STALLED
}

/**
 * Aviso autónomo estructurado (ADR-0040).
 *
 * Es el contrato entre el sensor de navegación y el pipeline: no depende de
 * Android ni del manager que lo produce. [fallbackSpeech] es el único texto
 * estático y se usa solo cuando el LLM no está disponible (regla 1: fallback
 * permitido).
 */
data class NavigationNotice(
    val kind: NavigationNoticeKind,
    val destinationName: String,
    val destinationAddress: String,
    val streetName: String,
    val distanceMeters: Int,
    val blocks: Int,
    val walkingMinutes: Int,
    val relativeInstruction: String,
    val cardinalDirection: String,
    val isFacingTarget: Boolean,
    val stalledSeconds: Int = 0,
    val plannedStops: List<String> = emptyList(),
    val pauseReason: String = ""
) {
    fun fallbackSpeech(): String = when (kind) {
        NavigationNoticeKind.ARRIVED ->
            "Llegamos a $destinationName."
        NavigationNoticeKind.OFF_COURSE ->
            "Parece que se desvió del camino. Para volver a $destinationName, $relativeInstruction."
        NavigationNoticeKind.APPROACHING_100M ->
            "Siga derecho por $streetName, está a una cuadra de $destinationName."
        NavigationNoticeKind.APPROACHING_50M ->
            "Está a unos 50 metros de $destinationName."
        NavigationNoticeKind.STALLED ->
            "¿Todo bien? Vamos hacia $destinationName y quedan unos $distanceMeters metros."
    }
}
