package com.fifo.voicepipeline.telephony

import com.fifo.voicepipeline.ui.model.IncomingCallInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fase de una llamada, independiente de si es celular o VoIP.
 */
enum class CallPhase {
    NONE,
    RINGING,
    ACTIVE
}

/**
 * Fotografía inmutable del estado de llamada.
 *
 * `callerName` puede ser un nombre de contacto, un número o un texto genérico.
 * La identificación contra el backend es responsabilidad de `CallerContextProvider`
 * (paso 3), no de este puerto.
 */
data class CallSnapshot(
    val phase: CallPhase = CallPhase.NONE,
    val callerName: String? = null,
    val phoneNumber: String? = null
) {
    val hasCall: Boolean
        get() = phase != CallPhase.NONE

    val isRinging: Boolean
        get() = phase == CallPhase.RINGING
}

/**
 * Proyección del estado de llamada que ya conoce el teléfono.
 */
fun IncomingCallInfo?.toCallSnapshot(): CallSnapshot {
    if (this == null) return CallSnapshot()
    return CallSnapshot(
        phase = if (isRinging) CallPhase.RINGING else CallPhase.ACTIVE,
        callerName = callerName,
        phoneNumber = phoneNumber
    )
}

/**
 * Costura de telefonía (TelephonyPort).
 *
 * Mantiene la telefonía FUERA de `VoiceSession`: la sesión observa [callState] y el
 * `CaptureArbiter` recibe `callActive`, pero no contesta, cuelga ni toca `AudioManager`.
 *
 * El camino legacy sigue usando `FifoCallManager` directamente hasta el flag de una
 * release. La implementación Android de este puerto es la única que debe construir
 * `FifoCallManager` en el camino nuevo.
 */
interface TelephonyPort {
    val callState: StateFlow<CallSnapshot>

    /** Relee el estado del sistema y actualiza [callState]. */
    fun refresh(): CallSnapshot

    /** Contesta la llamada entrante. Devuelve false si no pudo hacerlo. */
    fun answer(): Boolean

    /** Cuelga o rechaza la llamada. Devuelve false si no pudo hacerlo. */
    fun hangup(): Boolean

    /** Empieza a escuchar telefonía y enlaza las acciones del repositorio. */
    fun start()

    /** Deja de escuchar y desenlaza las acciones del repositorio. */
    fun stop()
}

/**
 * Implementación en memoria para pruebas de JVM y para el camino de sesión sin telefonía real.
 */
class InMemoryTelephonyPort(
    initial: CallSnapshot = CallSnapshot()
) : TelephonyPort {

    private val _callState = MutableStateFlow(initial)
    override val callState: StateFlow<CallSnapshot> = _callState.asStateFlow()

    var answerResult: Boolean = true
    var hangupResult: Boolean = true
    var answerInvocations: Int = 0
        private set
    var hangupInvocations: Int = 0
        private set
    var isStarted: Boolean = false
        private set

    override fun refresh(): CallSnapshot = _callState.value

    override fun answer(): Boolean {
        answerInvocations++
        if (answerResult && _callState.value.phase == CallPhase.RINGING) {
            _callState.value = _callState.value.copy(phase = CallPhase.ACTIVE)
        }
        return answerResult
    }

    override fun hangup(): Boolean {
        hangupInvocations++
        if (hangupResult) {
            _callState.value = CallSnapshot()
        }
        return hangupResult
    }

    override fun start() {
        isStarted = true
    }

    override fun stop() {
        isStarted = false
    }

    /** Simula un evento de telefonía para pruebas. */
    fun emit(snapshot: CallSnapshot) {
        _callState.value = snapshot
    }
}
