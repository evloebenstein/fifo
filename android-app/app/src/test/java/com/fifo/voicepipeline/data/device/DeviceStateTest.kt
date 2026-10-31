package com.fifo.voicepipeline.data.device

import com.fifo.voicepipeline.data.model.FifoDeviceLocation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Estado del dispositivo (ADR-0049): transiciones de conexión, beeper sin
 * tocar la ubicación y hardware con actualización parcial. La red se sustituye
 * por una costura (`DeviceRemotePort`) y la base por `null`.
 */
class DeviceStateTest {

    private lateinit var remote: RecordingDeviceRemote
    private lateinit var device: DeviceState

    @Before
    fun setUp() {
        remote = RecordingDeviceRemote()
        device = DeviceState(
            database = { null },
            scope = TestScope(UnconfinedTestDispatcher()),
            userId = { "usr_carmen" },
            remote = remote,
            nowLabel = { "10:00" }
        )
    }

    @Test
    fun `connecting records coordinates and reports the patch`() {
        device.updateConnectionStatus(
            connected = true,
            latitude = -33.45,
            longitude = -70.66,
            address = "Av. Los Aromos 123",
            roomHint = "Living",
            rssi = -42
        )

        val location = device.location.value
        assertTrue(location.isConnected)
        assertEquals(DeviceLocationPolicy.CONNECTED_NOW, location.lastConnectedTime)
        assertEquals(-33.45, location.lastKnownLatitude, 0.0)
        assertEquals(-70.66, location.lastKnownLongitude, 0.0)
        assertEquals("Av. Los Aromos 123", location.lastKnownAddress)
        assertEquals("Living", location.lastKnownRoom)
        assertEquals(-42, location.signalStrengthRssi)

        val patch = remote.patches.single()
        assertEquals("usr_carmen", patch.userId)
        assertEquals(true, patch.connected)
        assertEquals("Av. Los Aromos 123", patch.location.lastKnownAddress)
    }

    @Test
    fun `disconnecting keeps the last known location and silences the beeper`() {
        device.updateConnectionStatus(connected = true, latitude = -33.45, address = "Casa", rssi = -40)
        device.triggerBeep(true)

        device.updateConnectionStatus(connected = false)

        val location = device.location.value
        assertFalse(location.isConnected)
        assertEquals("Hoy a las 10:00", location.lastConnectedTime)
        assertFalse(location.isBeeping)
        assertEquals(-33.45, location.lastKnownLatitude, 0.0)
        assertEquals("Casa", location.lastKnownAddress)
        assertEquals(-40, location.signalStrengthRssi)
    }

    @Test
    fun `beeping does not change connection or location`() {
        device.updateConnectionStatus(connected = true, latitude = -33.45, address = "Casa")

        device.triggerBeep(true)

        val location = device.location.value
        assertTrue(location.isConnected)
        assertTrue(location.isBeeping)
        assertEquals(-33.45, location.lastKnownLatitude, 0.0)
        assertNull(remote.patches.last().connected)
        assertEquals(true, remote.patches.last().isBeeping)
    }

    @Test
    fun `hardware updates are partial`() {
        device.updateHardware(batteryPercent = 40)
        device.updateHardware(volumePercent = 25)

        val hardware = device.hardware.value
        assertEquals(40, hardware.batteryPercent)
        assertEquals(25, hardware.volumePercent)
        assertFalse(hardware.isCharging)
        assertFalse(hardware.isFlashlightOn)
    }

    @Test
    fun `importing a backend snapshot does not publish it back`() {
        val snapshot = FifoDeviceLocation(
            isConnected = true,
            lastKnownAddress = "Providencia",
            signalStrengthRssi = -55
        )

        device.importLocation(snapshot)

        assertEquals(snapshot, device.location.value)
        assertTrue(remote.patches.isEmpty())
    }

    @Test
    fun `backend connectivity is observable`() {
        assertFalse(device.isBackendConnected.value)

        device.setBackendConnected(true)

        assertTrue(device.isBackendConnected.value)
    }

    private data class Patch(
        val userId: String,
        val connected: Boolean?,
        val location: FifoDeviceLocation,
        val isBeeping: Boolean?
    )

    private class RecordingDeviceRemote : DeviceRemotePort {
        val patches = mutableListOf<Patch>()

        override suspend fun patch(
            userId: String,
            connected: Boolean?,
            location: FifoDeviceLocation,
            isBeeping: Boolean?
        ) {
            patches.add(Patch(userId, connected, location, isBeeping))
        }
    }
}
