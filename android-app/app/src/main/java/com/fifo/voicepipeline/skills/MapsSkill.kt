package com.fifo.voicepipeline.skills

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Skill para interactuar con Google Maps y buscar lugares cercanos de interés para personas mayores:
 * farmacias de turno, centros de salud (CESFAM), hospitales, parques tranquilos, etc.
 */
class MapsSkill(private val context: Context) : FifoSkill {

    companion object {
        private const val TAG = "MapsSkill"
    }

    override val name: String = "search_nearby_places"

    override val description: String =
        "Busca lugares de interés en el mapa cercanos a la persona (farmacias, consultorios, parques, hospitales, panaderías) y abre la vista del mapa."

    override val parameterSchemaJson: String = """
    {
        "type": "object",
        "properties": {
            "place_type": {
                "type": "string",
                "enum": ["farmacia", "centro_salud", "parque", "hospital", "panaderia", "supermercado"],
                "description": "Categoría o tipo de lugar a buscar"
            },
            "query_hint": {
                "type": "string",
                "description": "Detalle adicional como comuna o nombre específico (ej: 'Farmacia Cruz Verde', 'Parque Inés de Suárez')"
            }
        },
        "required": ["place_type"]
    }
    """.trimIndent()

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val placeType = args["place_type"]?.toString()?.trim() ?: "farmacia"
        val hint = args["query_hint"]?.toString()?.trim() ?: ""

        val searchQuery = if (hint.isNotBlank()) "$placeType $hint" else "$placeType cercanas"
        Log.i(TAG, "Abriendo mapa para búsqueda: '$searchQuery'")

        try {
            val gmmIntentUri = Uri.parse("geo:0,0?q=" + Uri.encode(searchQuery))
            val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(mapIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error abriendo Maps: ${e.message}")
        }

        val spokenName = when (placeType) {
            "farmacia" -> "las farmacias más cercanas"
            "centro_salud" -> "los centros de salud y consultorios cercanos"
            "parque" -> "los parques y plazas con áreas verdes cercanas"
            "hospital" -> "los hospitales y centros de urgencia cercanos"
            else -> "los locales de $placeType más cercanos"
        }

        return SkillResult(
            success = true,
            spokenFeedback = "He buscado $spokenName y le dejé abierto el mapa en el teléfono para que pueda ver la distancia y cómo llegar.",
            data = mapOf("query" to searchQuery, "place_type" to placeType)
        )
    }
}
