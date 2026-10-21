package com.fifo.voicepipeline.location

import com.fifo.voicepipeline.ui.model.CurrentLocationInfo

/**
 * Pure-JVM fake implementation of [LocationAdapter] for unit tests.
 *
 * No Android framework dependency: runs on the JVM test runner without
 * Robolectric or an emulator.  Every value is controllable via constructor
 * parameters or direct property assignment, so tests can inject any scenario
 * (good GPS fix, no GPS, specific POI results, etc.).
 *
 * Usage in tests:
 * ```kotlin
 * val fakeLocation = InMemoryLocationAdapter(
 *     fixedLocation = testLocation,
 *     spokenRouteGuidanceResult = testRouteGuidance
 * )
 * val skill = MapsSkill(locationAdapter = fakeLocation)
 * ```
 */
class InMemoryLocationAdapter(
    var fixedLocation: CurrentLocationInfo,
    var nearbyPlacesResult: List<NearbyPlaceMatch> = emptyList(),
    var externalNavigationResult: Pair<Boolean, String> = Pair(true, "Google Maps"),
    var reverseGeocodeResult: Pair<String, String>? = null,
    var spokenRouteGuidanceResult: SpokenRouteGuidance? = null,
    var openMapPinCalled: Boolean = false
) : LocationAdapter {

    // ── Location resolution ───────────────────────────────────────────────

    override suspend fun getFreshLocation(): CurrentLocationInfo = fixedLocation

    override fun getCurrentLocation(): CurrentLocationInfo = fixedLocation

    // ── Geocoding ─────────────────────────────────────────────────────────

    override fun reverseGeocode(latitude: Double, longitude: Double): Pair<String, String>? =
        reverseGeocodeResult

    override fun getSpokenRouteGuidance(destination: String): SpokenRouteGuidance =
        requireNotNull(spokenRouteGuidanceResult) {
            "Configure spokenRouteGuidanceResult for destination '$destination' in this test."
        }

    // ── POI search ────────────────────────────────────────────────────────

    override suspend fun searchNearbyPlaces(
        query: String,
        currentLocation: CurrentLocationInfo?
    ): List<NearbyPlaceMatch> = nearbyPlacesResult

    // ── Navigation intents ────────────────────────────────────────────────

    override fun startExternalNavigation(
        destination: String,
        app: String
    ): Pair<Boolean, String> = externalNavigationResult

    override fun openMapPin(latitude: Double, longitude: Double, label: String) {
        openMapPinCalled = true
    }

    // ── Bearing helpers ───────────────────────────────────────────────────

    override fun calculateCardinal(bearing: Float): String {
        val norm = (bearing + 360) % 360
        return when {
            norm < 22.5 || norm >= 337.5 -> "hacia el norte"
            norm < 67.5  -> "hacia el nororiente"
            norm < 112.5 -> "hacia el oriente"
            norm < 157.5 -> "hacia el suroriente"
            norm < 202.5 -> "hacia el sur"
            norm < 247.5 -> "hacia el suroccidente"
            norm < 292.5 -> "hacia el poniente"
            else         -> "hacia el norponiente"
        }
    }
}
