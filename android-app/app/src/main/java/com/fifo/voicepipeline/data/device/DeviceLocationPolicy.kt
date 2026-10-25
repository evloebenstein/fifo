package com.fifo.voicepipeline.data.device

import com.fifo.voicepipeline.ui.model.FifoDeviceLocation

/**
 * Política de transición del estado del robot Fifo.
 *
 * Es una función pura sobre `FifoDeviceLocation`: no conoce SQLite, red ni
 * relojes. `FifoDataRepository` formatea la hora, aplica la transición,
 * persiste el resultado y lo sincroniza con el backend.
 *
 * Reglas:
 * - un dato nuevo `null` conserva el último valor conocido;
 * - al desconectarse se apaga el beeper y se registra “Hoy a las HH:mm”;
 * - al reconectarse se conserva el beeper si ya estaba activo;
 * - el beeper se puede activar o apagar sin tocar la conexión ni la ubicación.
 */
internal object DeviceLocationPolicy {

    const val CONNECTED_NOW = "Conectado ahora"

    fun applyConnectionUpdate(
        current: FifoDeviceLocation,
        connected: Boolean,
        nowLabel: String,
        latitude: Double? = null,
        longitude: Double? = null,
        address: String? = null,
        roomHint: String? = null,
        rssi: Int? = null
    ): FifoDeviceLocation = current.copy(
        isConnected = connected,
        lastConnectedTime = if (connected) CONNECTED_NOW else "Hoy a las $nowLabel",
        lastKnownLatitude = latitude ?: current.lastKnownLatitude,
        lastKnownLongitude = longitude ?: current.lastKnownLongitude,
        lastKnownAddress = address ?: current.lastKnownAddress,
        lastKnownRoom = roomHint ?: current.lastKnownRoom,
        signalStrengthRssi = rssi ?: current.signalStrengthRssi,
        isBeeping = if (connected) current.isBeeping else false
    )

    fun applyBeep(current: FifoDeviceLocation, beeping: Boolean): FifoDeviceLocation =
        current.copy(isBeeping = beeping)
}
