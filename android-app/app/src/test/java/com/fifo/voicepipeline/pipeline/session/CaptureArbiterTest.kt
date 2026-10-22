package com.fifo.voicepipeline.pipeline.session

import com.fifo.voicepipeline.pipeline.MicSource
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Pruebas de la tabla de transiciones del dueño único de captura.
 *
 * El fake conserva el callback del micrófono aunque se detenga, para verificar que el
 * árbitro descarta tramas tardías después de un cambio de ruta.
 */
class CaptureArbiterTest {

    private class FakeCaptureHardware : CaptureHardware {
        var phoneStarts = 0
        var phoneStops = 0
        var nativeStarts = 0
        var nativeStops = 0
        var lastPhoneCallMode: Boolean? = null
        var phoneCallback: ((ByteArray) -> Unit)? = null

        override fun startPhone(callMode: Boolean, onFrame: (ByteArray) -> Unit) {
            phoneStarts++
            lastPhoneCallMode = callMode
            phoneCallback = onFrame
        }

        override fun stopPhone() {
            phoneStops++
            // Deliberadamente NO se limpia phoneCallback: simula una trama en vuelo.
        }

        override fun startNativeRecognizer() {
            nativeStarts++
        }

        override fun stopNativeRecognizer() {
            nativeStops++
        }

        fun emitPhone(frame: ByteArray = byteArrayOf(1, 2, 3)) {
            phoneCallback?.invoke(frame)
        }
    }

    private lateinit var hardware: FakeCaptureHardware
    private lateinit var frames: MutableList<ByteArray>
    private lateinit var arbiter: CaptureArbiter

    @Before
    fun setUp() {
        hardware = FakeCaptureHardware()
        frames = mutableListOf()
        arbiter = CaptureArbiter(hardware) { frame -> frames.add(frame) }
    }

    private fun env(
        muted: Boolean = false,
        callActive: Boolean = false,
        bleConnected: Boolean = false,
        preferredSource: MicSource = MicSource.ESP32,
        hasCloudStt: Boolean = true,
        nativeAvailable: Boolean = false
    ) = CaptureEnvironment(
        micMuted = muted,
        callActive = callActive,
        bleConnected = bleConnected,
        preferredSource = preferredSource,
        hasCloudStt = hasCloudStt,
        nativeRecognizerAvailable = nativeAvailable
    )

    @Test
    fun `arranca detenido y no toca hardware`() {
        assertEquals(CaptureRoute.STOPPED, arbiter.route.value)
        assertEquals(0, hardware.phoneStarts)
        assertEquals(0, hardware.nativeStarts)
    }

    @Test
    fun `sin BLE usa microfono normal una sola vez`() {
        assertEquals(CaptureRoute.PHONE_NORMAL, arbiter.update(env()))
        assertEquals(1, hardware.phoneStarts)
        assertEquals(false, hardware.lastPhoneCallMode)

        arbiter.update(env())
        arbiter.update(env())
        assertEquals(1, hardware.phoneStarts)
    }

    @Test
    fun `BLE conectado con STT nube usa BLE y detiene el microfono`() {
        arbiter.update(env())
        assertEquals(CaptureRoute.BLE_MIC, arbiter.update(env(bleConnected = true)))
        assertEquals(1, hardware.phoneStops)
        assertEquals(1, hardware.phoneStarts)

        arbiter.onBleFrame(byteArrayOf(9))
        assertEquals(1, frames.size)
        assertEquals(9, frames.first().first().toInt())
    }

    @Test
    fun `fuente preferida PHONE gana sobre BLE y descarta tramas BLE`() {
        assertEquals(
            CaptureRoute.PHONE_NORMAL,
            arbiter.update(env(bleConnected = true, preferredSource = MicSource.PHONE))
        )
        assertEquals(1, hardware.phoneStarts)

        hardware.emitPhone()
        assertEquals(1, frames.size)

        arbiter.onBleFrame(byteArrayOf(7))
        assertEquals(1, frames.size)
    }

    @Test
    fun `tramas telefonicas tardias se descartan al cambiar a BLE`() {
        arbiter.update(env())
        hardware.emitPhone()
        assertEquals(1, frames.size)

        arbiter.update(env(bleConnected = true))
        hardware.emitPhone()
        assertEquals(1, frames.size)

        arbiter.onBleFrame(byteArrayOf(4))
        assertEquals(2, frames.size)
    }

    @Test
    fun `entrar en llamada reconfigura una sola vez a PHONE_CALL`() {
        arbiter.update(env(bleConnected = true))
        assertEquals(CaptureRoute.BLE_MIC, arbiter.route.value)

        assertEquals(CaptureRoute.PHONE_CALL, arbiter.update(env(bleConnected = true, callActive = true)))
        assertEquals(1, hardware.phoneStarts)
        assertEquals(true, hardware.lastPhoneCallMode)

        // Timbrado -> activo, o cualquier evento repetido, NO reconfigura de nuevo.
        repeat(5) {
            arbiter.update(env(bleConnected = true, callActive = true))
        }
        assertEquals(1, hardware.phoneStarts)
        assertEquals(0, hardware.phoneStops)
    }

    @Test
    fun `cambio de fuente durante llamada no reconfigura`() {
        arbiter.update(env(bleConnected = true, callActive = true))
        assertEquals(1, hardware.phoneStarts)

        arbiter.update(
            env(bleConnected = true, callActive = true, preferredSource = MicSource.PHONE)
        )
        assertEquals(CaptureRoute.PHONE_CALL, arbiter.route.value)
        assertEquals(1, hardware.phoneStarts)
    }

