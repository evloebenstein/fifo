package com.fifo.voicepipeline.skills

import android.location.Location
import android.util.Log
import com.fifo.voicepipeline.data.FifoDataRepository
import com.fifo.voicepipeline.location.FifoNavigationManager
import com.fifo.voicepipeline.location.FifoOrientationHelper
import com.fifo.voicepipeline.location.LocationAdapter

/**
 * Skill que permite a Fifo guiar al usuario hacia un destino:
 * - Guía HABLADA manos libres (no requiere abrir ni mirar el celular).
 * - Calcula distancia, minutos caminando, orientación cardinal y pasos iniciales.
 * - Si el usuario pide expresamente abrir la pantalla ("abre Maps", "abre Waze"),
 *   inicia la app visual en el celular.
 *
 * ## GeoIntentSeam (ADR-0007)
 * Replaced direct `android.content.Context` dependency with [LocationAdapter]
 * and [FifoNavigationManager] constructor injection.  The skill is now fully
 * testable on the JVM without an emulator.
 */
class NavigationDirectionsSkill(
    private val locationAdapter: LocationAdapter,
    private val navigationManager: FifoNavigationManager
) : FifoSkill {

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

        val isConfirmation = destTrimmed in listOf(
            "si", "sí", "por favor", "si por favor", "sí por favor", "guíame", "guiame",
            "vamos", "dale", "ok", "bueno", "claro", "llevame", "llévame", "ayúdame",
            "ayudame", "avanza", "vamos allá", "vamos alla", "indicame", "indícame",
            "confirm_pending", "el local", "la tienda",
            "dije sí", "dije si", "dije sí por favor", "dije si por favor"
        ) || destTrimmed.startsWith("si,") || destTrimmed.startsWith("sí,")
          || destTrimmed.startsWith("dije si") || destTrimmed.startsWith("dije sí")

        val currentLoc = locationAdapter.getFreshLocation()
        val pending = navigationManager.pendingDestination

        // 1. ABSOLUTE PRIORITY: pending destination + user confirmation
        if (pending != null && (isConfirmation || isGenericDestination ||
                    destTrimmed.contains(pending.name, ignoreCase = true) ||
                    destTrimmed.contains(pending.road, ignoreCase = true))) {

            val active = navigationManager.confirmPendingDestination()
                ?: FifoNavigationManager.ActiveDestination(
                    name = pending.name,
                    latitude = pending.latitude,
                    longitude = pending.longitude,
                    fullAddress = pending.fullAddressText,
                    road = pending.road
                )

            val street = active.road.ifBlank { active.fullAddress }
            val desc = when {
                active.name.lowercase().contains("oxxo") -> "el minimarket OXXO"
                active.name.lowercase().contains("farmacia") -> "la farmacia ${active.name}"
                active.name.lowercase().contains("supermercado") -> "el supermercado ${active.name}"
                else -> "el local ${active.name}"
            }

            val userAzimuth = FifoOrientationHelper.currentAzimuthDegrees
            val distResults = FloatArray(2)
            Location.distanceBetween(
                currentLoc.latitude, currentLoc.longitude,
                active.latitude, active.longitude,
                distResults
            )
            val distMeters = distResults[0].toInt().coerceAtLeast(10)
            val targetBearing = (distResults[1] + 360f) % 360f
            val turnInfo = FifoOrientationHelper.getRelativeTurnInstruction(userAzimuth, targetBearing)
            val cardinal = locationAdapter.calculateCardinal(targetBearing)
            val distText = if (distMeters < 1200) "$distMeters metros"
                else "${String.format(java.util.Locale("es", "ES"), "%.1f", distMeters / 1000.0)} km"

            if (openScreen) {
                val (navSuccess, appUsed) = locationAdapter.startExternalNavigation(
                    "${active.latitude},${active.longitude}", appChoice
                )
                val spoken = "¡Vamos hacia $desc en $street! Para comenzar: ${turnInfo.instruction} y avance $distText hacia el $cardinal. Te abrí la ruta en la pantalla."
                return SkillResult(
                    success = navSuccess,
                    spokenFeedback = spoken,
                    data = mapOf(
                        "destination" to active.fullAddress,
                        "latitude" to active.latitude,
                        "longitude" to active.longitude,
                        "distance_meters" to distMeters,
                        "app_used" to appUsed,
                        "screen_opened" to true
                    )
                )
            } else {
                val spoken = "¡Vamos hacia $desc en $street! Para orientarte: ${turnInfo.instruction} y avanza $distText hacia el $cardinal. Yo te voy acompañando paso a paso en el camino, avísame mientras caminas."
                return SkillResult(
                    success = true,
                    spokenFeedback = spoken,
                    data = mapOf(
                        "destination" to active.fullAddress,
                        "latitude" to active.latitude,
                        "longitude" to active.longitude,
                        "distance_meters" to distMeters,
                        "screen_opened" to false,
                        "keep_listening" to true
                    )
                )
            }
        }

        if (isGenericDestination) {
            return SkillResult(
                success = false,
                spokenFeedback = "¿Hacia qué lugar o dirección desea ir? Indíqueme el destino y con gusto le guío paso a paso.",
                data = mapOf("needs_destination" to true)
            )
        }

        // 2. Store/POI query (OXXO, farmacia, supermercado, …)
        val destLower = destInput.lowercase().trim()
        val isStoreQuery = destLower.contains("oxxo") ||
                destLower.contains("farmacia") ||
                destLower.contains("minimarket") ||
                destLower.contains("supermercado") ||
                destLower.contains("super") ||
                destLower.contains("mercado") ||
                destLower.contains("almacen") ||
                destLower.contains("tienda") ||
                destLower.contains("local") ||
                destLower.contains("lider") ||
                destLower.contains("jumbo") ||
                destLower.contains("unimarc") ||
                destLower.contains("santa isabel")

        if (isStoreQuery) {
            val rawMatches = locationAdapter.searchNearbyPlaces(destInput, currentLoc)
            val matches = rawMatches.filterNot {
                it.name.equals(currentLoc.city, ignoreCase = true) ||
                it.name.equals("Vitacura", ignoreCase = true) ||
                it.name.equals("Santiago", ignoreCase = true)
            }
            val closest = matches.firstOrNull()
            if (closest != null) {
                val targetName = closest.name.ifBlank { destInput }

                if (openScreen) {
                    val spoken = "$targetName en ${closest.fullAddressText}."
                    val (navSuccess, appUsed) = locationAdapter.startExternalNavigation(
                        "${closest.latitude},${closest.longitude}", appChoice
                    )
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
                    val isRealTime = args["real_time"] == true || destInput.contains("tiempo real", ignoreCase = true)
                    val street = closest.road.ifBlank { closest.fullAddressText }
                    if (isRealTime) {
                        navigationManager.startNavigation(
                            name = closest.name,
                            latitude = closest.latitude,
                            longitude = closest.longitude,
                            fullAddress = closest.fullAddressText,
                            road = street
                        )
                    } else {
                        navigationManager.setPendingDestination(closest)
                    }
                    val userId = FifoDataRepository.userProfile.value.id
                    val contextIntent = if (userId.isNotBlank()) {
                        com.fifo.voicepipeline.network.FifoBackendClient.fetchNavigationContextualIntent(
                            userId = userId,
                            destination = destInput,
                            lat = currentLoc.latitude,
                            lon = currentLoc.longitude
                        )
                    } else null

                    val spoken = if (isRealTime) {
                        "$targetName: ${closest.relativeDirection} por $street (${closest.distanceMeters}m)."
                    } else {
                        "$targetName en $street a ${closest.distanceMeters}m."
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
                            "purpose_type" to (contextIntent?.purposeType ?: "general"),
                            "contextual_notes" to (contextIntent?.contextualNotes ?: emptyList()),
                            "contextual_recommendations" to (contextIntent?.proactiveRecommendations ?: emptyList()),
                            "screen_opened" to false,
                            "keep_listening" to true
                        )
                    )
                }
            }
        }

        // 3. Address or named destination (including "mi casa")
        val resolvedDestination = if (destInput.contains("casa", ignoreCase = true)) {
            val userAddr = FifoDataRepository.userProfile.value.preferredAddress
            if (!userAddr.isNullOrBlank()) userAddr else destInput
        } else {
            destInput
        }

        Log.i(TAG, "Generando guía hablada hacia: '$resolvedDestination' (abrir pantalla: $openScreen)")

        val userId = FifoDataRepository.userProfile.value.id
        val contextIntent = if (userId.isNotBlank()) {
            com.fifo.voicepipeline.network.FifoBackendClient.fetchNavigationContextualIntent(
                userId = userId,
                destination = resolvedDestination,
                lat = currentLoc.latitude,
                lon = currentLoc.longitude
            )
        } else null

        val routeGuidance = locationAdapter.getSpokenRouteGuidance(resolvedDestination)
        navigationManager.startNavigation(
            name = resolvedDestination,
            latitude = routeGuidance.destinationLat,
            longitude = routeGuidance.destinationLon,
            fullAddress = resolvedDestination,
            road = resolvedDestination
        )

        if (openScreen) {
            val (success, appUsed) = locationAdapter.startExternalNavigation(resolvedDestination, appChoice)
            return SkillResult(
                success = success,
                spokenFeedback = "Ruta abierta hacia $resolvedDestination.",
                data = mapOf(
                    "destination" to resolvedDestination,
                    "distance_meters" to routeGuidance.distanceMeters,
                    "walking_minutes" to routeGuidance.walkingMinutes,
                    "purpose_type" to (contextIntent?.purposeType ?: "general"),
                    "contextual_notes" to (contextIntent?.contextualNotes ?: emptyList()),
                    "contextual_recommendations" to (contextIntent?.proactiveRecommendations ?: emptyList()),
                    "app_used" to appUsed,
                    "screen_opened" to true
                )
            )
        }

        val proactiveTip = contextIntent?.proactiveRecommendations?.firstOrNull()?.let { " ($it)" } ?: ""
        return SkillResult(
            success = true,
            spokenFeedback = "$resolvedDestination a ${routeGuidance.distanceMeters}m.$proactiveTip",
            data = mapOf(
                "destination" to resolvedDestination,
                "distance_meters" to routeGuidance.distanceMeters,
                "walking_minutes" to routeGuidance.walkingMinutes,
                "purpose_type" to (contextIntent?.purposeType ?: "general"),
                "contextual_notes" to (contextIntent?.contextualNotes ?: emptyList()),
                "contextual_recommendations" to (contextIntent?.proactiveRecommendations ?: emptyList()),
                "screen_opened" to false
            )
        )
    }
}
