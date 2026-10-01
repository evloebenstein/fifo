package com.fifo.voicepipeline.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Buffer circular que acumula PCM crudo del micrófono.
 * Thread-safe para uso entre el hilo del WebSocket y el hilo de procesamiento.
 *
 * Cuando VAD detecta fin de frase, se exporta como WAV para enviar a STT.
 */
class PcmBuffer(private val maxBytes: Int = AudioConfig.MAX_RECORDING_BYTES) {

    private val buffer = ByteArrayOutputStream(maxBytes)
    private val lock = Any()

    /** Agrega bytes PCM crudos al buffer. */
    fun append(data: ByteArray) {
        synchronized(lock) {
            if (buffer.size() + data.size <= maxBytes) {
                buffer.write(data)
            }
            // Si se excede el máximo, descartamos (protección contra grabaciones infinitas)
        }
    }

    /** Retorna el tamaño actual del buffer en bytes. */
    fun size(): Int = synchronized(lock) { buffer.size() }

    /** ¿Hay datos en el buffer? */
    fun hasData(): Boolean = synchronized(lock) { buffer.size() > 0 }

    /**
     * Exporta el contenido del buffer como archivo WAV (header + PCM).
     * Necesario porque la API de Whisper espera formato WAV.
     */
    fun toWav(): ByteArray {
        synchronized(lock) {
            val pcmData = buffer.toByteArray()
            return createWavBytes(pcmData)
        }
    }

    /** Retorna los bytes PCM crudos sin header WAV. */
    fun toPcm(): ByteArray {
        synchronized(lock) {
            return buffer.toByteArray()
        }
    }

    /** Limpia el buffer para la próxima grabación. */
    fun clear() {
        synchronized(lock) {
            buffer.reset()
        }
    }

    /**
     * Crea un archivo WAV válido anteponiendo un header de 44 bytes al PCM.
     * Formato: PCM 16-bit, mono, 16kHz — sin compresión.
     */
    private fun createWavBytes(pcmData: ByteArray): ByteArray {
        val totalDataLen = pcmData.size + 36
        val byteRate = AudioConfig.SAMPLE_RATE * AudioConfig.CHANNELS * AudioConfig.BYTES_PER_SAMPLE
        val blockAlign = AudioConfig.CHANNELS * AudioConfig.BYTES_PER_SAMPLE

        val header = ByteBuffer.allocate(AudioConfig.WAV_HEADER_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        header.put("RIFF".toByteArray())
        header.putInt(totalDataLen)
        header.put("WAVE".toByteArray())

        // fmt chunk
        header.put("fmt ".toByteArray())
        header.putInt(16)                            // Chunk size
        header.putShort(1)                           // PCM format
        header.putShort(AudioConfig.CHANNELS.toShort())
        header.putInt(AudioConfig.SAMPLE_RATE)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(AudioConfig.BITS_PER_SAMPLE.toShort())

        // data chunk
        header.put("data".toByteArray())
        header.putInt(pcmData.size)

        return header.array() + pcmData
    }
}