    @Test
    fun `al colgar vuelve a la ruta anterior`() {
        arbiter.update(env(bleConnected = true))
        arbiter.update(env(bleConnected = true, callActive = true))
        assertEquals(CaptureRoute.PHONE_CALL, arbiter.route.value)

        assertEquals(CaptureRoute.BLE_MIC, arbiter.update(env(bleConnected = true)))
        assertEquals(1, hardware.phoneStops)
    }

    @Test
    fun `al colgar con fuente PHONE vuelve a PHONE_NORMAL`() {
        arbiter.update(env(bleConnected = true, preferredSource = MicSource.PHONE))
        arbiter.update(
            env(bleConnected = true, preferredSource = MicSource.PHONE, callActive = true)
        )
        assertEquals(CaptureRoute.PHONE_CALL, arbiter.route.value)

        assertEquals(
            CaptureRoute.PHONE_NORMAL,
            arbiter.update(env(bleConnected = true, preferredSource = MicSource.PHONE))
        )
        assertEquals(3, hardware.phoneStarts)
        assertEquals(false, hardware.lastPhoneCallMode)
    }

    @Test
    fun `mute detiene la captura y descarta BLE`() {
        arbiter.update(env(bleConnected = true))
        arbiter.onBleFrame(byteArrayOf(1))
        assertEquals(1, frames.size)

        assertEquals(CaptureRoute.STOPPED, arbiter.update(env(bleConnected = true, muted = true)))
        assertEquals(0, hardware.phoneStops)
        arbiter.onBleFrame(byteArrayOf(2))
        assertEquals(1, frames.size)

        assertEquals(CaptureRoute.BLE_MIC, arbiter.update(env(bleConnected = true)))
        arbiter.onBleFrame(byteArrayOf(3))
        assertEquals(2, frames.size)
    }

    @Test
    fun `mute durante llamada detiene PHONE_CALL y al desmutear no reinicia nativo`() {
        arbiter.update(env(bleConnected = true, callActive = true))
        assertEquals(CaptureRoute.PHONE_CALL, arbiter.route.value)

        assertEquals(
            CaptureRoute.STOPPED,
            arbiter.update(env(bleConnected = true, callActive = true, muted = true))
        )
        assertEquals(1, hardware.phoneStops)

        assertEquals(
            CaptureRoute.PHONE_CALL,
            arbiter.update(env(bleConnected = true, callActive = true))
        )
        assertEquals(2, hardware.phoneStarts)
        assertEquals(0, hardware.nativeStarts)
    }

    @Test
    fun `durante llamada se aceptan tramas BLE aunque la fuente preferida sea PHONE`() {
        arbiter.update(
            env(bleConnected = true, callActive = true, preferredSource = MicSource.PHONE)
        )
        arbiter.onBleFrame(byteArrayOf(5))
        assertEquals(1, frames.size)

        arbiter.update(
            env(bleConnected = true, callActive = false, preferredSource = MicSource.PHONE)
        )
        arbiter.onBleFrame(byteArrayOf(6))
        assertEquals(1, frames.size)
    }

    @Test
    fun `sin STT nube usa reconocedor nativo y lo apaga durante llamada`() {
        assertEquals(
            CaptureRoute.NATIVE_RECOGNIZER,
            arbiter.update(env(bleConnected = true, hasCloudStt = false, nativeAvailable = true))
        )
        assertEquals(1, hardware.nativeStarts)
        assertEquals(0, hardware.phoneStops)

        assertEquals(
            CaptureRoute.PHONE_CALL,
            arbiter.update(
                env(
                    bleConnected = true,
                    callActive = true,
                    hasCloudStt = false,
                    nativeAvailable = true
                )
            )
        )
        assertEquals(1, hardware.nativeStops)
        assertEquals(1, hardware.phoneStarts)
        assertEquals(1, hardware.nativeStarts)

        // Al colgar vuelve al reconocedor nativo, no al micrófono.
        assertEquals(
            CaptureRoute.NATIVE_RECOGNIZER,
            arbiter.update(env(bleConnected = true, hasCloudStt = false, nativeAvailable = true))
        )
        assertEquals(2, hardware.nativeStarts)
    }

    @Test
    fun `sin STT nube y sin reconocedor nativo usa BLE`() {
        assertEquals(
            CaptureRoute.BLE_MIC,
            arbiter.update(env(bleConnected = true, hasCloudStt = false, nativeAvailable = false))
        )
        assertEquals(0, hardware.nativeStarts)
        assertEquals(0, hardware.phoneStarts)
    }

    @Test
    fun `shutdown detiene hardware y descarta todas las tramas`() {
        arbiter.update(env())
        assertEquals(1, hardware.phoneStarts)

        arbiter.shutdown()
        assertEquals(CaptureRoute.STOPPED, arbiter.route.value)
        assertEquals(1, hardware.phoneStops)

        hardware.emitPhone()
        arbiter.onBleFrame(byteArrayOf(8))
        assertEquals(0, frames.size)
    }

    @Test
    fun `shutdown en BLE no toca hardware local y bloquea tramas`() {
        arbiter.update(env(bleConnected = true))
        arbiter.shutdown()

        assertEquals(CaptureRoute.STOPPED, arbiter.route.value)
        assertEquals(0, hardware.phoneStops)
        arbiter.onBleFrame(byteArrayOf(1))
        assertEquals(0, frames.size)
    }
}
