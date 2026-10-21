package com.fifo.voicepipeline.location

import com.fifo.voicepipeline.ui.model.CurrentLocationInfo

/**
 * GeoIntentSeam – location & navigation hardware port.
 *
 * All geo-hardware interactions (GPS reads, geocoding, POI search, map-app
 * intents) are accessed through this interface.  Production code binds
 * [AndroidLocationAdapter]; JVM unit-tests inject [InMemoryLocationAdapter].
 *
 * Design rules:
 *  - No `android.content.Context` leaks across the boundary.
 *  - All suspending functions run on the caller's coroutine scope.
 *  - Implementations MUST be thread-safe.
 */
interface LocationAdapter {

    // ── Location resolution ───────────────────────────────────────────────

    /**
     * Returns the freshest available location, waiting for a hardware fix
     * if needed (suspending).  Never throws; returns a fallback on error.
     */
    suspend fun getFreshLocation(): CurrentLocationInfo

    /**
     * Returns the best cached location synchronously (may be stale).
     */
    fun getCurrentLocation(): CurrentLocationInfo

    // ── Geocoding ─────────────────────────────────────────────────────────

    /**
     * Reverse-geocodes [latitude]/[longitude] to a human address and city.
     * Returns `null` when the geocoder cannot resolve the coordinates.
     */
    fun reverseGeocode(latitude: Double, longitude: Double): Pair<String, String>?

    /**
     * Resolves [destination] and builds spoken route guidance from the
     * current location, including the resolved destination coordinates.
     */
    fun getSpokenRouteGuidance(destination: String): SpokenRouteGuidance

    // ── POI search ────────────────────────────────────────────────────────

    /**
     * Searches nearby points-of-interest matching [query] and returns them
     * sorted by walking distance (closest first).
     */
    suspend fun searchNearbyPlaces(
        query: String,
        currentLocation: CurrentLocationInfo? = null
    ): List<NearbyPlaceMatch>

    // ── Navigation intents ────────────────────────────────────────────────

    /**
     * Launches an external navigation app (Google Maps or Waze) to the
     * given [destination] string.  Returns `(success, appName)`.
     */
    fun startExternalNavigation(
        destination: String,
        app: String = "google_maps"
    ): Pair<Boolean, String>

    /**
     * Opens a map pin in Google Maps at the given coordinates.
     */
    fun openMapPin(latitude: Double, longitude: Double, label: String = "Destino")

    // ── Bearing helpers ───────────────────────────────────────────────────

    /**
     * Returns a cardinal direction label (Spanish) for [bearing] in degrees.
     */
    fun calculateCardinal(bearing: Float): String
}
