package com.fifo.voicepipeline.audio

/**
 * Constantes de configuración de audio compartidas entre
 * el WebSocket server, VAD, y el pipeline de nube.
 */
object AudioConfig {
    /** Sample rate en Hz — debe coincidir con el ESP32 */
    const val SAMPLE_RATE = 16000

    /** Bits por muestra */
    const val BITS_PER_SAMPLE = 16

    /** Canales (mono) */
    const val CHANNELS = 1

    /** Bytes por muestra (16-bit = 2 bytes) */
    const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8

    /** Tamaño del chunk que envía el ESP32 (512 muestras * 2 bytes) */
    const val CHUNK_BYTES = 1024

    /** Duración de un chunk en ms (512 samples @ 16kHz = 32ms) */
    const val CHUNK_DURATION_MS = 32

    /** Puerto del WebSocket server */
    const val WS_PORT = 8080

    // ── VAD (Voice Activity Detection) ──────────────
    /** Umbral RMS para considerar que hay voz (ajustar según entorno) */
    const val VAD_RMS_THRESHOLD = 500.0

    /** Tiempo de silencio (ms) antes de cortar la frase */
    const val VAD_SILENCE_TIMEOUT_MS = 800L

    /** Tamaño máximo del buffer de grabación (10 segundos de audio) */
    const val MAX_RECORDING_BYTES = SAMPLE_RATE * BYTES_PER_SAMPLE * 10

    // ── WAV Header ──────────────────────────────────
    /** Tamaño del header WAV estándar */
    const val WAV_HEADER_SIZE = 44
}
