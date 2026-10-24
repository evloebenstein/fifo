package com.fifo.voicepipeline.telephony

import com.fifo.voicepipeline.ui.model.IncomingCallInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Estado compartido de la llamada entrante.
 *
 * Es un contenedor puro: no contesta, no cuelga y no toca `AudioManager`. Solo
 * guarda la llamada visible, la última llamada activa y los callbacks que el
 * dueño real de la telefonía (`FifoCallManager` / `AndroidTelephonyPort`)
 * enlaza con `bindRepositoryActions()`.
 *
 * `FifoDataRepository` lo expone mediante delegación para no cambiar a sus
 * consumidores (UI, skills, receptor y puerto de telefonía).
 */
class CallStateCoordinator {

    private val _incomingCall = MutableStateFlow<IncomingCallInfo?>(null)
    val incomingCall: StateFlow<IncomingCallInfo?> = _incomingCall.asStateFlow()

    var onAnswerCallAction: (() -> Boolean)? = null
    var onHangupCallAction: (() -> Boolean)? = null

    var lastActiveCall: IncomingCallInfo? = null
        private set

    fun setIncomingCall(call: IncomingCallInfo?) {
        if (call != null) {
            lastActiveCall = call
        }
        _incomingCall.value = call
    }

    fun answerCurrentCall(): Boolean {
        val handled = onAnswerCallAction?.invoke() ?: false
        if (handled) {
            _incomingCall.value = _incomingCall.value?.copy(isRinging = false)
            _incomingCall.value?.let { lastActiveCall = it }
        }
        return handled
    }

    fun hangupCurrentCall(): Boolean {
        _incomingCall.value?.let { lastActiveCall = it }
        val handled = onHangupCallAction?.invoke() ?: false
        _incomingCall.value = null
        return handled
    }
}
