package com.fifo.voicepipeline.pipeline.session

import com.fifo.voicepipeline.pipeline.MicSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ruta efectiva de captura de audio.
 *
 * Solo puede haber una ruta activa a la vez. [BLE_MIC] no enciende hardware local:
 * el audio llega por notificaciones BLE y el árbitro decide si se reenvía.
 */
enum class CaptureRoute {
    STOPPED,
    PHONE_NORMAL,
    PHONE_CALL,
    BLE_MIC,
    NATIVE_RECOGNIZER
}

/**
 * Entradas que determinan la ruta de captura. Es un value object puro: no toca Android.
 *
 * [callActive] es true tanto en timbrado como en llamada en curso. Ambos casos usan
 * la misma ruta [CaptureRoute.PHONE_CALL], de modo que la transición timbrado → activo
 * no reconfigura el micrófono.
 */
data class CaptureEnvironment(
    val micMuted: Boolean = false,
    val callActive: Boolean = false,
    val bleConnected: Boolean = false,
    val preferredSource: MicSource = MicSource.ESP32,
    val hasCloudStt: Boolean = true,
    val nativeRecognizerAvailable: Boolean = false
)

/**
 * Puerto de hardware que el [CaptureArbiter] controla.
 *
 * Es la única costura que toca `PhoneMicRecorder` y el reconocedor nativo. En pruebas
 * se sustituye por un fake que cuenta transiciones.
 */
interface CaptureHardware {
    fun startPhone(callMode: Boolean, onFrame: (ByteArray) -> Unit)
    fun stopPhone()
    fun startNativeRecognizer()
    fun stopNativeRecognizer()
}

/**
 * Árbitro único de la captura de audio.
 *
 * Invariantes:
 * 1. Es el único lugar que decide entre micrófono del celular, BLE y reconocedor nativo.
 * 2. Solo reconfigura hardware cuando cambia la ruta efectiva. Eventos repetidos
 *    (timbrado → activo, mismo estado de BLE, mismo mute) no vuelven a tocar el micrófono.
 * 3. Durante una llamada la ruta es siempre [CaptureRoute.PHONE_CALL]: nunca se inicia
 *    el reconocedor nativo, porque Android lo bloquea con ERROR_AUDIO en llamadas.
 * 4. Las tramas tardías del micrófono se descartan si la ruta ya cambió.
 */
class CaptureArbiter(
    private val hardware: CaptureHardware,
    private val onFrame: (ByteArray) -> Unit
) {
    private val _route = MutableStateFlow(CaptureRoute.STOPPED)
    val route: StateFlow<CaptureRoute> = _route.asStateFlow()

    var environment: CaptureEnvironment = CaptureEnvironment()
        private set

    /**
     * Aplica un nuevo entorno y devuelve la ruta efectiva resultante.
     */
    fun update(next: CaptureEnvironment): CaptureRoute {
        environment = next
        val target = targetRoute(next)
        if (target != _route.value) {
            applyRoute(target)
        }
        return _route.value
    }

    /**
     * Reenvía audio BLE solo si la fuente efectiva lo permite.
     *
     * - Durante una llamada se acepta BLE además del micrófono del celular (comportamiento legacy).
     * - Fuera de llamada, BLE solo se acepta si la fuente preferida es ESP32.
     * - Nunca se reenvía con el micrófono silenciado ni con la captura detenida.
     */
    fun onBleFrame(pcmData: ByteArray) {
        if (_route.value == CaptureRoute.STOPPED) return
        val env = environment
        if (env.micMuted || !env.bleConnected) return
        val allowed = env.callActive || env.preferredSource == MicSource.ESP32
        if (allowed) onFrame(pcmData)
    }

    /**
     * Detiene la captura local y deja el árbitro en [CaptureRoute.STOPPED].
     */
    fun shutdown() {
        val previous = _route.value
        // Detener primero la ruta lógica para que cualquier trama en vuelo se descarte.
        _route.value = CaptureRoute.STOPPED
        when (previous) {
            CaptureRoute.PHONE_NORMAL, CaptureRoute.PHONE_CALL -> hardware.stopPhone()
            CaptureRoute.NATIVE_RECOGNIZER -> hardware.stopNativeRecognizer()
            CaptureRoute.BLE_MIC, CaptureRoute.STOPPED -> Unit
        }
    }

    private fun targetRoute(env: CaptureEnvironment): CaptureRoute {
        if (env.micMuted) return CaptureRoute.STOPPED
        if (env.callActive) return CaptureRoute.PHONE_CALL
        if (env.preferredSource == MicSource.PHONE || !env.bleConnected) {
            return CaptureRoute.PHONE_NORMAL
        }
        // BLE conectado y preferido: el STT en la nube consume las tramas BLE.
        // Sin STT en la nube se usa el reconocedor nativo si está disponible.
        return if (env.hasCloudStt || !env.nativeRecognizerAvailable) {
            CaptureRoute.BLE_MIC
        } else {
            CaptureRoute.NATIVE_RECOGNIZER
        }
    }

    private fun applyRoute(target: CaptureRoute) {
        val previous = _route.value
        // Publicar la ruta antes de tocar hardware: una trama en vuelo ya se evalúa
        // contra la ruta nueva, no contra la anterior.
        _route.value = target

        val previousUsesPhone = previous == CaptureRoute.PHONE_NORMAL ||
                previous == CaptureRoute.PHONE_CALL
        val targetUsesPhone = target == CaptureRoute.PHONE_NORMAL ||
                target == CaptureRoute.PHONE_CALL

        // Primero se apaga lo que ya no corresponde; después se enciende lo nuevo.
        if (previousUsesPhone && !targetUsesPhone) {
            hardware.stopPhone()
        }
        if (previous == CaptureRoute.NATIVE_RECOGNIZER && target != CaptureRoute.NATIVE_RECOGNIZER) {
            hardware.stopNativeRecognizer()
        }

        // Entre PHONE_NORMAL y PHONE_CALL no se para el grabador: el puerto de hardware
        // usa switchToCallMode para reconfigurar en una sola operación.
        if (targetUsesPhone) {
            hardware.startPhone(callMode = target == CaptureRoute.PHONE_CALL) { frame ->
                forwardPhoneFrame(frame)
            }
        }
        if (target == CaptureRoute.NATIVE_RECOGNIZER) {
            hardware.startNativeRecognizer()
        }
    }

    private fun forwardPhoneFrame(frame: ByteArray) {
        val current = _route.value
        if (current == CaptureRoute.PHONE_NORMAL || current == CaptureRoute.PHONE_CALL) {
            onFrame(frame)
        }
    }
}
