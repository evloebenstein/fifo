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
    private val minSpeechDurationMs: Long = 300L   // Frases menores a 300ms se consideran ruido
) {
    /** Piso de ruido ambiental estimado dinámicamente */
    var ambientNoiseFloor: Double = 70.0
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
    fun processChunk(pcmData: ByteArray, isSpeaking: Boolean = false): VadResult {
        val rms = calculateFilteredRms(pcmData)
        val now = System.currentTimeMillis()

        // Umbrales adaptativos con piso calibrado:
        // Si Fifo está hablando por parlante, el umbral de arranque sube para captar la voz directa del usuario
        // por encima de la locución del altavoz sin falsos disparos.
        val baseStart = if (isSpeaking) 420.0 else 220.0
        val baseContinue = if (isSpeaking) 280.0 else 150.0

        val startThreshold = max(baseStart, ambientNoiseFloor * 1.35 + 35.0)
        val continueThreshold = max(baseContinue, ambientNoiseFloor * 1.15 + 15.0)

        val threshold = if (inSpeech) continueThreshold else startThreshold
        val hasSpeechEnergy = rms > threshold

        // Tiempos máximos de corte:
        // Si Fifo está hablando (Barge-in), no esperar 8 segundos ni 800ms de silencio:
        // Un comando de corte ("Fifo silencio", "cállate", "basta") dura ~500-1000ms.
        // Al detectar 1.1s de voz sostenida o 300ms de pausa, cortar inmediatamente para silenciar a Fifo al instante.
        val effectiveMaxSpeechMs = if (isSpeaking) 1100L else maxSpeechDurationMs
        val effectiveSilenceTimeoutMs = if (isSpeaking) 300L else silenceTimeoutMs

        if (hasSpeechEnergy) {
            consecutiveSpeechChunks++
            lastSpeechTimestamp = now
            isSpeechDetected = true

            if (!inSpeech) {
                // Si Fifo está hablando, requerimos 3 chunks (~96ms) para descartar picos del altavoz
                val requiredChunks = if (isSpeaking) 3 else 2
                if (consecutiveSpeechChunks >= requiredChunks) {
                    inSpeech = true
                    speechStartTimestamp = now
                    return VadResult.SpeechStart(rms)
                } else {
                    return VadResult.Silence(rms)
                }
            }

            // Si se alcanza el tiempo máximo de frase (o ventana rápida de barge-in), forzar fin de frase
            if (now - speechStartTimestamp >= effectiveMaxSpeechMs) {
                inSpeech = false
                isSpeechDetected = false
                consecutiveSpeechChunks = 0
                return VadResult.SpeechEnd(rms, now - lastSpeechTimestamp)
            }

            return VadResult.SpeechContinue(rms)
        } else {
            consecutiveSpeechChunks = 0
        }

        // Si no hay voz activa, adaptamos suavemente el piso de ruido ambiental (solo si Fifo no está hablando)
        if (!inSpeech) {
            if (!isSpeaking) {
                // Filtro exponencial para seguir el ruido del entorno sin saltos bruscos
                ambientNoiseFloor = ambientNoiseFloor * 0.96 + rms * 0.04
                // Mantener el piso dentro de límites razonables
                ambientNoiseFloor = min(800.0, max(25.0, ambientNoiseFloor))
            }
            return VadResult.Silence(rms)
        }

        // Estamos dentro de una frase, pero este chunk es silencio/pausa
        val silenceDuration = now - lastSpeechTimestamp
        val totalSpeechDuration = now - speechStartTimestamp

        if (silenceDuration >= effectiveSilenceTimeoutMs) {
            inSpeech = false
            isSpeechDetected = false

            // Si la frase total fue más corta que el mínimo, se descarta como ruido
            val effectiveMinSpeechMs = if (isSpeaking) 200L else minSpeechDurationMs
            if (totalSpeechDuration < effectiveMinSpeechMs) {
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
