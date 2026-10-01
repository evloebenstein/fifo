package com.fifo.voicepipeline.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Procesador VAD adaptativo de alta inmunidad al ruido ambiental.
 *
 * Diseñado específicamente para funcionar en espacios ruidosos y concurridos:
 * 1. Calibración continua del piso de ruido ambiental (Adaptive Noise Floor Tracking).
 * 2. Umbral dinámico con histéresis (Start Threshold > Continue Threshold).
 * 3. Filtro paso-altos interno para ignorar zumbidos graves y componentes DC (<150 Hz).
 * 4. Límite de seguridad de duración máxima de frase (evita grabaciones infinitas por murmullo de fondo).
 * 5. Filtro de rechazo a transitorios cortos (descarta aplausos, golpes o ruidos < 300 ms).
 */
class VadProcessor(
    private val silenceTimeoutMs: Long = AudioConfig.VAD_SILENCE_TIMEOUT_MS,
    private val maxSpeechDurationMs: Long = 8000L, // 8 segundos máx por turno
    private val minSpeechDurationMs: Long = 350L   // Frases menores a 350ms se consideran ruido
) {
    /** Piso de ruido ambiental estimado dinámicamente */
    var ambientNoiseFloor: Double = 400.0
        private set

    /** ¿Se detectó voz en algún momento de la sesión actual? */
    var isSpeechDetected: Boolean = false
        private set

    /** Timestamp del último chunk con voz */
    private var lastSpeechTimestamp: Long = 0L

    /** Timestamp de cuando comenzó la frase actual */
    private var speechStartTimestamp: Long = 0L

    /** ¿Estamos actualmente dentro de una frase? */
    private var inSpeech: Boolean = false

    /** Contador de chunks consecutivos con voz para evitar disparos por chasquidos */
    private var consecutiveSpeechChunks: Int = 0

    // Filtro DC / Paso-altos (estado)
    private var prevSample: Double = 0.0
    private var prevFiltered: Double = 0.0

    /**
     * Procesa un chunk de audio PCM 16-bit.
     *
     * @param pcmData Bytes crudos PCM (Little Endian, 16-bit signed)
     * @return [VadResult] con el estado de detección
     */
    fun processChunk(pcmData: ByteArray): VadResult {
        val rms = calculateFilteredRms(pcmData)
        val now = System.currentTimeMillis()

        // Umbrales adaptativos basados en el ruido ambiente actual
        // Para empezar a hablar, la voz debe superar el ruido de fondo por un margen claro
        val startThreshold = max(700.0, ambientNoiseFloor * 2.2 + 350.0)
        // Para continuar hablando, el umbral es menor (histéresis) para no cortar palabras suaves
        val continueThreshold = max(500.0, ambientNoiseFloor * 1.45 + 180.0)

        val threshold = if (inSpeech) continueThreshold else startThreshold
        val hasSpeechEnergy = rms > threshold

        if (hasSpeechEnergy) {
            consecutiveSpeechChunks++
            lastSpeechTimestamp = now
            isSpeechDetected = true

            if (!inSpeech) {
                // Requiere al menos 2 chunks consecutivos (~64ms) de energía sostenida para no dispararse por ruidos de impacto
                if (consecutiveSpeechChunks >= 2) {
                    inSpeech = true
                    speechStartTimestamp = now
                    return VadResult.SpeechStart(rms)
                } else {
                    return VadResult.Silence(rms)
                }
            }

            // Si se alcanza el tiempo máximo de frase, forzar fin de frase (evita quedarse atrapado en murmullo constante)
            if (now - speechStartTimestamp >= maxSpeechDurationMs) {
                inSpeech = false
                isSpeechDetected = false
                consecutiveSpeechChunks = 0
                return VadResult.SpeechEnd(rms, now - lastSpeechTimestamp)
            }

            return VadResult.SpeechContinue(rms)
        } else {
            consecutiveSpeechChunks = 0
        }

        // Si no hay voz activa, adaptamos suavemente el piso de ruido ambiental
        if (!inSpeech) {
            // Filtro exponencial para seguir el ruido del entorno sin saltos bruscos
            ambientNoiseFloor = ambientNoiseFloor * 0.96 + rms * 0.04
            // Mantener el piso dentro de límites razonables
            ambientNoiseFloor = min(2500.0, max(200.0, ambientNoiseFloor))
            return VadResult.Silence(rms)
        }

        // Estamos dentro de una frase, pero este chunk es silencio/pausa
        val silenceDuration = now - lastSpeechTimestamp
        val totalSpeechDuration = now - speechStartTimestamp

        if (silenceDuration >= silenceTimeoutMs) {
            inSpeech = false
            isSpeechDetected = false

            // Si la frase total fue más corta que el mínimo, se descarta como ruido
            if (totalSpeechDuration < minSpeechDurationMs) {
                return VadResult.Silence(rms)
            }

            // Fin de frase confirmado
            return VadResult.SpeechEnd(rms, silenceDuration)
        }

        // Pausa natural dentro de la frase
        return VadResult.SpeechPause(rms, silenceDuration)
    }

    /** Resetea el estado para una nueva sesión de escucha. */
    fun reset() {
        isSpeechDetected = false
        lastSpeechTimestamp = 0L
        speechStartTimestamp = 0L
        inSpeech = false
        consecutiveSpeechChunks = 0
        prevSample = 0.0
        prevFiltered = 0.0
    }

    /**
     * Calcula el RMS aplicando un filtro paso-altos (elimina DC y ruidos graves < 150Hz).
     * Esto evita que el ruido de motores, aire acondicionado o golpes lejanos distorsionen el RMS.
     */
    private fun calculateFilteredRms(pcmData: ByteArray): Double {
        if (pcmData.isEmpty()) return 0.0

        val shortBuffer = ByteBuffer.wrap(pcmData)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()

        val sampleCount = shortBuffer.remaining()
        if (sampleCount == 0) return 0.0

        var sumOfSquares = 0.0
        // Coeficiente paso-altos simple (~150Hz a 16kHz)
        val alpha = 0.95

        for (i in 0 until sampleCount) {
            val sample = shortBuffer.get(i).toDouble()
            // y[n] = alpha * (y[n-1] + x[n] - x[n-1])
            val filtered = alpha * (prevFiltered + sample - prevSample)
            prevSample = sample
            prevFiltered = filtered

            sumOfSquares += filtered * filtered
        }

        return sqrt(sumOfSquares / sampleCount)
    }
}

/**
 * Resultado del procesamiento VAD para un chunk de audio.
 */
sealed class VadResult(val rms: Double) {
    /** No hay voz — silencio o ruido ambiente por debajo del umbral */
    class Silence(rms: Double) : VadResult(rms)

    /** Se detectó el inicio de una frase */
    class SpeechStart(rms: Double) : VadResult(rms)

    /** La frase continúa activamente */
    class SpeechContinue(rms: Double) : VadResult(rms)

    /** Pausa breve dentro de una frase (aún no se considera fin) */
    class SpeechPause(rms: Double, val silenceDurationMs: Long) : VadResult(rms)

    /** Fin de frase confirmado — silencio suficiente o tiempo máximo alcanzado */
    class SpeechEnd(rms: Double, val silenceDurationMs: Long) : VadResult(rms)
}
