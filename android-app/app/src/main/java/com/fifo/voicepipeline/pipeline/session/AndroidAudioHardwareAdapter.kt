package com.fifo.voicepipeline.pipeline.session

import android.content.Context
import com.fifo.voicepipeline.audio.AndroidTtsSpeaker
import com.fifo.voicepipeline.audio.PhoneMicRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Adaptador de producción que conecta el hardware real de Android con la costura AudioSeam.
 */
class AndroidAudioHardwareAdapter(
    private val context: Context,
    private val scope: CoroutineScope
) : AudioInboundAdapter, AudioOutboundAdapter {

    // ── Salida (TTS) ─────────────────────────────────
    private var finishCallback: (() -> Unit)? = null
    private var rmsSimulationJob: Job? = null

    private val ttsSpeaker = AndroidTtsSpeaker(
        context = context,
        onStart = {
            // Inicia simulación de RMS dinámico durante la locución para animar FifoFace
        },
        onDone = {
            rmsSimulationJob?.cancel()
            finishCallback?.invoke()
            finishCallback = null
        },
        onError = {
            rmsSimulationJob?.cancel()
            finishCallback?.invoke()
            finishCallback = null
        }
    )

    override val isSpeaking: Boolean
        get() = ttsSpeaker.isSpeaking

    override fun speak(
        text: String,
        onRmsUpdate: ((Double) -> Unit)?,
        onFinish: (() -> Unit)?
    ) {
        finishCallback = onFinish
        ttsSpeaker.speak(text)

        // Mientras habla, emite pulsos de RMS para animar la boca de FifoFace
        rmsSimulationJob?.cancel()
        rmsSimulationJob = scope.launch(Dispatchers.Main) {
            while (ttsSpeaker.isSpeaking) {
                val simulatedRms = (0.35 + Math.random() * 0.45).coerceIn(0.1, 0.9)
                onRmsUpdate?.invoke(simulatedRms)
                delay(60L)
            }
            onRmsUpdate?.invoke(0.0)
        }
    }

    override fun stop() {
        rmsSimulationJob?.cancel()
        ttsSpeaker.stop()
        finishCallback = null
    }

    // ── Entrada (Micrófono) ──────────────────────────
    private var phoneRecorder: PhoneMicRecorder? = null
    override var isCapturing: Boolean = false
        private set

    override fun startCapture(onAudioFrame: (ByteArray) -> Unit) {
        if (isCapturing) return
        isCapturing = true
        phoneRecorder = PhoneMicRecorder(context = context)
        phoneRecorder?.start(onChunk = onAudioFrame)
    }

    override fun stopCapture() {
        isCapturing = false
        phoneRecorder?.stop()
        phoneRecorder = null
    }

    fun shutdown() {
        stop()
        stopCapture()
        ttsSpeaker.release()
    }
}
