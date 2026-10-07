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

        // Si pide un comercio o lugar de interés cercano (ej: OXXO, farmacia)
        val destLower = destInput.lowercase().trim()
        val isStoreQuery = destLower.contains("oxxo") ||
                destLower.contains("farmacia") ||
                destLower.contains("minimarket") ||
                destLower.contains("supermercado")

        if (isStoreQuery) {
            val currentLoc = FifoLocationHelper.getCurrentLocation(context)
            val matches = FifoLocationHelper.searchNearbyPlaces(context, destInput, currentLoc)
            val closest = matches.firstOrNull()
            if (closest != null) {
                if (openScreen) {
                    val spoken = if (closest.distanceMeters < 1200) {
                        "Revisé su ubicación en ${currentLoc.address}. El local de ${closest.name} más cercano está en ${closest.fullAddressText}, a unos ${closest.distanceMeters} metros (a unos ${closest.walkingMinutes} minutos caminando ${closest.cardinalDirection}). Le acabo de abrir la ruta en el mapa en la pantalla de su celular."
                    } else {
                        val km = String.format(java.util.Locale("es", "ES"), "%.1f", closest.distanceMeters / 1000.0)
                        "Revisé su ubicación en ${currentLoc.address}. El local de ${closest.name} más cercano se encuentra en ${closest.fullAddressText}, a unos $km kilómetros. Le acabo de abrir la ruta en el mapa en la pantalla de su celular."
                    }
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
                            "app_used" to appUsed,
                            "screen_opened" to true
                        )
                    )
                } else {
                    // Guía 100% verbal manos libres por voz sin abrir el celular
                    val spoken = if (closest.distanceMeters < 1200) {
                        "Revisé su ubicación en ${currentLoc.address}. El local de ${closest.name} más cercano a usted está en ${closest.fullAddressText}, a unos ${closest.distanceMeters} metros de distancia (unas ${closest.blocks} cuadras, a unos ${closest.walkingMinutes} minutos caminando). Para llegar, salga a la calle y camine ${closest.cardinalDirection} por ${closest.road.ifBlank { "la calle principal" }}. ¿Desea que le vaya indicando los pasos mientras camina, o prefiere que le muestre el mapa en su celular?"
                    } else {
                        val km = String.format(java.util.Locale("es", "ES"), "%.1f", closest.distanceMeters / 1000.0)
                        "Revisé su ubicación en ${currentLoc.address}. El local de ${closest.name} más cercano se encuentra en ${closest.fullAddressText}, a unos $km kilómetros ${closest.cardinalDirection}, a unos ${closest.walkingMinutes} minutos a pie. ¿Desea que le muestre el mapa en la pantalla o prefiere pedir un transporte?"
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
            val combinedSpoken = "Para ir a $resolvedDestination: le queda a unos ${routeGuidance.distanceMeters} metros, a unos ${routeGuidance.walkingMinutes} minutos caminando ${routeGuidance.cardinalDirection}. Le acabo de abrir la ruta en $appUsed en la pantalla del celular."
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
