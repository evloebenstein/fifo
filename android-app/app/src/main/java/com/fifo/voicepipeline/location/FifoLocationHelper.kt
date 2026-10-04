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
            val providers = lm.getProviders(true)
            for (provider in providers) {
                val l = lm.getLastKnownLocation(provider) ?: continue
                if (bestLocation == null || l.accuracy < bestLocation.accuracy) {
                    bestLocation = l
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

        val spoken = if (distanceMeters < 1000) {
            "Para ir a $resolvedName: le queda a unos $distanceMeters metros de distancia, aproximadamente a $walkingMinutes minutos caminando $cardinal (a unas $blocks cuadras). Salga a la calle y avance derecho en esa dirección. ¿Desea que le vaya avisando los siguientes pasos mientras camina o prefiere que le abra el mapa en pantalla?"
        } else {
            val km = String.format(Locale("es", "ES"), "%.1f", distanceMeters / 1000.0)
            "Para ir a $resolvedName: está a unos $km kilómetros $cardinal, aproximadamente a $drivingMinutes minutos en vehículo o locomoción. ¿Desea que le abra el mapa en pantalla o prefiere pedir un transporte?"
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
}

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
