package com.fifo.voicepipeline.pipeline

/**
 * Camino de producción activo. Solo puede haber uno a la vez.
 */
enum class VoicePipelineMode {
    LEGACY,
    SESSION
}

/**
 * Selección pura del pipeline según el flag de release y los prerrequisitos.
 *
 * `VOICE_SESSION_ENABLED` es un flag de una release: en debug está apagado y en
 * release encendido. Aun así, el camino nuevo necesita STT en la nube; sin claves
 * se conserva el legacy para no dejar a Fifo sordo.
 */
object VoicePipelineModeSelector {
    fun select(flagEnabled: Boolean, hasCloudStt: Boolean): VoicePipelineMode {
        return if (flagEnabled && hasCloudStt) VoicePipelineMode.SESSION else VoicePipelineMode.LEGACY
    }
}
