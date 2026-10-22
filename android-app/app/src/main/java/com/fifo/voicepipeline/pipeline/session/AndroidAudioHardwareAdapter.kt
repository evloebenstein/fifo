package com.fifo.voicepipeline.pipeline.session

import android.content.Context
import com.fifo.voicepipeline.audio.AndroidTtsSpeaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Adaptador de producción de salida de audio (Android TTS).
 *
 * Es deliberadamente solo de salida: la captura pertenece a [CaptureArbiter] y a
 * [AndroidAudioHardwareInboundAdapter]. Así nunca hay dos dueños del micrófono.
 *
 * No toca `AudioManager` ni el enrutamiento de llamadas: eso vive en `TelephonyPort`.
 */
internal class AndroidAudioHardwareAdapter(
    context: Context,
    private val scope: CoroutineScope
) : AudioOutboundAdapter {

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

    fun shutdown() {
        stop()
        ttsSpeaker.release()
    }
}
