package com.fifo.voicepipeline.pipeline.session

/**
 * Costura de audio (AudioSeam) según los principios de Feathers & Ousterhout.
 *
 * Desacopla la captura y emisión física de audio del orquestador conversacional.
 * Dos adaptadores satisfacen esta costura:
 * 1. AndroidAudioHardwareAdapter: Hardware real en producción (BLE ESP32, Micrófono celular, Android TTS).
 * 2. InMemoryAudioAdapter: Simulación en memoria para pruebas unitarias instantáneas sin emulador.
 */

/**
 * Adaptador de entrada de audio (Captura física o simulada de tramas PCM).
 */
interface AudioInboundAdapter {
    fun startCapture(onAudioFrame: (ByteArray) -> Unit)
    fun stopCapture()
    val isCapturing: Boolean
}

/**
 * Adaptador de salida de audio (Locución TTS física o simulada).
 */
interface AudioOutboundAdapter {
    fun speak(
        text: String,
        onRmsUpdate: ((Double) -> Unit)? = null,
        onFinish: (() -> Unit)? = null
    )
    fun stop()
    val isSpeaking: Boolean
}

/**
 * Adaptador de prueba en memoria para la entrada de audio.
 */
class InMemoryAudioInboundAdapter : AudioInboundAdapter {
    private var callback: ((ByteArray) -> Unit)? = null
    override var isCapturing: Boolean = false
        private set

    override fun startCapture(onAudioFrame: (ByteArray) -> Unit) {
        callback = onAudioFrame
        isCapturing = true
    }

    override fun stopCapture() {
        isCapturing = false
        callback = null
    }

    /** Simula la llegada de una trama PCM desde el micrófono o BLE */
    fun emitAudioFrame(pcmData: ByteArray) {
        if (isCapturing) {
            callback?.invoke(pcmData)
        }
    }
}

/**
 * Adaptador de prueba en memoria para la salida de audio.
 */
class InMemoryAudioOutboundAdapter : AudioOutboundAdapter {
    val spokenUtterances = mutableListOf<String>()
    var lastRmsReported: Double = 0.0
    private var pendingFinishCallback: (() -> Unit)? = null
    override var isSpeaking: Boolean = false
        private set

    override fun speak(
        text: String,
        onRmsUpdate: ((Double) -> Unit)?,
        onFinish: (() -> Unit)?
    ) {
        spokenUtterances.add(text)
        isSpeaking = true
        pendingFinishCallback = onFinish
        onRmsUpdate?.invoke(0.65)
        lastRmsReported = 0.65
    }

    fun finishCurrentSpeech(explicitCallback: (() -> Unit)? = null) {
        isSpeaking = false
        val cb = explicitCallback ?: pendingFinishCallback
        pendingFinishCallback = null
        cb?.invoke()
    }

    override fun stop() {
        isSpeaking = false
        pendingFinishCallback = null
    }
}
