package com.fifo.voicepipeline.network

import com.fifo.voicepipeline.data.model.RobotGpsFix

/**
 * Decodificador del aviso de GNSS del robot (característica `ff15`).
 *
 * El firmware publica texto plano, separador `|` y seis campos:
 * `valid|latitud|longitud|altitud|satélites|hdop`. Un payload roto se descarta
 * en silencio: es mejor no saber nada que inventar una posición.
 */
object RobotGpsParser {

    private const val MIN_FIELDS = 6

    fun parse(text: String, receivedAtMs: Long): RobotGpsFix? {
        val parts = text.trim().split('|')
        if (parts.size < MIN_FIELDS) return null

        val hasFix = parts[0].trim() == "1"
        val latitude = parts[1].toDoubleOrNull() ?: return null
        val longitude = parts[2].toDoubleOrNull() ?: return null
        val altitude = parts[3].toDoubleOrNull() ?: 0.0
        val satellites = parts[4].toIntOrNull() ?: 0
        val hdop = parts[5].toDoubleOrNull() ?: 0.0

        // Un "fix" en el cero del planeta es un módulo sin solución, no una posición.
        val usable = hasFix && latitude != 0.0 && longitude != 0.0

        return RobotGpsFix(
            hasFix = usable,
            latitude = if (usable) latitude else 0.0,
            longitude = if (usable) longitude else 0.0,
            altitudeMeters = if (usable) altitude else 0.0,
            satellites = satellites,
            hdop = hdop,
            receivedAtMs = receivedAtMs
        )
    }
}
