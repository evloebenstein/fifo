package com.fifo.voicepipeline.data.device

import com.fifo.voicepipeline.ui.model.FifoDeviceLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLocationPolicyTest {

    private val known = FifoDeviceLocation(
        isConnected = false,
        lastConnectedTime = "Hoy a las 09:15",
        lastKnownLatitude = -33.4489,
        lastKnownLongitude = -70.6693,
        lastKnownAddress = "Av. Providencia 123",
        lastKnownRoom = "Living",
        signalStrengthRssi = -62,
        isBeeping = false
    )

    @Test
    fun `al conectarse registra Conectado ahora y guarda la ubicacion nueva`() {
        val updated = DeviceLocationPolicy.applyConnectionUpdate(
            current = known,
            connected = true,
            nowLabel = "14:05",
            latitude = -33.43,
            longitude = -70.61,
            address = "Los Leones 456",
            roomHint = "Dormitorio",
            rssi = -48
        )

        assertTrue(updated.isConnected)
        assertEquals("Conectado ahora", updated.lastConnectedTime)
        assertEquals(-33.43, updated.lastKnownLatitude, 0.0001)
        assertEquals(-70.61, updated.lastKnownLongitude, 0.0001)
        assertEquals("Los Leones 456", updated.lastKnownAddress)
        assertEquals("Dormitorio", updated.lastKnownRoom)
        assertEquals(-48, updated.signalStrengthRssi)
    }

    @Test
    fun `al desconectarse registra la hora y apaga el beeper`() {
        val beeping = known.copy(isConnected = true, isBeeping = true)

        val updated = DeviceLocationPolicy.applyConnectionUpdate(
            current = beeping,
            connected = false,
            nowLabel = "18:30"
        )

        assertFalse(updated.isConnected)
        assertEquals("Hoy a las 18:30", updated.lastConnectedTime)
        assertFalse(updated.isBeeping)
    }

    @Test
    fun `al reconectarse conserva el beeper que ya estaba activo`() {
        val beeping = known.copy(isConnected = true, isBeeping = true)

        val updated = DeviceLocationPolicy.applyConnectionUpdate(
            current = beeping,
            connected = true,
            nowLabel = "08:00"
        )

        assertTrue(updated.isBeeping)
    }

    @Test
    fun `los datos nulos conservan el ultimo valor conocido`() {
        val updated = DeviceLocationPolicy.applyConnectionUpdate(
            current = known,
            connected = true,
            nowLabel = "10:00"
        )

        assertEquals(known.lastKnownLatitude, updated.lastKnownLatitude, 0.0001)
        assertEquals(known.lastKnownLongitude, updated.lastKnownLongitude, 0.0001)
        assertEquals(known.lastKnownAddress, updated.lastKnownAddress)
        assertEquals(known.lastKnownRoom, updated.lastKnownRoom)
        assertEquals(known.signalStrengthRssi, updated.signalStrengthRssi)
    }

    @Test
    fun `una actualizacion parcial solo cambia el campo informado`() {
        val updated = DeviceLocationPolicy.applyConnectionUpdate(
            current = known,
            connected = false,
            nowLabel = "11:00",
            roomHint = "Cocina"
        )

        assertEquals("Cocina", updated.lastKnownRoom)
        assertEquals(known.lastKnownAddress, updated.lastKnownAddress)
        assertEquals(known.signalStrengthRssi, updated.signalStrengthRssi)
    }

    @Test
    fun `el rssi cero es un valor valido y no se reemplaza por el anterior`() {
        val updated = DeviceLocationPolicy.applyConnectionUpdate(
            current = known,
            connected = true,
            nowLabel = "12:00",
            rssi = 0
        )

        assertEquals(0, updated.signalStrengthRssi)
    }

    @Test
    fun `el beeper se activa y apaga sin tocar conexion ni ubicacion`() {
        val activado = DeviceLocationPolicy.applyBeep(known, true)
        val apagado = DeviceLocationPolicy.applyBeep(activado, false)

        assertTrue(activado.isBeeping)
        assertFalse(apagado.isBeeping)
        assertEquals(known.isConnected, apagado.isConnected)
        assertEquals(known.lastConnectedTime, apagado.lastConnectedTime)
        assertEquals(known.lastKnownAddress, apagado.lastKnownAddress)
    }

    @Test
    fun `el estado inicial se puede desconectar sin datos previos`() {
        val updated = DeviceLocationPolicy.applyConnectionUpdate(
            current = FifoDeviceLocation(),
            connected = false,
            nowLabel = "07:45"
        )

        assertEquals("Hoy a las 07:45", updated.lastConnectedTime)
        assertFalse(updated.isConnected)
        assertFalse(updated.isBeeping)
    }
}
