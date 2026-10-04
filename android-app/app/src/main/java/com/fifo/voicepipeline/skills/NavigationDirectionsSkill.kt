package com.fifo.voicepipeline.skills

import android.content.Context
import android.util.Log
import com.fifo.voicepipeline.data.FifoDataRepository
import com.fifo.voicepipeline.location.FifoLocationHelper

/**
 * Skill que permite a Fifo guiar al usuario hacia un destino:
 * - Guía HABLADA manos libres (no requiere abrir ni mirar el celular).
 * - Calcula distancia, minutos caminando, orientación cardinal y pasos iniciales.
 * - Si el usuario pide expresamente abrir la pantalla ("abre Maps", "abre Waze"),
 *   inicia la app visual en el celular.
 */
class NavigationDirectionsSkill(private val context: Context) : FifoSkill {

    companion object {
        private const val TAG = "NavigationDirectionsSkill"
    }

    override val name: String = "open_navigation_directions"

    override val description: String =
        "Guía al usuario hacia un destino (su casa, farmacia, consultorio, parque, hospital) mediante indicaciones habladas directas por voz sin necesidad de mirar el celular, y opcionalmente abre la ruta en Google Maps o Waze si el usuario lo pide."

    override val parameterSchemaJson: String = """
    {
        "type": "object",
        "properties": {
            "destination": {
                "type": "string",
                "description": "Lugar de destino o dirección (ej: 'mi casa', 'Farmacia Ahumada', 'CESFAM', 'Plaza Inés de Suárez')"
            },
            "open_screen_map": {
                "type": "boolean",
                "description": "True si el usuario pidió explícitamente abrir o ver el mapa en la pantalla del celular. Por defecto False (guía 100% hablada por voz sin tocar el teléfono)."
            },
            "navigation_app": {
                "type": "string",
                "enum": ["google_maps", "waze"],
                "description": "Aplicación de mapas si open_screen_map es true ('google_maps' o 'waze'). Por defecto 'google_maps'."
            }
        },
        "required": ["destination"]
    }
    """.trimIndent()

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val destInput = args["destination"]?.toString()?.trim() ?: ""
        val openScreen = (args["open_screen_map"] as? Boolean)
            ?: (args["open_screen_map"]?.toString()?.toBooleanStrictOrNull() ?: false)
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

        Log.i(TAG, "Generando guía hablada hacia: '$resolvedDestination' (abrir pantalla: $openScreen)")

        // 1. Obtener guía hablada con cálculo de distancia, minutos a pie y orientación
        val routeGuidance = FifoLocationHelper.getSpokenRouteGuidance(context, resolvedDestination)

        // 2. Si el usuario pidió explícitamente abrir el mapa visual en pantalla
        if (openScreen) {
            val (success, appUsed) = FifoLocationHelper.startNavigation(context, resolvedDestination, appChoice)
            val combinedSpoken = "${routeGuidance.spokenGuidance} También le he abierto la ruta en $appUsed en la pantalla del teléfono."
            return SkillResult(
                success = success,
                spokenFeedback = combinedSpoken,
                data = mapOf(
                    "destination" to resolvedDestination,
                    "distance_meters" to routeGuidance.distanceMeters,
                    "walking_minutes" to routeGuidance.walkingMinutes,
                    "app_used" to appUsed,
                    "screen_opened" to true
                )
            )
        }

        // 3. Guía 100% por voz sin obligar a desbloquear ni mirar el celular
        return SkillResult(
            success = true,
            spokenFeedback = routeGuidance.spokenGuidance,
            data = mapOf(
                "destination" to resolvedDestination,
                "distance_meters" to routeGuidance.distanceMeters,
                "walking_minutes" to routeGuidance.walkingMinutes,
                "screen_opened" to false
            )
        )
    }
}
