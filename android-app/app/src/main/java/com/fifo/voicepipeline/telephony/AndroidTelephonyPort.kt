package com.fifo.voicepipeline.telephony

import android.content.Context
import com.fifo.voicepipeline.data.FifoDataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Implementación Android del puerto de telefonía.
 *
 * Es la única clase del camino nuevo que construye `FifoCallManager`. El manager queda
 * como detalle interno: la sesión solo ve [callState], [answer] y [hangup].
 *
 * `FifoCallManager` ya no instala callbacks globales en su constructor; este puerto los
 * enlaza explícitamente en [start] y los desenlaza en [stop]. Eso evita que un manager
 * desechable (por ejemplo el del BroadcastReceiver) pise las acciones del repositorio.
 */
class AndroidTelephonyPort(
    context: Context,
    private val scope: CoroutineScope
) : TelephonyPort {

    private val _callState = MutableStateFlow(CallSnapshot())
    override val callState: StateFlow<CallSnapshot> = _callState.asStateFlow()

    private var started = false
    private var repositoryJob: Job? = null

    private val callManager = FifoCallManager(
        context = context,
        onAnnounceCall = { callerName, phoneNumber ->
            publish(
                CallSnapshot(
                    phase = CallPhase.RINGING,
                    callerName = callerName,
                    phoneNumber = phoneNumber
                )
            )
        },
        onCommunicationModeChanged = { active ->
            if (active) {
                // Si ya hay una llamada registrada, el repositorio (OFFHOOK) la marcará activa.
                // Aquí solo cubrimos llamadas que Android detecta sin entrada en el repositorio.
                if (_callState.value.phase == CallPhase.NONE) {
                    publish(CallSnapshot(phase = CallPhase.ACTIVE))
                }
            } else if (FifoDataRepository.incomingCall.value == null) {
                publish(CallSnapshot())
            }
        }
    )

    override fun start() {
        if (started) return
        started = true
        callManager.bindRepositoryActions()
        callManager.startListening()
        repositoryJob = scope.launch {
            FifoDataRepository.incomingCall.collect { info ->
                val next = info.toCallSnapshot()
                if (next.phase == CallPhase.NONE && callManager.isCommunicationModeActive()) {
                    // Android puede estar en llamada antes de que el repositorio reciba OFFHOOK.
                    if (_callState.value.phase == CallPhase.NONE) {
                        publish(CallSnapshot(phase = CallPhase.ACTIVE))
                    }
                } else {
                    publish(next)
                }
            }
        }
        refresh()
    }

    override fun stop() {
        if (!started) return
        started = false
        repositoryJob?.cancel()
        repositoryJob = null
        callManager.stopListening()
        _callState.value = CallSnapshot()
    }

    override fun refresh(): CallSnapshot {
        val info = FifoDataRepository.incomingCall.value
        val next = when {
            info != null -> info.toCallSnapshot()
            callManager.isCommunicationModeActive() -> CallSnapshot(phase = CallPhase.ACTIVE)
            else -> CallSnapshot()
        }
        publish(next)
        return _callState.value
    }

    override fun answer(): Boolean {
        val handled = FifoDataRepository.answerCurrentCall()
        refresh()
        return handled
    }

    override fun hangup(): Boolean {
        val handled = FifoDataRepository.hangupCurrentCall()
        refresh()
        return handled
    }

    private fun publish(next: CallSnapshot) {
        if (next != _callState.value) {
            _callState.value = next
        }
    }
}
