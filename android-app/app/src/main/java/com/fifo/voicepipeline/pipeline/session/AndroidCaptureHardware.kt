package com.fifo.voicepipeline.pipeline.session

import com.fifo.voicepipeline.audio.NativeSpeechRecognizer
import com.fifo.voicepipeline.audio.PhoneMicRecorder

/**
 * Implementación Android del puerto [CaptureHardware].
 *
 * `PhoneMicRecorder` se mantiene como detalle interno: el [CaptureArbiter] es el único
 * que decide cuándo iniciarlo, detenerlo o cambiar a modo llamada.
 *
 * Entre `PHONE_NORMAL` y `PHONE_CALL` el árbitro no llama a [stopPhone]; llama a
 * [startPhone] y este adaptador usa `switchToCallMode` para reconfigurar en una sola
 * operación.
 */
internal class AndroidCaptureHardware(
    private val phoneMicRecorder: PhoneMicRecorder,
    private val nativeRecognizer: NativeSpeechRecognizer? = null
) : CaptureHardware {

    override fun startPhone(callMode: Boolean, onFrame: (ByteArray) -> Unit) {
        if (phoneMicRecorder.isRecording) {
            phoneMicRecorder.switchToCallMode(callMode, onChunk = onFrame)
        } else {
            phoneMicRecorder.start(forceCallSource = callMode, onChunk = onFrame)
        }
    }

    override fun stopPhone() {
        phoneMicRecorder.stop()
    }

    override fun startNativeRecognizer() {
        nativeRecognizer?.startContinuousListening()
    }

    override fun stopNativeRecognizer() {
        nativeRecognizer?.stop()
    }
}
