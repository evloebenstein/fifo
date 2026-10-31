package com.fifo.voicepipeline.data.device

import com.fifo.voicepipeline.data.local.FifoDatabaseHelper
import com.fifo.voicepipeline.data.model.FifoDeviceLocation
import com.fifo.voicepipeline.data.model.PhoneHardwareStatus
import com.fifo.voicepipeline.network.FifoBackendClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Costura de sincronización remota del estado del dispositivo.
 *
 * Producción usa [BackendDeviceRemote]; las pruebas inyectan un no-op para no
 * tocar la red.
 */
fun interface DeviceRemotePort {
    suspend fun patch(
        userId: String,
        connected: Boolean?,
        location: FifoDeviceLocation,
        isBeeping: Boolean?
    )
}

/** Adaptador de producción sobre [FifoBackendClient]. */
object BackendDeviceRemote : DeviceRemotePort {
    override suspend fun patch(
        userId: String,
        connected: Boolean?,
        location: FifoDeviceLocation,
        isBeeping: Boolean?
    ) {
        FifoBackendClient.patchDeviceLocation(
            userId = userId,
            isConnected = connected,
            lastConnectedTime = location.lastConnectedTime,
            latitude = location.lastKnownLatitude,
            longitude = location.lastKnownLongitude,
            address = location.lastKnownAddress,
            roomHint = location.lastKnownRoom,
            rssi = location.signalStrengthRssi,
            isBeeping = isBeeping ?: location.isBeeping
        )
    }
}

/**
 * Dueño único del estado del dispositivo y del teléfono (ADR-0049):
 * ubicación del robot, conexión con el backend y hardware (linterna, batería,
 * volumen).
 *
 * `isContinuousListening` **no** vive aquí: su dueño es `TurnOrchestrator`.
 */
class DeviceState(
    private val database: () -> FifoDatabaseHelper?,
    private val scope: CoroutineScope,
    private val userId: () -> String,
    private val remote: DeviceRemotePort = BackendDeviceRemote,
    private val nowLabel: () -> String = {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }
) {
    private val _location = MutableStateFlow(FifoDeviceLocation())
    val location: StateFlow<FifoDeviceLocation> = _location.asStateFlow()

    private val _hardware = MutableStateFlow(
        PhoneHardwareStatus(
            batteryPercent = 85,
            isCharging = false,
            isFlashlightOn = false,
            volumePercent = 80
        )
    )
    val hardware: StateFlow<PhoneHardwareStatus> = _hardware.asStateFlow()

    private val _isBackendConnected = MutableStateFlow(false)
    val isBackendConnected: StateFlow<Boolean> = _isBackendConnected.asStateFlow()

    /** Carga la última foto persistida del robot al restaurar la sesión. */
    fun load() {
        _location.value = database()?.deviceLocation?.getDeviceLocation() ?: FifoDeviceLocation()
    }

    /** Aplica la foto del robot recibida del backend sin reescribirla en local. */
    fun importLocation(location: FifoDeviceLocation) {
        _location.value = location
    }

    fun setBackendConnected(connected: Boolean) {
        _isBackendConnected.value = connected
    }

    /**
     * Actualiza el estado de conexión y coordenadas GPS de la última vez visto.
     * Si la ruta calculada no cambia, no se toca hardware ni se persiste.
     */
    fun updateConnectionStatus(
        connected: Boolean,
        latitude: Double? = null,
        longitude: Double? = null,
        address: String? = null,
        roomHint: String? = null,
        rssi: Int? = null
    ) {
        val updated = DeviceLocationPolicy.applyConnectionUpdate(
            current = _location.value,
            connected = connected,
            nowLabel = nowLabel(),
            latitude = latitude,
            longitude = longitude,
            address = address,
            roomHint = roomHint,
            rssi = rssi
        )
        _location.value = updated
        database()?.deviceLocation?.updateDeviceLocation(loc = updated, userId = userId())

        scope.launch {
            remote.patch(
                userId = userId(),
                connected = connected,
                location = updated,
                isBeeping = updated.isBeeping
            )
        }
    }

    /** Activa o desactiva el beeper del robot sin tocar la conexión ni la ubicación. */
    fun triggerBeep(beeping: Boolean) {
        val updated = DeviceLocationPolicy.applyBeep(_location.value, beeping)
        _location.value = updated
        database()?.deviceLocation?.updateDeviceLocation(loc = updated, userId = userId())

        scope.launch {
            remote.patch(
                userId = userId(),
                connected = null,
                location = updated,
                isBeeping = beeping
            )
        }
    }

    fun updateHardware(
        batteryPercent: Int? = null,
        isCharging: Boolean? = null,
        isFlashlightOn: Boolean? = null,
        volumePercent: Int? = null
    ) {
        val current = _hardware.value
        _hardware.value = current.copy(
            batteryPercent = batteryPercent ?: current.batteryPercent,
            isCharging = isCharging ?: current.isCharging,
            isFlashlightOn = isFlashlightOn ?: current.isFlashlightOn,
            volumePercent = volumePercent ?: current.volumePercent
        )
    }
}
