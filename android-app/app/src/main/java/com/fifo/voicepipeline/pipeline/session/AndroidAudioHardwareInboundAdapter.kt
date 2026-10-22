package com.fifo.voicepipeline.pipeline.session

import android.content.Context
import com.fifo.voicepipeline.audio.PhoneMicRecorder
import com.fifo.voicepipeline.pipeline.MicSource

/**
 * Adaptador de producción de hardware de audio entrante.
 *
 * Toda la decisión de fuente vive en [CaptureArbiter]. Este adaptador solo traduce
 * eventos Android/BLE a un [CaptureEnvironment] y publica las tramas resultantes.
 *
 * No se debe llamar a `PhoneMicRecorder` desde fuera de este adaptador/árbitro.
 */
internal class AndroidAudioHardwareInboundAdapter(
    context: Context,
    private val captureHardware: CaptureHardware = AndroidCaptureHardware(
        PhoneMicRecorder(context = context)
    )
) : AudioInboundAdapter {

    private var frameListener: ((ByteArray) -> Unit)? = null
    private val arbiter = CaptureArbiter(captureHardware) { frame ->
        frameListener?.invoke(frame)
    }

    private var environment = CaptureEnvironment()

    var activeSource: MicSource = MicSource.ESP32
        private set

    val route: CaptureRoute
        get() = arbiter.route.value

    override var isCapturing: Boolean = false
        private set

    override fun startCapture(onAudioFrame: (ByteArray) -> Unit) {
        frameListener = onAudioFrame
        isCapturing = true
        applyEnvironment()
    }

    override fun stopCapture() {
        isCapturing = false
        arbiter.shutdown()
        frameListener = null
    }

    fun setMicSource(source: MicSource) {
        activeSource = source
        environment = environment.copy(preferredSource = source)
        applyEnvironment()
    }

    fun setBleConnected(connected: Boolean) {
        environment = environment.copy(bleConnected = connected)
        applyEnvironment()
    }

    /**
     * Activa/desactiva el modo llamada. Es idempotente: timbrado y llamada activa
     * comparten la misma ruta y no reconfiguran el micrófono.
     */
    fun setCallMode(inCall: Boolean) {
        environment = environment.copy(callActive = inCall)
        applyEnvironment()
    }

    fun setMicMuted(muted: Boolean) {
        environment = environment.copy(micMuted = muted)
        applyEnvironment()
    }

    fun handleIncomingBleFrame(pcmData: ByteArray) {
        if (!isCapturing) return
        arbiter.onBleFrame(pcmData)
    }

    private fun applyEnvironment() {
        if (isCapturing) {
            arbiter.update(environment)
        } else {
            arbiter.shutdown()
        }
    }
}
