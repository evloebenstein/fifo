package com.fifo.voicepipeline.skills

import android.content.Intent
import android.net.Uri
import android.util.Log
import com.fifo.voicepipeline.location.FifoNavigationManager
import com.fifo.voicepipeline.location.LocationAdapter

/**
 * Skill para interactuar con Google Maps y buscar lugares cercanos de interés:
 * locales de OXXO, minimarkets, farmacias de turno, centros de salud (CESFAM),
 * hospitales, parques, bancos, cafeterías, etc.
 *
 * Entrega una respuesta 100% hablada por voz con la calle exacta y la distancia,
 * además de dejar abierto el mapa en pantalla.
 *
 * ## GeoIntentSeam (ADR-0007)
 * No longer holds `android.content.Context`; all geo operations go through
 * [locationAdapter], and map-launch intents go through [intentLauncher].
 * This makes the skill fully testable on the JVM.
 */
class MapsSkill(
    private val locationAdapter: LocationAdapter,
    private val navigationManager: FifoNavigationManager,
    /**
     * Allows tests to capture Intent launches without a real Activity stack.
     * Production code wires this to `context::startActivity`.
     */
    private val intentLauncher: ((Intent) -> Unit)? = null
) : FifoSkill {

    companion object {
        private const val TAG = "MapsSkill"
    }

    override val name: String = "search_nearby_places"

    override val description: String =
        "Busca lugares de interés cercanos al usuario (locales de OXXO, farmacias, supermercados, centros de salud, parques, bancos, etc.), calcula la calle exacta, distancia y orientación para decírselo por voz, y opcionalmente abre el mapa en el celular solo si el usuario pide ver la pantalla."

    override val parameterSchemaJson: String = """
    {
        "type": "object",
        "properties": {
            "place_type": {
                "type": "string",
                "description": "Categoría o nombre del lugar a buscar (ej: 'oxxo', 'farmacia', 'minimarket', 'supermercado', 'lider', 'jumbo', 'centro_salud', 'hospital', 'parque', 'panaderia', 'cafeteria', 'banco')"
            },
            "query_hint": {
                "type": ["string", "null"],
                "description": "Detalle adicional como comuna, nombre específico o calle (ej: 'OXXO', 'Lider', 'Jumbo', 'Cruz Verde', 'Providencia', 'Lider Express')"
            },
            "open_screen_map": {
                "type": ["boolean", "null"],
                "description": "True SOLAMENTE si el usuario pidió de forma explícita ver o abrir el mapa en la pantalla de su celular (ej: 'muéstrame en mi celular la ubicación', 'ábreme el mapa en pantalla'). Por defecto FALSE (guía 100% verbal por voz sin abrir la pantalla)."
            }
        },
        "required": ["place_type"]
    }
    """.trimIndent()

    override suspend fun execute(args: Map<String, Any?>): SkillResult {
        val placeTypeRaw = args["place_type"]?.toString()?.trim()?.ifBlank { "comercio" } ?: "comercio"
        val hint = args["query_hint"]?.toString()?.trim() ?: ""
        val openScreen = (args["open_screen_map"] as? Boolean)
            ?: (args["open_screen_map"]?.toString()?.toBooleanStrictOrNull() ?: false)

        val currentLoc = locationAdapter.getFreshLocation()
        val cleanHint = if (currentLoc.isGpsActive && !currentLoc.city.contains("Providencia", ignoreCase = true)) {
            hint.replace(Regex("(?i)\\b(near|cerca de)?\\s*av\\.?\\s*providencia.*"), "").trim()
        } else {
            hint
        }

        val placeName = when {
            cleanHint.isNotBlank() && (placeTypeRaw == "comercio" || placeTypeRaw == "local") -> cleanHint
            cleanHint.isNotBlank() && !cleanHint.contains(placeTypeRaw, ignoreCase = true) -> "$placeTypeRaw $cleanHint"
            cleanHint.isNotBlank() -> cleanHint
            else -> placeTypeRaw
        }

        val displayName = placeName
        Log.i(TAG, "Buscando lugares cercanos: '$displayName' (openScreen=$openScreen). GPS activo=${currentLoc.isGpsActive} (${currentLoc.latitude}, ${currentLoc.longitude})")

        val rawMatches = locationAdapter.searchNearbyPlaces(displayName, currentLoc)
        val matches = rawMatches.filterNot {
            it.name.equals(currentLoc.city, ignoreCase = true) ||
            it.name.equals("Vitacura", ignoreCase = true) ||
            it.name.equals("Santiago", ignoreCase = true)
        }

        val closest = matches.firstOrNull()
        if (closest != null) {
            navigationManager.setPendingDestination(closest)
            if (openScreen) {
                navigationManager.startNavigation(
                    name = closest.name,
                    latitude = closest.latitude,
                    longitude = closest.longitude,
                    fullAddress = closest.fullAddressText,
                    road = closest.road
                )
            }
        }

        // Detect if original user query was in plural form
        val fullQueryText = "$placeTypeRaw $hint".lowercase()
        val isPlural = fullQueryText.contains("locales") ||
                fullQueryText.contains("mercados") ||
                fullQueryText.contains("supermercados") ||
                fullQueryText.contains("farmacias") ||
                fullQueryText.contains("tiendas") ||
                fullQueryText.contains("negocios") ||
                fullQueryText.contains("panaderias") ||
                fullQueryText.contains("cafeterias") ||
                fullQueryText.contains("cuales") ||
                fullQueryText.contains("cuáles") ||
                fullQueryText.contains("opciones") ||
                fullQueryText.contains("alternativas")

        val spokenFeedback = if (openScreen) {
            if (closest != null) {
                "${closest.name} en ${closest.road.ifBlank { closest.fullAddressText }}."
            } else {
                "Búsqueda de $displayName abierta en el mapa."
            }
        } else {
            if (isPlural && matches.size >= 2) {
                matches.take(3).joinToString("; ") { "${it.name} a ${it.distanceMeters}m" } + "."
            } else if (closest != null) {
                val streetOnly = closest.road.ifBlank { closest.fullAddressText }
                "${closest.name} en $streetOnly a ${closest.distanceMeters}m."
            } else {
                "No se encontraron locales de $displayName cercanos."
            }
        }

        // Open Google Maps ONLY if user explicitly requested the screen
        if (openScreen) {
            launchMapIntent(closest?.latitude, closest?.longitude, currentLoc.latitude, currentLoc.longitude, displayName, currentLoc.isGpsActive)
        } else {
            Log.i(TAG, "Guía 100% verbal solicitada (openScreen=false). No se abre la pantalla de Maps.")
        }

        val topPlacesData = matches.take(3).map {
            mapOf(
                "name" to it.name,
                "description" to it.name,
                "street" to it.road.ifBlank { it.fullAddressText },
                "distance_meters" to it.distanceMeters,
                "walking_minutes" to it.walkingMinutes,
                "cardinal" to it.cardinalDirection,
                "blocks" to it.blocks
            )
        }

        return SkillResult(
            success = true,
            spokenFeedback = spokenFeedback,
            data = mapOf(
                "query" to placeName,
                "gps_active" to currentLoc.isGpsActive,
                "current_address" to currentLoc.address,
                "destination" to (closest?.fullAddressText ?: displayName),
                "destination_lat" to (closest?.latitude ?: currentLoc.latitude),
                "destination_lon" to (closest?.longitude ?: currentLoc.longitude),
                "distance_meters" to (closest?.distanceMeters ?: 0),
                "walking_minutes" to (closest?.walkingMinutes ?: 0),
                "driving_minutes" to (closest?.drivingMinutes ?: 0),
                "screen_opened" to openScreen,
                "is_plural" to isPlural,
                "nearby_places" to topPlacesData
            )
        )
    }

    private fun launchMapIntent(
        destLat: Double?,
        destLon: Double?,
        originLat: Double,
        originLon: Double,
        displayName: String,
        gpsActive: Boolean
    ) {
        try {
            val mapUri = when {
                destLat != null && destLon != null ->
                    Uri.parse("https://www.google.com/maps/dir/?api=1&origin=$originLat,$originLon&destination=$destLat,$destLon&travelmode=walking")
                gpsActive ->
                    Uri.parse("geo:$originLat,$originLon?q=" + Uri.encode(displayName))
                else ->
                    Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode("$displayName cerca de mí"))
            }

            val mapIntent = Intent(Intent.ACTION_VIEW, mapUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage("com.google.android.apps.maps")
            }

            val launcher = intentLauncher
            if (launcher != null) {
                launcher(mapIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error construyendo intent de Maps: ${e.message}")
        }
    }
}
