package com.fifo.voicepipeline.pipeline.session

import android.content.Context
import com.fifo.voicepipeline.audio.AndroidTtsSpeaker
import com.fifo.voicepipeline.audio.RobotSpeakerRoute
import com.fifo.voicepipeline.audio.RobotTtsSpeaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Adaptador de producción de salida de audio.
 *
 * Es deliberadamente solo de salida: la captura pertenece a [CaptureArbiter] y a
 * [AndroidAudioHardwareInboundAdapter]. Así nunca hay dos dueños del micrófono.
 *
 * La voz sale por el parlante del robot cuando hay enlace BLE con el hardware y
 * por el parlante del teléfono cuando no lo hay. Es una sola decisión, tomada en
 * cada locución: nunca suenan los dos a la vez.
 *
 * No toca `AudioManager` ni el enrutamiento de llamadas: eso vive en `TelephonyPort`.
 */
internal class AndroidAudioHardwareAdapter(
    context: Context,
    private val scope: CoroutineScope,
    private val robotRoute: RobotSpeakerRoute? = null
) : AudioOutboundAdapter {

    private var finishCallback: (() -> Unit)? = null
    private var rmsSimulationJob: Job? = null

    private val handleFinish: () -> Unit = {
        rmsSimulationJob?.cancel()
        finishCallback?.invoke()
        finishCallback = null
    }

    private val phoneSpeaker = AndroidTtsSpeaker(
        context = context,
        onStart = {
            // Inicia simulación de RMS dinámico durante la locución para animar FifoFace
        },
        onDone = handleFinish,
        onError = { handleFinish() }
    )

    private val robotSpeaker: RobotTtsSpeaker? = robotRoute?.let { route ->
        RobotTtsSpeaker(
            context = context,
            scope = scope,
            route = route,
            onStart = {},
            onDone = handleFinish,
            onError = { handleFinish() }
        )
    }

    override val isSpeaking: Boolean
        get() = phoneSpeaker.isSpeaking || robotSpeaker?.isSpeaking == true

    override fun speak(
        text: String,
        onRmsUpdate: ((Double) -> Unit)?,
        onFinish: (() -> Unit)?
    ) {
        finishCallback = onFinish

        val speakThroughRobot = robotRoute?.isAvailable == true && robotSpeaker != null
        if (speakThroughRobot) {
            phoneSpeaker.stop()
            robotSpeaker?.speak(text)
        } else {
            robotSpeaker?.stop()
            phoneSpeaker.speak(text)
        }

        // Mientras habla, emite pulsos de RMS para animar la boca de FifoFace
        rmsSimulationJob?.cancel()
        rmsSimulationJob = scope.launch(Dispatchers.Main) {
            while (isSpeaking) {
                val simulatedRms = (0.35 + Math.random() * 0.45).coerceIn(0.1, 0.9)
                onRmsUpdate?.invoke(simulatedRms)
                delay(60L)
            }
            onRmsUpdate?.invoke(0.0)
        }
    }

    override fun stop() {
        rmsSimulationJob?.cancel()
        phoneSpeaker.stop()
        robotSpeaker?.stop()
        finishCallback = null
    }

    fun shutdown() {
        stop()
        phoneSpeaker.release()
        robotSpeaker?.release()
    }
}
