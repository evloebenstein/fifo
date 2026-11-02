package com.fifo.voicepipeline.skills

import android.util.Log
import com.fifo.voicepipeline.location.LocationAdapter

/**
 * Skill que permite a Fifo responder a preguntas como:
 * - "Fifo, ¿dónde estamos?"
 * - "¿En qué calle estoy?"
 * - "¿Cuál es mi ubicación actual?"
 *
 * Usa el adapter de ubicación inyectado (GPS + geocodificación inversa); no
 * toca el hardware ni el accessor.
 */
class CurrentLocationSkill(
    private val locationAdapter: LocationAdapter
) : FifoSkill {

    companion object {
        private const val TAG = "CurrentLocationSkill"
    }

    override val name: String = "get_current_location"

    override val description: String =
        "Obtiene la ubicación geográfica actual del usuario a través del GPS del celular y le informa con exactitud en qué calle, comuna o ciudad se encuentra cuando pregunta '¿dónde estamos?' o '¿cuál es mi ubicación?'."

    override val parameterSchemaJson: String = """
    {
        "type": "object",
        "properties": {
            "detail_level": {
                "type": "string",
                "enum": ["street_and_city", "city_only"],
                "description": "Nivel de detalle de la respuesta (por defecto calle y comuna)"
            }
        }
    }
    """.trimIndent()

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        Log.i(TAG, "Consultando ubicación actual por GPS...")
        val loc = locationAdapter.getCurrentLocation()

        val spokenFeedback = if (loc.isGpsActive) {
            "Estamos en ${loc.address}, en ${loc.city}."
        } else {
            "Según el registro de su domicilio, estamos en ${loc.address}, en ${loc.city}."
        }

        return SkillResult(
            success = true,
            spokenFeedback = spokenFeedback,
            data = mapOf(
                "latitude" to loc.latitude,
                "longitude" to loc.longitude,
                "address" to loc.address,
                "city" to loc.city,
                "gps_active" to loc.isGpsActive
            )
        )
    }
}
