package com.fifo.voicepipeline.pipeline.session

import java.util.ArrayDeque
import kotlin.math.sqrt

/**
 * Resultado del filtrado y análisis de una trama PCM entrante.
 */
sealed class InboundFrameResult {
    /** La trama fue descartada por blindaje de eco (el altavoz está hablando y no supera el umbral de interrupción) */
    data object EchoSuppressed : InboundFrameResult()

    /** Trama de silencio o ruido ambiental bajo */
    data class Silent(val rms: Double) : InboundFrameResult()

    /** Trama de voz normal detectada */
    data class Speech(val pcmData: ByteArray, val rms: Double) : InboundFrameResult()

    /** Trama que supera el umbral de interrupción mientras Fifo habla (Barge-In) */
    data class BargeInDetected(val pcmData: ByteArray, val rms: Double) : InboundFrameResult()
}

/**
 * Submódulo profundo que procesa el flujo entrante de tramas PCM:
 * - Calcula el volumen RMS.
 * - Mantiene el búfer circular de pre-roll (15 tramas ~480ms) para no cortar "Fi-fo".
 * - Aplica blindaje de eco para evitar auto-interrupciones con el propio altavoz.
 * - Identifica la intención de Barge-In.
 */
class AudioInboundFilter(
    private val preRollCapacity: Int = 15,
    private val speechRmsThreshold: Double = 0.035,
    private val bargeInRmsThreshold: Double = 0.16
) {
    private val preRollBuffer = ArrayDeque<ByteArray>(preRollCapacity)
    private var consecutiveBargeInFrames = 0

    /**
     * Procesa una trama PCM cruda (16-bit Mono, Little Endian, 16kHz).
     * @param pcmData Bytes de audio recibidos.
     * @param isSpeakingNow Indica si el altavoz de Fifo está reproduciendo TTS actualmente.
     */
    fun processFrame(pcmData: ByteArray, isSpeakingNow: Boolean): InboundFrameResult {
        val rms = calculateRms(pcmData)

        // Alimentar búfer circular de pre-roll siempre que no estemos en auto-eco
        if (!isSpeakingNow) {
            if (preRollBuffer.size >= preRollCapacity) {
                preRollBuffer.removeFirst()
            }
            preRollBuffer.addLast(pcmData.copyOf())
        }

        // Blindaje contra eco del altavoz:
        if (isSpeakingNow) {
            // Para interrumpir a Fifo mientras habla, el usuario debe hablar con energía deliberada
            if (rms > bargeInRmsThreshold) {
                consecutiveBargeInFrames++
                if (consecutiveBargeInFrames >= 2) {
                    return InboundFrameResult.BargeInDetected(pcmData, rms)
                }
            } else {
                consecutiveBargeInFrames = 0
            }
            return InboundFrameResult.EchoSuppressed
        }

        consecutiveBargeInFrames = 0

        return if (rms >= speechRmsThreshold) {
            InboundFrameResult.Speech(pcmData, rms)
        } else {
            InboundFrameResult.Silent(rms)
        }
    }

    /**
     * Vuelca y concatena el contenido del búfer circular de pre-roll con la trama actual.
     * Garantiza que la palabra "Fifo" tenga sus primeros ~480ms intactos para el reconocedor.
     */
    fun extractPreRollAndClear(): ByteArray {
        val totalSize = preRollBuffer.sumOf { it.size }
        val combined = ByteArray(totalSize)
        var offset = 0
        while (preRollBuffer.isNotEmpty()) {
            val chunk = preRollBuffer.removeFirst()
            System.arraycopy(chunk, 0, combined, offset, chunk.size)
            offset += chunk.size
        }
        return combined
    }

    /**
     * Limpia el búfer de pre-roll (por ejemplo, al entrar en reposo).
     */
    fun clearPreRoll() {
        preRollBuffer.clear()
    }

    companion object {
        fun calculateRms(pcmData: ByteArray): Double {
            var sum = 0.0
            val sampleCount = pcmData.size / 2
            if (sampleCount == 0) return 0.0
            for (i in 0 until pcmData.size step 2) {
                val sample = (pcmData[i].toInt() and 0xFF) or (pcmData[i + 1].toInt() shl 8)
                val shortVal = sample.toShort()
                sum += (shortVal * shortVal)
            }
            val mean = sum / sampleCount
            val rawRms = sqrt(mean) / 32768.0
            return rawRms.coerceIn(0.0, 1.0)
        }
    }
}
