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

        val currentLoc = FifoLocationHelper.getCurrentLocation(context)
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

        val rawMatches = FifoLocationHelper.searchNearbyPlaces(context, displayName, currentLoc)
        // Descartar cualquier falso positivo que sea el nombre de la comuna o ciudad
        val matches = rawMatches.filterNot {
            it.name.equals(currentLoc.city, ignoreCase = true) ||
            it.name.equals("Vitacura", ignoreCase = true) ||
            it.name.equals("Santiago", ignoreCase = true)
        }

        val closest = matches.firstOrNull()
        if (closest != null) {
            com.fifo.voicepipeline.location.FifoNavigationManager.setPendingDestination(closest)
            if (openScreen) {
                com.fifo.voicepipeline.location.FifoNavigationManager.startNavigation(
                    name = closest.name,
                    latitude = closest.latitude,
                    longitude = closest.longitude,
                    fullAddress = closest.fullAddressText,
                    road = closest.road
                )
            }
        }

        // Detectar si la pregunta original del usuario fue en plural
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

        fun formatDescriptor(name: String, queryCategory: String): String {
            val lower = name.lowercase()
            val qLower = queryCategory.lowercase()
            val cleanName = when {
                lower.contains("ok market") -> "OXXO (anteriormente OK Market)"
                else -> name
            }
            return when {
                lower.contains("farmacia") || qLower.contains("farmacia") -> {
                    if (cleanName.startsWith("farmacia", ignoreCase = true)) "la $cleanName" else "la farmacia $cleanName"
                }
                lower.contains("panaderia") || lower.contains("panadería") || qLower.contains("panaderia") -> {
                    if (cleanName.startsWith("panaderia", ignoreCase = true) || cleanName.startsWith("panadería", ignoreCase = true)) "la $cleanName" else "la panadería $cleanName"
                }
                lower.contains("botilleria") || lower.contains("botillería") -> "la $cleanName"
                lower.contains("cafeteria") || lower.contains("cafetería") -> "la cafetería $cleanName"
                lower.contains("clinica") || lower.contains("clínica") -> "la clínica $cleanName"
                lower.contains("plaza") -> "la plaza $cleanName"
                lower.contains("oxxo") -> "el minimarket OXXO"
                lower.contains("jumbo") -> "el supermercado Jumbo"
                lower.contains("lider") || lower.contains("líder") -> "el supermercado Líder"
                lower.contains("unimarc") -> "el supermercado Unimarc"
                lower.contains("santa isabel") -> "el supermercado Santa Isabel"
                lower.contains("supermercado") || qLower.contains("supermercado") -> {
                    if (cleanName.startsWith("supermercado", ignoreCase = true)) "el $cleanName" else "el supermercado $cleanName"
                }
                lower.contains("minimarket") || qLower.contains("minimarket") -> {
                    if (cleanName.startsWith("minimarket", ignoreCase = true)) "el $cleanName" else "el minimarket $cleanName"
                }
                lower.contains("almacen") || lower.contains("almacén") || qLower.contains("almacen") -> "el almacén $cleanName"
                lower.contains("mercado") || qLower.contains("mercado") -> {
                    if (cleanName.startsWith("mercado", ignoreCase = true)) "el $cleanName" else "el mercado $cleanName"
                }
                lower.contains("banco") -> "el banco $cleanName"
                lower.contains("hospital") -> "el hospital $cleanName"
                lower.contains("cesfam") -> "el CESFAM $cleanName"
                lower.contains("consultorio") -> "el consultorio $cleanName"
                lower.contains("parque") -> "el parque $cleanName"
                lower.contains("restaurant") || lower.contains("restaurante") -> "el restaurante $cleanName"
                else -> "el local $cleanName"
            }
        }

        val spokenFeedback = if (openScreen) {
            // Caso 1: El usuario pidió expresamente ver la ubicación en la pantalla del celular
            if (closest != null) {
                val desc = formatDescriptor(closest.name, placeTypeRaw)
                val streetOnly = closest.road.ifBlank { closest.fullAddressText }
                val distText = if (closest.distanceMeters < 1200) "${closest.distanceMeters} metros" else "${String.format(Locale("es", "ES"), "%.1f", closest.distanceMeters / 1000.0)} kilómetros"
                "El lugar más próximo es $desc en $streetOnly, a unos $distText. Le abrí la ruta en la pantalla de su celular."
            } else {
                "Le abrí el mapa en su celular con la búsqueda de $displayName."
            }
        } else {
            // Caso 2: Guía 100% verbal, natural y variada (manos libres)
            if (isPlural && matches.size >= 2) {
                // Respuesta en plural cuando el usuario consulta por múltiples locales o mercados
                val topPlaces = matches.take(2)
                val p1 = topPlaces[0]
                val p2 = topPlaces[1]
                val desc1 = formatDescriptor(p1.name, placeTypeRaw)
                val desc2 = formatDescriptor(p2.name, placeTypeRaw)
                val dist1 = if (p1.distanceMeters < 1200) "${p1.distanceMeters} metros" else "${String.format(Locale("es", "ES"), "%.1f", p1.distanceMeters / 1000.0)} km"
                val dist2 = if (p2.distanceMeters < 1200) "${p2.distanceMeters} metros" else "${String.format(Locale("es", "ES"), "%.1f", p2.distanceMeters / 1000.0)} km"
                val road1 = p1.road.ifBlank { p1.fullAddressText }
                val road2 = p2.road.ifBlank { p2.fullAddressText }

                val pluralPhrasings = listOf(
                    "Cerca de aquí tiene varias alternativas: $desc1 a unos $dist1 en $road1, y $desc2 a unos $dist2 en $road2. ¿Le gustaría que le indique el camino a alguno de ellos?",
                    "Encontré estas opciones cercanas: primero $desc1 a $dist1 en $road1, y también $desc2 a $dist2 en $road2. ¿Hacia cuál de ellos prefiere que le guíe?",
                    "Por la zona cuenta con $desc1 a unas ${p1.blocks} cuadras en $road1, y $desc2 a ${p2.blocks} cuadras en $road2. ¿Desea que le oriente para llegar?"
                )
                pluralPhrasings.random()
            } else if (closest != null) {
                // Respuesta singular variada y natural, sin plantillas rígidas
                val targetDesc = formatDescriptor(closest.name, placeTypeRaw)
                val streetOnly = closest.road.ifBlank { closest.fullAddressText }
                val distText = if (closest.distanceMeters < 1200) "${closest.distanceMeters} metros" else "${String.format(Locale("es", "ES"), "%.1f", closest.distanceMeters / 1000.0)} kilómetros"
                val minsText = "${closest.walkingMinutes} minutos"
                val cardinal = closest.cardinalDirection
                val blocksText = if (closest.blocks == 1) "1 cuadra" else "${closest.blocks} cuadras"

                val singularPhrasings = listOf(
                    "El más próximo es $targetDesc, ubicado en $streetOnly, a unos $distText ($minsText a pie). ¿Le gustaría que le guíe paso a paso?",
                    "Tiene muy cerca $targetDesc en $streetOnly, a unos $distText caminando $cardinal. ¿Desea que le vaya indicando el camino?",
                    "A unos $distText $cardinal, por $streetOnly, encuentra $targetDesc. ¿Le gustaría que le ayude a llegar?",
                    "El establecimiento más cercano a su ubicación es $targetDesc, en $streetOnly a unas $blocksText. ¿Le gustaría que le oriente para ir?"
                )
                singularPhrasings.random()
            } else {
                if (currentLoc.isGpsActive) {
                    "No encontré locales de $displayName cercanos a su ubicación actual. ¿Desea que le busque en un radio más amplio?"
                } else {
                    "No alcancé a captar la señal GPS de su teléfono para buscar el local de $displayName. ¿Desea que lo intente nuevamente?"
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

        val topPlacesData = matches.take(3).map {
            mapOf(
                "name" to it.name,
                "description" to formatDescriptor(it.name, placeTypeRaw),
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
}
