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
                "description": "Lugar de destino o dirección (ej: 'mi casa', 'Farmacia Ahumada', 'supermercado', 'Lider', 'Jumbo', 'CESFAM', 'Plaza Inés de Suárez')"
            },
            "open_screen_map": {
                "type": ["boolean", "null"],
                "description": "True si el usuario pidió explícitamente abrir o ver el mapa en la pantalla del celular. Por defecto False (guía 100% hablada por voz sin tocar el teléfono)."
            },
            "navigation_app": {
                "type": ["string", "null"],
                "enum": ["google_maps", "waze", null],
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

        val destTrimmed = destInput.lowercase().trim()
        val isGenericDestination = destTrimmed.isBlank() ||
                destTrimmed in listOf(
                    "algún lado", "algun lado", "un lado", "un lugar", "algún lugar",
                    "algun lugar", "un sitio", "a algún lado", "a algun lado", "a un lugar",
                    "a un sitio", "a ninguna parte", "donde sea", "a donde sea"
                )

        if (isGenericDestination) {
            return SkillResult(
                success = false,
                spokenFeedback = "¿Hacia qué lugar o dirección desea ir? Indíqueme el destino y con gusto le guío paso a paso.",
                data = mapOf("needs_destination" to true)
            )
        }

        // Si pide un comercio o lugar de interés cercano (ej: OXXO, farmacia, supermercado)
        val destLower = destInput.lowercase().trim()
        val isStoreQuery = destLower.contains("oxxo") ||
                destLower.contains("farmacia") ||
                destLower.contains("minimarket") ||
                destLower.contains("supermercado") ||
                destLower.contains("super") ||
                destLower.contains("lider") ||
                destLower.contains("jumbo") ||
                destLower.contains("unimarc") ||
                destLower.contains("santa isabel")

        if (isStoreQuery) {
            val currentLoc = FifoLocationHelper.getCurrentLocation(context)
            val matches = FifoLocationHelper.searchNearbyPlaces(context, destInput, currentLoc)
            val closest = matches.firstOrNull()
            if (closest != null) {
                val targetName = closest.name.ifBlank { destInput }
                if (openScreen) {
                    val distText = if (closest.distanceMeters < 1200) "${closest.distanceMeters} metros (${closest.walkingMinutes} min a pie)" else "${String.format(java.util.Locale("es", "ES"), "%.1f", closest.distanceMeters / 1000.0)} km"
                    val spoken = "El $targetName más cercano está en ${closest.fullAddressText}, a unos $distText ${closest.cardinalDirection}. Te abrí la ruta en la pantalla."
                    val (navSuccess, appUsed) = FifoLocationHelper.startNavigation(context, "${closest.latitude},${closest.longitude}", appChoice)
                    return SkillResult(
                        success = navSuccess,
                        spokenFeedback = spoken,
                        data = mapOf(
                            "destination" to closest.fullAddressText,
                            "latitude" to closest.latitude,
                            "longitude" to closest.longitude,
                            "distance_meters" to closest.distanceMeters,
                            "walking_minutes" to closest.walkingMinutes,
                            "driving_minutes" to closest.drivingMinutes,
                            "app_used" to appUsed,
                            "screen_opened" to true
                        )
                    )
                } else {
                    // Guía 100% verbal concisa y sin discursos redundantes
                    val spoken = if (closest.distanceMeters < 1200) {
                        "El $targetName más cercano está en ${closest.fullAddressText}, a ${closest.distanceMeters} metros (${closest.walkingMinutes} min caminando ${closest.cardinalDirection}). ¿Quieres que te guíe hacia allá?"
                    } else {
                        val km = String.format(java.util.Locale("es", "ES"), "%.1f", closest.distanceMeters / 1000.0)
                        "El $targetName más cercano está en ${closest.fullAddressText}, a unos $km kilómetros ${closest.cardinalDirection} (${closest.drivingMinutes} min en auto). ¿Quieres que te indique la ruta?"
                    }
                    return SkillResult(
                        success = true,
                        spokenFeedback = spoken,
                        data = mapOf(
                            "destination" to closest.fullAddressText,
                            "latitude" to closest.latitude,
                            "longitude" to closest.longitude,
                            "distance_meters" to closest.distanceMeters,
                            "walking_minutes" to closest.walkingMinutes,
                            "driving_minutes" to closest.drivingMinutes,
                            "screen_opened" to false
                        )
                    )
                }
            }
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
            val combinedSpoken = "Para ir a $resolvedDestination: queda a ${routeGuidance.distanceMeters} metros (${routeGuidance.walkingMinutes} min caminando). Te abrí la ruta en el celular."
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
