package com.fifo.voicepipeline.skills

import android.content.Context
import android.util.Log
import com.fifo.voicepipeline.data.FifoDataRepository
import com.fifo.voicepipeline.location.FifoLocationHelper

/**
 * Skill que permite a Fifo guiar al usuario abriendo la navegación paso a paso
 * en Google Maps o Waze cuando pide direcciones:
 * - "Fifo, llévame a mi casa con Waze"
 * - "¿Cómo llego a la farmacia en Maps?"
 * - "Dame la dirección hacia el Parque Inés de Suárez"
 */
class NavigationDirectionsSkill(private val context: Context) : FifoSkill {

    companion object {
        private const val TAG = "NavigationDirectionsSkill"
    }

    override val name: String = "open_navigation_directions"

    override val description: String =
        "Abre la navegación paso a paso hacia un destino en el celular utilizando Google Maps o Waze cuando el usuario pide direcciones o pregunta cómo llegar a algún lugar (su casa, farmacia, consultorio, parque, hospital)."

    override val parameterSchemaJson: String = """
    {
        "type": "object",
        "properties": {
            "destination": {
                "type": "string",
                "description": "Lugar de destino o dirección (ej: 'mi casa', 'Farmacia Cruz Verde', 'Hospital del Salvador', 'Plaza de Armas')"
            },
            "navigation_app": {
                "type": "string",
                "enum": ["google_maps", "waze"],
                "description": "Aplicación de mapas preferida ('google_maps' o 'waze'). Por defecto 'google_maps'."
            }
        },
        "required": ["destination"]
    }
    """.trimIndent()

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val destInput = args["destination"]?.toString()?.trim() ?: ""
        val appChoice = args["navigation_app"]?.toString()?.trim() ?: "google_maps"

        if (destInput.isBlank()) {
            return SkillResult(
                success = false,
                spokenFeedback = "No alcancé a entender hacia dónde desea ir. ¿Me puede indicar el nombre del lugar o la dirección?"
            )
        }

        // Si pide ir a "mi casa" o "casa", buscamos su dirección registrada en el perfil
        val resolvedDestination = if (destInput.contains("casa", ignoreCase = true)) {
            val userAddr = FifoDataRepository.userProfile.value.preferredAddress
            if (!userAddr.isNullOrBlank()) userAddr else destInput
        } else {
            destInput
        }

        Log.i(TAG, "Iniciando navegación hacia: '$resolvedDestination' usando '$appChoice'")
        val (success, appUsed) = FifoLocationHelper.startNavigation(context, resolvedDestination, appChoice)

        return if (success) {
            val spoken = "He iniciado la navegación hacia $destInput en $appUsed. Ya puede ver la ruta y las indicaciones paso a paso en la pantalla de su teléfono."
            SkillResult(
                success = true,
                spokenFeedback = spoken,
                data = mapOf(
                    "destination" to resolvedDestination,
                    "app_used" to appUsed
                )
            )
        } else {
            SkillResult(
                success = false,
                spokenFeedback = "Disculpe, tuve un inconveniente al abrir la aplicación de mapas en el teléfono. Por favor revise que el GPS esté activo."
            )
        }
    }
}
