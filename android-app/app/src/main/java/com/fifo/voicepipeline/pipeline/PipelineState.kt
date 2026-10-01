package com.fifo.voicepipeline.pipeline

/**
 * Estados posibles del pipeline de voz.
 * Controla la UI y la lógica del flujo.
 */
enum class PipelineState {
    /** Sin conexión del ESP32 */
    DISCONNECTED,

    /** En reposo / durmiendo esperando que el usuario diga "FIFO" */
    SLEEPING,

    /** ESP32 despierto y listo, esperando que el usuario hable */
    IDLE,

    /** Recibiendo audio del usuario (VAD activo) */
    LISTENING,

    /** Procesando: STT → LLM → TTS en la nube */
    PROCESSING,

    /** Reproduciendo respuesta por el parlante del celular */
    SPEAKING,

    /** Error recoverable — se mostrará mensaje */
    ERROR
}

/**
 * Fuente del micrófono para la captura de voz:
 * - ESP32: Micrófono analógico UCC conectado al ESP32 vía WebSocket
 * - PHONE: Micrófono integrado del celular (fallback por si el del ESP32 falla)
 */
enum class MicSource {
    ESP32,
    PHONE
}

