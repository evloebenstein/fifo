package com.fifo.voicepipeline.pipeline.session

import com.fifo.voicepipeline.audio.PcmBuffer
import com.fifo.voicepipeline.audio.VadProcessor
import com.fifo.voicepipeline.audio.VadResult
import kotlin.math.sqrt

/**
 * Resultado de segmentar una trama de audio entrante.
 *
 * [rms] está normalizado a 0..1 y se usa solo para la boca/OLED. Nunca decide
 * cortar una locución: eso se hace después, cuando hay transcripción.
 */
sealed class SpeechSegment {
    abstract val rms: Double
    data class Silence(override val rms: Double) : SpeechSegment()
    data class Started(override val rms: Double) : SpeechSegment()
    data class Continuing(override val rms: Double) : SpeechSegment()
    data class Ended(val wavData: ByteArray, override val rms: Double) : SpeechSegment()
}

/**
 * Segmentador de voz con VAD adaptativo y pre-roll.
 *
 * Mantiene las últimas ~480 ms de audio para que la primera sílaba de "Fifo"
 * no se pierda. Cuando el VAD confirma el fin de frase, entrega un WAV completo
 * listo para STT.
 *
 * No conoce Android, TTS, llamadas ni UI. Es el dueño único del VAD y del
 * pre-roll en el camino nuevo; el facade no debe tener su propia copia.
 */
class SpeechSegmenter(
    private val vad: VadProcessor = VadProcessor(),
    private val pcmBuffer: PcmBuffer = PcmBuffer(),
    private val preRollCapacity: Int = 15
) {
    private val preRollBuffer = ArrayDeque<ByteArray>(preRollCapacity)

    fun processFrame(pcmData: ByteArray, isSpeakingNow: Boolean): SpeechSegment {
        if (pcmData.isEmpty()) return SpeechSegment.Silence(0.0)

        synchronized(preRollBuffer) {
            if (preRollBuffer.size >= preRollCapacity) {
                preRollBuffer.removeFirst()
            }
            preRollBuffer.addLast(pcmData.copyOf())
        }

        return when (vad.processChunk(pcmData, isSpeaking = isSpeakingNow)) {
            is VadResult.SpeechStart -> {
                pcmBuffer.clear()
                synchronized(preRollBuffer) {
                    while (preRollBuffer.isNotEmpty()) {
                        pcmBuffer.append(preRollBuffer.removeFirst())
                    }
                }
                SpeechSegment.Started(calculateRms(pcmData))
            }

            is VadResult.SpeechContinue, is VadResult.SpeechPause -> {
                pcmBuffer.append(pcmData)
                SpeechSegment.Continuing(calculateRms(pcmData))
            }

            is VadResult.SpeechEnd -> {
                pcmBuffer.append(pcmData)
                val wav = pcmBuffer.toWav()
                pcmBuffer.clear()
                clearPreRoll()
                SpeechSegment.Ended(wav, calculateRms(pcmData))
            }

            is VadResult.Silence -> SpeechSegment.Silence(calculateRms(pcmData))
        }
    }

    fun reset() {
        vad.reset()
        pcmBuffer.clear()
        clearPreRoll()
    }

    private fun clearPreRoll() {
        synchronized(preRollBuffer) {
            preRollBuffer.clear()
        }
    }

    companion object {
        /** RMS normalizado de una trama PCM 16-bit mono Little Endian. */
        fun calculateRms(pcmData: ByteArray): Double {
            val sampleCount = pcmData.size / 2
            if (sampleCount == 0) return 0.0

            var sum = 0.0
            for (i in 0 until sampleCount * 2 step 2) {
                val sample = (pcmData[i].toInt() and 0xFF) or (pcmData[i + 1].toInt() shl 8)
                val shortVal = sample.toShort()
                sum += shortVal.toDouble() * shortVal.toDouble()
            }
            return (sqrt(sum / sampleCount) / 32768.0).coerceIn(0.0, 1.0)
        }
    }
}
