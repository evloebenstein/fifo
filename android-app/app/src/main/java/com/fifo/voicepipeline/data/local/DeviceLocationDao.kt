package com.fifo.voicepipeline.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.fifo.voicepipeline.ui.model.FifoDeviceLocation

/**
 * DAO de ubicación del dispositivo: `device_locations`.
 *
 * No conoce `Context` ni el ciclo de vida de la base. El helper le pasa las
 * conexiones ya abiertas.
 */
class DeviceLocationDao(
    private val readableDatabase: () -> SQLiteDatabase,
    private val writableDatabase: () -> SQLiteDatabase
) {
    fun getDeviceLocation(deviceId: String = "FIFO-S3-ESP32"): FifoDeviceLocation {
        readableDatabase().rawQuery(
            "SELECT is_connected, last_connected_time, last_known_latitude, " +
                "last_known_longitude, last_known_address, last_known_room, " +
                "signal_strength_rssi, is_beeping " +
                "FROM device_locations WHERE device_id = ?",
            arrayOf(deviceId)
        ).use { cur ->
            if (cur.moveToFirst()) {
                return FifoDeviceLocation(
                    isConnected = cur.getInt(0) == 1,
                    lastConnectedTime = cur.getString(1) ?: "Desconectado",
                    lastKnownLatitude = cur.getDouble(2),
                    lastKnownLongitude = cur.getDouble(3),
                    lastKnownAddress = cur.getString(4) ?: "Sin registro de ubicación",
                    lastKnownRoom = cur.getString(5) ?: "Sin registrar",
                    signalStrengthRssi = cur.getInt(6),
                    isBeeping = cur.getInt(7) == 1
                )
            }
        }
        return FifoDeviceLocation()
    }

    fun updateDeviceLocation(
        deviceId: String = "FIFO-S3-ESP32",
        loc: FifoDeviceLocation,
        userId: String
    ) {
        val cv = ContentValues().apply {
            put("device_id", deviceId)
            put("user_id", userId)
            put("is_connected", if (loc.isConnected) 1 else 0)
            put("last_connected_time", loc.lastConnectedTime)
            put("last_known_latitude", loc.lastKnownLatitude)
            put("last_known_longitude", loc.lastKnownLongitude)
            put("last_known_address", loc.lastKnownAddress)
            put("last_known_room", loc.lastKnownRoom)
            put("signal_strength_rssi", loc.signalStrengthRssi)
            put("is_beeping", if (loc.isBeeping) 1 else 0)
        }
        writableDatabase().insertWithOnConflict(
            "device_locations",
            null,
            cv,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }
}
