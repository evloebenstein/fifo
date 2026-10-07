package com.fifo.voicepipeline.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.fifo.voicepipeline.data.FifoDataRepository
import com.fifo.voicepipeline.ui.model.CurrentLocationInfo
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

/**
 * Utilidad para interactuar con el módulo GPS del teléfono celular:
 * - Detección de ubicación actual para preguntas "¿Fifo, dónde estamos?"
 * - Geocodificación inversa a direcciones humanas amigables
 * - Navegación paso a paso con Google Maps y Waze
 * - Guardado de última ubicación conocida del robot físico Fifo
 */
object FifoLocationHelper {

    private const val TAG = "FifoLocationHelper"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /**
     * Obtiene la ubicación geográfica actual del celular.
     * Si no hay permisos o GPS apagado, retorna la ubicación de perfil como fallback seguro.
     */
    @SuppressLint("MissingPermission")
    fun getCurrentLocation(context: Context): CurrentLocationInfo {
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val defaultCity = FifoDataRepository.userProfile.value.city.ifBlank { "Santiago, Chile" }
        val defaultAddress = FifoDataRepository.userProfile.value.preferredAddress.ifBlank { "Av. Providencia 1234, Providencia" }

        if (!hasFine && !hasCoarse) {
            Log.w(TAG, "Permisos de ubicación no otorgados; usando ubicación de perfil.")
            return CurrentLocationInfo(
                latitude = -33.4255,
                longitude = -70.6143,
                address = defaultAddress,
                city = defaultCity,
                isGpsActive = false
            )
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            return CurrentLocationInfo(
                latitude = -33.4255,
                longitude = -70.6143,
                address = defaultAddress,
                city = defaultCity,
                isGpsActive = false
            )
        }

        var bestLocation: Location? = null
        try {
            val candidateProviders = mutableListOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                candidateProviders.add(0, LocationManager.FUSED_PROVIDER)
            }
            for (provider in candidateProviders) {
                try {
                    val l = lm.getLastKnownLocation(provider) ?: continue
                    if (bestLocation == null || l.accuracy < bestLocation.accuracy) {
                        bestLocation = l
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "Permiso denegado para provider $provider")
                } catch (e: Exception) {
                    Log.d(TAG, "Provider $provider no disponible: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo última ubicación conocida: ${e.message}")
        }

        if (bestLocation == null) {
            return CurrentLocationInfo(
                latitude = -33.4255,
                longitude = -70.6143,
                address = defaultAddress,
                city = defaultCity,
                isGpsActive = false
            )
        }

        val lat = bestLocation.latitude
        val lon = bestLocation.longitude
        val addressName = reverseGeocode(context, lat, lon) ?: defaultAddress

        return CurrentLocationInfo(
            latitude = lat,
            longitude = lon,
            address = addressName,
            city = defaultCity,
            isGpsActive = true
        )
    }

    /**
     * Convierte coordenadas de latitud/longitud en una dirección humana en español.
     */
    fun reverseGeocode(context: Context, latitude: Double, longitude: Double): String? {
        return try {
            val geocoder = Geocoder(context, Locale("es", "CL"))
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(latitude, longitude, 1)
            if (!addresses.isNullOrEmpty()) {
                val addr = addresses[0]
                val thoroughfare = addr.thoroughfare ?: ""
                val subThoroughfare = addr.subThoroughfare ?: ""
                val locality = addr.locality ?: addr.subAdminArea ?: ""
                val feature = addr.featureName ?: ""

                when {
                    thoroughfare.isNotBlank() && subThoroughfare.isNotBlank() ->
                        "$thoroughfare $subThoroughfare, $locality".trim().removePrefix(",").trim()
                    thoroughfare.isNotBlank() ->
                        "$thoroughfare, $locality".trim().removePrefix(",").trim()
                    feature.isNotBlank() ->
                        "$feature, $locality".trim().removePrefix(",").trim()
                    else -> addr.getAddressLine(0)
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error en Geocoder: ${e.message}")
            null
        }
    }

    /**
     * Inicia la navegación paso a paso hacia un destino usando Google Maps o Waze.
     *
     * @param context Contexto de la app
     * @param destination Dirección, nombre de lugar o coordenadas
     * @param app "google_maps" o "waze"
     * @return Pair(éxito, mensaje descriptivo)
     */
    fun startNavigation(
        context: Context,
        destination: String,
        app: String = "google_maps"
    ): Pair<Boolean, String> {
        val dest = destination.trim()
        if (dest.isBlank()) {
            return Pair(false, "No se indicó un destino válido.")
        }

        val targetApp = app.lowercase().trim()

        if (targetApp == "waze") {
            try {
                val wazeUri = Uri.parse("waze://?q=${Uri.encode(dest)}&navigate=yes")
                val intent = Intent(Intent.ACTION_VIEW, wazeUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return Pair(true, "Waze")
            } catch (e: Exception) {
                Log.w(TAG, "Waze no está instalado, intentando con Google Maps: ${e.message}")
            }
        }

        // Google Maps (predeterminado o fallback de Waze)
        return try {
            val navUri = Uri.parse("google.navigation:q=${Uri.encode(dest)}&mode=d")
            val mapIntent = Intent(Intent.ACTION_VIEW, navUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage("com.google.android.apps.maps")
            }
            context.startActivity(mapIntent)
            Pair(true, "Google Maps")
        } catch (e: Exception) {
            // Fallback genérico a cualquier visor geo
            try {
                val geoUri = Uri.parse("geo:0,0?q=${Uri.encode(dest)}")
                val fallbackIntent = Intent(Intent.ACTION_VIEW, geoUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
                Pair(true, "el Mapa")
            } catch (ex: Exception) {
                Log.e(TAG, "No se pudo abrir app de mapas: ${ex.message}")
                Pair(false, "Error al abrir la aplicación de mapas")
            }
        }
    }

    /**
     * Genera indicaciones de navegación habladas (guía verbal manos libres),
     * permitiendo al adulto mayor saber hacia dónde caminar, la distancia y el tiempo
     * SIN necesidad de sacar el celular del bolsillo ni mirar la pantalla.
     */
    fun getSpokenRouteGuidance(context: Context, destination: String): SpokenRouteGuidance {
        val currentLoc = getCurrentLocation(context)
        val defaultAddress = FifoDataRepository.userProfile.value.preferredAddress

        var destLat = currentLoc.latitude
        var destLon = currentLoc.longitude
        var resolvedName = destination

        try {
            val geocoder = Geocoder(context, Locale("es", "CL"))
            @Suppress("DEPRECATION")
            val matches = geocoder.getFromLocationName(destination, 1)
            if (!matches.isNullOrEmpty()) {
                destLat = matches[0].latitude
                destLon = matches[0].longitude
                resolvedName = matches[0].featureName ?: matches[0].thoroughfare ?: destination
            } else {
                // Si no encuentra la dirección exacta, estimar offset suave
                destLat = currentLoc.latitude + 0.003
                destLon = currentLoc.longitude + 0.002
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error geocodificando destino '$destination': ${e.message}")
            destLat = currentLoc.latitude + 0.003
            destLon = currentLoc.longitude + 0.002
        }

        // Calcular distancia y orientación
        val results = FloatArray(2)
        Location.distanceBetween(currentLoc.latitude, currentLoc.longitude, destLat, destLon, results)
        val distanceMeters = results[0].toInt().coerceAtLeast(150)
        val initialBearing = (results[1] + 360) % 360

        val cardinal = when (initialBearing) {
            in 337.5..360.0, in 0.0..22.5 -> "hacia el norte"
            in 22.5..67.5 -> "hacia el nororiente"
            in 67.5..112.5 -> "hacia el oriente"
            in 112.5..157.5 -> "hacia el suroriente"
            in 157.5..202.5 -> "hacia el sur"
            in 202.5..247.5 -> "hacia el suroccidente"
            in 247.5..292.5 -> "hacia el poniente"
            else -> "hacia el norponiente"
        }

        val blocks = (distanceMeters / 100).coerceAtLeast(1)
        val walkingMinutes = (distanceMeters / 75).coerceAtLeast(2) // 75 m/min (~4.5 km/h)
        val drivingMinutes = (distanceMeters / 400).coerceAtLeast(1)

        val spoken = if (distanceMeters < 1200) {
            "Para ir a $resolvedName: queda a unos $distanceMeters metros ($walkingMinutes minutos caminando $cardinal). ¿Te voy indicando los pasos?"
        } else {
            val km = String.format(Locale("es", "ES"), "%.1f", distanceMeters / 1000.0)
            "Para ir a $resolvedName: queda a unos $km kilómetros $cardinal ($drivingMinutes min en vehículo o $walkingMinutes min a pie). ¿Te indico la ruta?"
        }

        return SpokenRouteGuidance(
            destination = resolvedName,
            distanceMeters = distanceMeters,
            walkingMinutes = walkingMinutes,
            drivingMinutes = drivingMinutes,
            cardinalDirection = cardinal,
            spokenGuidance = spoken
        )
    }

    /**
     * Abre Google Maps mostrando un punto exacto en el mapa con un marcador (ej: última ubicación de Fifo).
     */
    fun openMapPin(
        context: Context,
        latitude: Double,
        longitude: Double,
        label: String = "Última ubicación de Fifo"
    ) {
        try {
            val uri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude(${Uri.encode(label)})")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error abriendo punto en mapa: ${e.message}")
        }
    }

    /**
     * Calcula la orientación cardinal en español a partir del bearing en grados.
     */
    fun calculateCardinal(bearing: Float): String {
        val norm = (bearing + 360) % 360
        return when (norm) {
            in 337.5..360.0, in 0.0..22.5 -> "hacia el norte"
            in 22.5..67.5 -> "hacia el nororiente"
            in 67.5..112.5 -> "hacia el oriente"
            in 112.5..157.5 -> "hacia el suroriente"
            in 157.5..202.5 -> "hacia el sur"
            in 202.5..247.5 -> "hacia el suroccidente"
            in 247.5..292.5 -> "hacia el poniente"
            else -> "hacia el norponiente"
        }
    }

    /**
     * Consulta OpenStreetMap Nominatim para un término acotado dentro de un viewbox alrededor del usuario.
     */
    private fun fetchNominatimPlaces(
        query: String,
        currentLoc: CurrentLocationInfo,
        delta: Double,
        limit: Int
    ): List<NearbyPlaceMatch> {
        val minLon = currentLoc.longitude - delta
        val maxLon = currentLoc.longitude + delta
        val minLat = currentLoc.latitude - delta
        val maxLat = currentLoc.latitude + delta

        val boundedUrl = "https://nominatim.openstreetmap.org/search?q=${Uri.encode(query)}&format=json&addressdetails=1&viewbox=${minLon},${maxLat},${maxLon},${minLat}&bounded=1&limit=$limit"

        val request = Request.Builder()
            .url(boundedUrl)
            .header("User-Agent", "FifoVoiceApp/1.0 (Android; info@fiforobot.com)")
            .build()

        val list = mutableListOf<NearbyPlaceMatch>()
        try {
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (response.isSuccessful && body.isNotBlank()) {
                val jsonArr = JsonParser.parseString(body).asJsonArray
                for (elem in jsonArr) {
                    val item = elem.asJsonObject
                    val lat = item.get("lat")?.asDouble ?: continue
                    val lon = item.get("lon")?.asDouble ?: continue
                    val addrObj = if (item.has("address") && !item.get("address").isJsonNull) item.getAsJsonObject("address") else null
                    val road = addrObj?.get("road")?.asString ?: ""
                    val houseNumber = addrObj?.get("house_number")?.asString ?: ""
                    val neighbourhood = addrObj?.get("neighbourhood")?.asString ?: ""
                    val suburb = addrObj?.get("suburb")?.asString ?: ""
                    val city = addrObj?.get("city")?.asString ?: addrObj?.get("county")?.asString ?: currentLoc.city
                    val rawName = item.get("name")?.asString
                    val shopType = addrObj?.get("shop")?.asString
                    val amenityType = addrObj?.get("amenity")?.asString

                    val shopName = when {
                        !rawName.isNullOrBlank() -> rawName
                        !shopType.isNullOrBlank() -> shopType.replaceFirstChar { it.uppercase() }
                        !amenityType.isNullOrBlank() -> amenityType.replaceFirstChar { it.uppercase() }
                        else -> query.replaceFirstChar { it.uppercase() }
                    }

                    val distResults = FloatArray(2)
                    Location.distanceBetween(currentLoc.latitude, currentLoc.longitude, lat, lon, distResults)
                    val distMeters = distResults[0].toInt().coerceAtLeast(20)
                    val bearing = (distResults[1] + 360) % 360
                    val cardinal = calculateCardinal(bearing)
                    val walkingMin = (distMeters / 75).coerceAtLeast(1)
                    val drivingMin = (distMeters / 400).coerceAtLeast(1)
                    val blocks = (distMeters / 100).coerceAtLeast(1)

                    val streetText = if (road.isNotBlank()) {
                        if (houseNumber.isNotBlank()) "$road $houseNumber" else road
                    } else {
                        shopName
                    }
                    val areaText = if (suburb.isNotBlank()) ", $suburb" else if (neighbourhood.isNotBlank()) ", $neighbourhood" else ""
                    val fullAddress = "$streetText$areaText"

                    list.add(
                        NearbyPlaceMatch(
                            name = shopName,
                            road = road,
                            houseNumber = houseNumber,
                            neighbourhood = neighbourhood,
                            suburb = suburb,
                            city = city,
                            latitude = lat,
                            longitude = lon,
                            distanceMeters = distMeters,
                            bearing = bearing,
                            cardinalDirection = cardinal,
                            walkingMinutes = walkingMin,
                            drivingMinutes = drivingMin,
                            blocks = blocks,
                            fullAddressText = fullAddress
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error en fetchNominatimPlaces ('$query'): ${e.message}")
        }
        return list
    }

    /**
     * Busca comercios y puntos de interés cercanos (supermercados, OXXO, farmacias, etc.)
     * consultando OpenStreetMap con términos optimizados para Chile y la zona inmediata,
     * calculando distancia real, cuadras, minutos a pie, minutos en auto y orientación.
     */
    suspend fun searchNearbyPlaces(
        context: Context,
        query: String,
        currentLocation: CurrentLocationInfo? = null
    ): List<NearbyPlaceMatch> = withContext(Dispatchers.IO) {
        val currentLoc = currentLocation ?: getCurrentLocation(context)
        val queryLower = query.lowercase().trim()

        val searchTerms = when {
            queryLower.contains("supermercado") || queryLower.contains("super") || queryLower.contains("súper") -> {
                when {
                    queryLower.contains("lider") -> listOf("lider", "supermarket", "supermercado")
                    queryLower.contains("jumbo") -> listOf("jumbo", "supermarket", "supermercado")
                    queryLower.contains("unimarc") -> listOf("unimarc", "supermarket", "supermercado")
                    queryLower.contains("santa isabel") -> listOf("santa isabel", "supermarket", "supermercado")
                    queryLower.contains("alvi") -> listOf("alvi", "supermarket", "supermercado")
                    else -> listOf("supermarket", "lider", "unimarc", "supermercado", "jumbo", "santa isabel")
                }
            }
            queryLower.contains("lider") -> listOf("lider", "supermarket")
            queryLower.contains("jumbo") -> listOf("jumbo", "supermarket")
            queryLower.contains("unimarc") -> listOf("unimarc", "supermarket")
            queryLower.contains("santa isabel") -> listOf("santa isabel", "supermarket")
            queryLower.contains("oxxo") -> listOf("oxxo", "ok market")
            queryLower.contains("farmacia") -> listOf("pharmacy", "farmacia", "cruz verde", "ahumada", "salcobrand")
            queryLower.contains("minimarket") || queryLower.contains("almacen") || queryLower.contains("almacén") -> listOf("convenience", "minimarket", "almacen")
            queryLower.contains("panaderia") || queryLower.contains("panadería") -> listOf("bakery", "panaderia")
            queryLower.contains("cafe") || queryLower.contains("café") || queryLower.contains("cafeteria") || queryLower.contains("cafetería") -> listOf("cafe", "cafeteria")
            queryLower.contains("restaurante") || queryLower.contains("restaurant") || queryLower.contains("comida") -> listOf("restaurant", "comida")
            queryLower.contains("comercio") || queryLower.contains("local") || queryLower.contains("tienda") || queryLower.contains("negocio") || queryLower.contains("alrededor") ->
                listOf("convenience", "supermarket", "bakery", "shop", "cafe", "pharmacy")
            queryLower.contains("banco") -> listOf("bank", "banco")
            queryLower.contains("hospital") || queryLower.contains("cesfam") || queryLower.contains("consultorio") -> listOf("hospital", "clinic", "cesfam", "consultorio")
            queryLower.contains("parque") || queryLower.contains("plaza") -> listOf("park", "plaza", "parque")
            else -> listOf(query.trim())
        }

        val resultsList = mutableListOf<NearbyPlaceMatch>()
        val seenCoords = mutableSetOf<String>()

        // Fase 1: Búsqueda acotada al entorno inmediato (~2.5 km a la redonda)
        val deltaNear = 0.025
        for (term in searchTerms.take(4)) {
            val matches = fetchNominatimPlaces(term, currentLoc, delta = deltaNear, limit = 15)
            for (m in matches) {
                val coordKey = "${String.format(Locale.US, "%.4f", m.latitude)},${String.format(Locale.US, "%.4f", m.longitude)}"
                if (seenCoords.add(coordKey)) {
                    resultsList.add(m)
                }
            }
        }

        // Fase 2: Si no hubo resultados en 2.5 km, ampliar radio a ~5 km con los términos principales
        if (resultsList.isEmpty()) {
            val deltaExpanded = 0.05
            for (term in searchTerms.take(2)) {
                val matches = fetchNominatimPlaces(term, currentLoc, delta = deltaExpanded, limit = 15)
                for (m in matches) {
                    val coordKey = "${String.format(Locale.US, "%.4f", m.latitude)},${String.format(Locale.US, "%.4f", m.longitude)}"
                    if (seenCoords.add(coordKey)) {
                        resultsList.add(m)
                    }
                }
            }
        }

        // Fase 3: Fallback con Geocoder si Nominatim no arrojó resultados
        if (resultsList.isEmpty()) {
            val fallbackQuery = searchTerms.firstOrNull() ?: query
            try {
                val geocoder = Geocoder(context, Locale("es", "CL"))
                @Suppress("DEPRECATION")
                val matches = geocoder.getFromLocationName("$fallbackQuery, ${currentLoc.city}", 5)
                if (!matches.isNullOrEmpty()) {
                    for (m in matches) {
                        val distResults = FloatArray(2)
                        Location.distanceBetween(currentLoc.latitude, currentLoc.longitude, m.latitude, m.longitude, distResults)
                        val distMeters = distResults[0].toInt().coerceAtLeast(50)
                        val bearing = (distResults[1] + 360) % 360
                        val cardinal = calculateCardinal(bearing)
                        val walkingMin = (distMeters / 75).coerceAtLeast(1)
                        val drivingMin = (distMeters / 400).coerceAtLeast(1)
                        val blocks = (distMeters / 100).coerceAtLeast(1)
                        val road = m.thoroughfare ?: m.featureName ?: fallbackQuery
                        val houseNumber = m.subThoroughfare ?: ""
                        val suburb = m.subLocality ?: m.locality ?: ""
                        val streetText = if (houseNumber.isNotBlank()) "$road $houseNumber" else road
                        val fullAddress = if (suburb.isNotBlank()) "$streetText, $suburb" else streetText

                        resultsList.add(
                            NearbyPlaceMatch(
                                name = m.featureName ?: fallbackQuery.replaceFirstChar { it.uppercase() },
                                road = road,
                                houseNumber = houseNumber,
                                neighbourhood = "",
                                suburb = suburb,
                                city = currentLoc.city,
                                latitude = m.latitude,
                                longitude = m.longitude,
                                distanceMeters = distMeters,
                                bearing = bearing,
                                cardinalDirection = cardinal,
                                walkingMinutes = walkingMin,
                                drivingMinutes = drivingMin,
                                blocks = blocks,
                                fullAddressText = fullAddress
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error en Geocoder fallback: ${e.message}")
            }
        }

        resultsList.sortBy { it.distanceMeters }
        resultsList
    }
}

/**
 * Resultado detallado de un lugar de interés cercano encontrado.
 */
data class NearbyPlaceMatch(
    val name: String,
    val road: String,
    val houseNumber: String,
    val neighbourhood: String,
    val suburb: String,
    val city: String,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Int,
    val bearing: Float,
    val cardinalDirection: String,
    val walkingMinutes: Int,
    val drivingMinutes: Int,
    val blocks: Int,
    val fullAddressText: String
)

/**
 * Resultado estructurado de guía de navegación hablada manos libres.
 */
data class SpokenRouteGuidance(
    val destination: String,
    val distanceMeters: Int,
    val walkingMinutes: Int,
    val drivingMinutes: Int,
    val cardinalDirection: String,
    val spokenGuidance: String
)
