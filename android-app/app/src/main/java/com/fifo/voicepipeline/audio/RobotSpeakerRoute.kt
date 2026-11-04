package com.fifo.voicepipeline.audio

/**
 * Costura de salida de voz hacia el hardware del robot (ADR-0055).
 *
 * El pipeline no conoce BLE: solo pide "manda este PCM al parlante". Quien
 * implementa la costura es el cliente BLE, y cuando no hay robot conectado la
 * voz sigue saliendo por el parlante del teléfono.
 */
interface RobotSpeakerRoute {

    /** `true` si hay un robot enlazado que pueda reproducir el audio. */
    val isAvailable: Boolean

    /**
     * Envía un bloque PCM 16-bit little endian mono a 8 kHz.
     * @return `false` si el enlace no está listo y conviene reintentar.
     */
    fun sendPcm(pcm: ByteArray): Boolean

    /** Descarta lo que quede en la cola del robot. */
    fun flush()
}
