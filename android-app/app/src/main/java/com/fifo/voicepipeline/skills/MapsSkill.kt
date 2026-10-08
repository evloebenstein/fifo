package com.fifo.voicepipeline.skills

import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.util.Log
import com.fifo.voicepipeline.location.FifoLocationHelper
import java.util.Locale

/**
 * Skill para interactuar con Google Maps y buscar lugares cercanos de interés:
 * locales de OXXO, minimarkets, farmacias de turno, centros de salud (CESFAM),
 * hospitales, parques, bancos, cafeterías, etc.
 *
 * Entrega una respuesta 100% hablada por voz con la calle exacta y la distancia,
 * además de dejar abierto el mapa en pantalla.
 */
class MapsSkill(private val context: Context) : FifoSkill {

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

        val placeName = when {
            hint.isNotBlank() && (placeTypeRaw == "comercio" || placeTypeRaw == "local") -> hint
            hint.isNotBlank() && !hint.equals(placeTypeRaw, ignoreCase = true) -> "$placeTypeRaw $hint"
            else -> placeTypeRaw
        }

        val currentLoc = FifoLocationHelper.getCurrentLocation(context)
        val displayName = placeName
        Log.i(TAG, "Buscando lugares cercanos: '$displayName' (openScreen=$openScreen). GPS activo=${currentLoc.isGpsActive} (${currentLoc.latitude}, ${currentLoc.longitude})")

        val matches = FifoLocationHelper.searchNearbyPlaces(context, displayName, currentLoc)
        val closest = matches.firstOrNull()
        val rawName = closest?.name?.ifBlank { displayName } ?: displayName
        val targetName = when {
            rawName.contains("OK Market", ignoreCase = true) -> "un local registrado en el mapa como OK Market (hoy convertido a OXXO)"
            else -> rawName
        }

        val spokenFeedback = if (openScreen) {
            // Caso 1: El usuario pidió expresamente ver la ubicación en su celular
            if (closest != null) {
                val streetOnly = closest.road.ifBlank { closest.fullAddressText }
                val distText = if (closest.distanceMeters < 1200) "${closest.distanceMeters} metros" else "${String.format(Locale("es", "ES"), "%.1f", closest.distanceMeters / 1000.0)} kilómetros"
                "El $targetName más cercano está en $streetOnly, a unos $distText. Le abrí la ruta en la pantalla de su celular."
            } else {
                "Le abrí el mapa en su celular con la búsqueda de $displayName."
            }
        } else {
            // Caso 2: Guía 100% verbal y concisa (comportamiento predeterminado)
            if (closest != null) {
                val streetOnly = closest.road.ifBlank { closest.fullAddressText }
                if (closest.distanceMeters < 1200) {
                    "El $targetName más cercano está en $streetOnly, a unos ${closest.distanceMeters} metros (unos ${closest.walkingMinutes} minutos caminando). ¿Desea que le abra el mapa en su celular?"
                } else {
                    val km = String.format(Locale("es", "ES"), "%.1f", closest.distanceMeters / 1000.0)
                    "El $targetName más cercano se encuentra en $streetOnly, a unos $km kilómetros. ¿Desea que le abra la ruta?"
                }
            } else {
                if (currentLoc.isGpsActive) {
                    "No encontré locales de $displayName cercanos a su ubicación actual. ¿Desea que le abra el mapa en su celular?"
                } else {
                    "No alcancé a captar la señal GPS de su teléfono para buscar el local de $displayName. ¿Desea abrir el mapa?"
                }
            }
        }

        // Abrir Google Maps ÚNICAMENTE si el usuario pidió explícitamente abrir la pantalla
        if (openScreen) {
            try {
                val mapUri = if (closest != null) {
                    Uri.parse("https://www.google.com/maps/dir/?api=1&origin=${currentLoc.latitude},${currentLoc.longitude}&destination=${closest.latitude},${closest.longitude}&travelmode=walking")
                } else if (currentLoc.isGpsActive) {
                    Uri.parse("geo:${currentLoc.latitude},${currentLoc.longitude}?q=" + Uri.encode(displayName))
                } else {
                    Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode("$displayName cerca de mí"))
                }

                val mapIntent = Intent(Intent.ACTION_VIEW, mapUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    setPackage("com.google.android.apps.maps")
                }

                try {
                    context.startActivity(mapIntent)
                } catch (e: Exception) {
                    val fallbackIntent = Intent(Intent.ACTION_VIEW, mapUri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallbackIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error abriendo Maps: ${e.message}")
            }
        } else {
            Log.i(TAG, "Guía 100% verbal solicitada (openScreen=false). No se abre la pantalla de Maps.")
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
                "screen_opened" to openScreen
            )
        )
    }
}
