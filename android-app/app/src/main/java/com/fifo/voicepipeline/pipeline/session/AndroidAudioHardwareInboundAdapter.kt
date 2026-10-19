package com.fifo.voicepipeline.pipeline.session

import android.content.Context
import android.util.Log
import com.fifo.voicepipeline.audio.PhoneMicRecorder
import com.fifo.voicepipeline.network.BleVoiceClient
import com.fifo.voicepipeline.pipeline.MicSource

/**
 * Adaptador de producción de hardware de audio entrante.
 *
 * Multiplexa la captura de audio entre:
 * 1. Micrófono integrado del celular (PhoneMicRecorder con cancelación de eco/ruido).
 * 2. Conexión Bluetooth LE con el micrófono del ESP32-S3 (BleVoiceClient).
 *
 * Emite tramas normalizadas de audio PCM (16 kHz, 16-bit mono) a través de la costura AudioInboundAdapter.
 */
class AndroidAudioHardwareInboundAdapter(
    private val context: Context,
    val phoneMicRecorder: PhoneMicRecorder = PhoneMicRecorder(context = context),
    var bleClient: BleVoiceClient? = null
) : AudioInboundAdapter {

    companion object {
        private const val TAG = "AndroidAudioInbound"
    }

    private var frameListener: ((ByteArray) -> Unit)? = null
    var activeSource: MicSource = MicSource.PHONE
        private set

    override var isCapturing: Boolean = false
        private set

    override fun startCapture(onAudioFrame: (ByteArray) -> Unit) {
        frameListener = onAudioFrame
        isCapturing = true
        startSource(activeSource)
    }

    override fun stopCapture() {
        isCapturing = false
        phoneMicRecorder.stop()
        frameListener = null
    }

    fun setMicSource(source: MicSource) {
        if (activeSource == source && isCapturing) return
        activeSource = source
        if (isCapturing) {
            startSource(source)
        }
    }

    fun handleIncomingBleFrame(pcmChunk: ByteArray) {
        if (isCapturing && activeSource == MicSource.ESP32) {
            frameListener?.invoke(pcmChunk)
        }
    }

    fun setCallMode(inCall: Boolean) {
        phoneMicRecorder.switchToCallMode(inCall) { chunk ->
            if (isCapturing && activeSource == MicSource.PHONE) {
                frameListener?.invoke(chunk)
            }
        }
    }

    private fun startSource(source: MicSource) {
        when (source) {
            MicSource.PHONE -> {
                phoneMicRecorder.start { chunk ->
                    if (isCapturing && activeSource == MicSource.PHONE) {
                        frameListener?.invoke(chunk)
                    }
                }
                Log.i(TAG, "Captura iniciada desde micrófono del celular")
            }
            MicSource.ESP32 -> {
                phoneMicRecorder.stop()
                Log.i(TAG, "Captura direccionada a micrófono ESP32")
            }
        }
    }
}
