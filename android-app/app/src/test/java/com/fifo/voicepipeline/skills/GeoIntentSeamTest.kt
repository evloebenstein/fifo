package com.fifo.voicepipeline.skills

import android.location.Location
import com.fifo.voicepipeline.location.FifoNavigationManager
import com.fifo.voicepipeline.location.InMemoryLocationAdapter
import com.fifo.voicepipeline.location.NearbyPlaceMatch
import com.fifo.voicepipeline.location.SpokenRouteGuidance
import com.fifo.voicepipeline.ui.model.CurrentLocationInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * GeoIntentSeam unit tests (ADR-0007).
 *
 * Verifies the navigation state machine and skill flow WITHOUT any Android
 * framework dependency — no Context, no LocationManager, no Geocoder.
 * All hardware is replaced by [InMemoryLocationAdapter].
 */
class GeoIntentSeamTest {

    // ── Fixtures ──────────────────────────────────────────────────────────

    private val vitacuraLocation = CurrentLocationInfo(
        latitude = -33.4172,
        longitude = -70.6088,
        address = "Av. Vitacura 2909",
        city = "Vitacura",
        isGpsActive = true
    )

    private val oxxoNearby = NearbyPlaceMatch(
        name = "OXXO Manquehue",
        road = "Manquehue Norte",
        houseNumber = "1234",
        neighbourhood = "",
        suburb = "Vitacura",
        city = "Vitacura",
        latitude = -33.4155,
        longitude = -70.6070,
        distanceMeters = 230,
        bearing = 15f,
        cardinalDirection = "hacia el norte",
        walkingMinutes = 3,
        drivingMinutes = 1,
        blocks = 2,
        fullAddressText = "Manquehue Norte 1234, Vitacura",
        relativeDirection = "continúa recto"
    )

    private lateinit var locationAdapter: InMemoryLocationAdapter
    private lateinit var navManager: FifoNavigationManager

    @Before
    fun setUp() {
        locationAdapter = InMemoryLocationAdapter(
            fixedLocation = vitacuraLocation,
            nearbyPlacesResult = listOf(oxxoNearby)
        )
        // No LocationManager in JVM tests → pass null provider
        navManager = FifoNavigationManager(
            locationAdapter = locationAdapter,
            locationManagerProvider = { null }
        )
    }

    // ── NavigationManager state machine ───────────────────────────────────

    @Test
    fun `startNavigation sets activeRoute and isNavigating`() {
        navManager.startNavigation(
            name = "OXXO Manquehue",
            latitude = oxxoNearby.latitude,
            longitude = oxxoNearby.longitude,
            fullAddress = oxxoNearby.fullAddressText,
            road = oxxoNearby.road
        )
        assertTrue(navManager.isNavigating)
        assertEquals("OXXO Manquehue", navManager.activeRoute?.name)
    }

    @Test
    fun `stopNavigation clears activeRoute`() {
        navManager.startNavigation("A", 0.0, 0.0, "Addr", "Road")
        navManager.stopNavigation()
        assertFalse(navManager.isNavigating)
        assertNull(navManager.activeRoute)
    }

    @Test
    fun `setPendingDestination stores place for confirmation`() {
        navManager.setPendingDestination(oxxoNearby)
        assertNotNull(navManager.pendingDestination)
        assertEquals("OXXO Manquehue", navManager.pendingDestination?.name)
    }

    @Test
    fun `confirmPendingDestination starts navigation and clears pending`() {
        navManager.setPendingDestination(oxxoNearby)
        val active = navManager.confirmPendingDestination()
        assertNotNull(active)
        assertEquals("OXXO Manquehue", active?.name)
        assertNull(navManager.pendingDestination)
        assertTrue(navManager.isNavigating)
    }

    @Test
    fun `confirmPendingDestination returns null when no pending`() {
        val result = navManager.confirmPendingDestination()
        assertNull(result)
    }

    @Test
    fun `getGuidanceReport returns null when not navigating`() {
        assertNull(navManager.getGuidanceReport(vitacuraLocation))
    }

    @Test
    fun `getGuidanceReport returns report when navigating`() {
        navManager.startNavigation(
            name = "OXXO Manquehue",
            latitude = oxxoNearby.latitude,
            longitude = oxxoNearby.longitude,
            fullAddress = oxxoNearby.fullAddressText,
            road = oxxoNearby.road
        )
        val report = navManager.getGuidanceReport(vitacuraLocation)
        assertNotNull(report)
        assertEquals("OXXO Manquehue", report?.destinationName)
        assertTrue((report?.distanceMeters ?: 0) > 0)
    }

    @Test
    fun `simulateLocationTick stops navigation on arrival within 25m`() {
        navManager.startNavigation(
            name = "OXXO Manquehue",
            latitude = vitacuraLocation.latitude,   // same coords → 0m distance
            longitude = vitacuraLocation.longitude,
            fullAddress = oxxoNearby.fullAddressText,
            road = oxxoNearby.road
        )

        val arrivalLocation = Location("test").apply {
            latitude = vitacuraLocation.latitude
            longitude = vitacuraLocation.longitude
            speed = 1.2f
        }
        navManager.simulateLocationTick(arrivalLocation)

        // The state machine must stop navigation synchronously on arrival.
        // Note: onAutonomousGuidance fires via Handler.post() which is a no-op
        // in pure JVM tests — speech delivery is tested at the integration level.
        assertFalse("Navigation must stop automatically on arrival", navManager.isNavigating)
        assertNull("Active route must be cleared on arrival", navManager.activeRoute)
    }

    // ── MapsSkill through the seam ────────────────────────────────────────

    @Test
    fun `MapsSkill returns closest OXXO spoken feedback`() = runTest {
        val skill = MapsSkill(
            locationAdapter = locationAdapter,
            navigationManager = navManager
        )

        val result = skill.execute(
            mapOf("place_type" to "oxxo", "open_screen_map" to false)
        )

        assertTrue(result.success)
        assertTrue(result.spokenFeedback.contains("OXXO", ignoreCase = true))
        assertTrue(result.spokenFeedback.contains("230m", ignoreCase = true))
    }

    @Test
    fun `MapsSkill sets pending destination after search`() = runTest {
        val skill = MapsSkill(
            locationAdapter = locationAdapter,
            navigationManager = navManager
        )

        skill.execute(mapOf("place_type" to "oxxo"))

        assertNotNull(navManager.pendingDestination)
        assertEquals("OXXO Manquehue", navManager.pendingDestination?.name)
    }

    @Test
    fun `MapsSkill does NOT open map intent when openScreen is false`() = runTest {
        var intentLaunched = false
        val skill = MapsSkill(
            locationAdapter = locationAdapter,
            navigationManager = navManager,
            intentLauncher = { intentLaunched = true }
        )

        skill.execute(mapOf("place_type" to "oxxo", "open_screen_map" to false))

        assertFalse("Intent must not be launched in voice-only mode", intentLaunched)
    }

    @Test
    fun `MapsSkill launches map intent when openScreen is true`() = runTest {
        var intentLaunched = false
        val skill = MapsSkill(
            locationAdapter = locationAdapter,
            navigationManager = navManager,
            intentLauncher = { intentLaunched = true }
        )

        skill.execute(mapOf("place_type" to "oxxo", "open_screen_map" to true))

        assertTrue("Intent must be launched when openScreen=true", intentLaunched)
    }

    // ── NavigationDirectionsSkill confirmation flow ───────────────────────

    @Test
    fun `NavigationDirectionsSkill confirms pending destination on affirmative`() = runTest {
        // Set up pending destination first (as MapsSkill would do)
        navManager.setPendingDestination(oxxoNearby)

        val skill = NavigationDirectionsSkill(
            locationAdapter = locationAdapter,
            navigationManager = navManager
        )

        val result = skill.execute(mapOf("destination" to "sí"))

        assertTrue(result.success)
        assertTrue(navManager.isNavigating)
        assertTrue(result.spokenFeedback.contains("OXXO", ignoreCase = true))
    }

    @Test
    fun `NavigationDirectionsSkill asks for destination when none given`() = runTest {
        val skill = NavigationDirectionsSkill(
            locationAdapter = locationAdapter,
            navigationManager = navManager
        )

        val result = skill.execute(mapOf("destination" to "algun lado"))

        assertFalse(result.success)
        assertTrue(result.data["needs_destination"] == true)
    }

    @Test
    fun `NavigationDirectionsSkill preserves adapter guidance for a named destination`() = runTest {
        val expectedRoute = SpokenRouteGuidance(
            destination = "Consultorio Dra. Rojas",
            distanceMeters = 1850,
            walkingMinutes = 25,
            drivingMinutes = 5,
            cardinalDirection = "hacia el nororiente",
            relativeDirection = "gire levemente a la derecha",
            spokenGuidance = "El consultorio queda a 1.9 kilómetros.",
            destinationLat = -33.4050,
            destinationLon = -70.5800
        )
        locationAdapter.spokenRouteGuidanceResult = expectedRoute
        val skill = NavigationDirectionsSkill(
            locationAdapter = locationAdapter,
            navigationManager = navManager
        )

        val result = skill.execute(mapOf("destination" to "Consultorio Dra. Rojas"))

        assertTrue(result.success)
        assertEquals(expectedRoute.distanceMeters, result.data["distance_meters"])
        assertEquals(expectedRoute.walkingMinutes, result.data["walking_minutes"])
        assertEquals(expectedRoute.destinationLat, navManager.activeRoute?.latitude)
        assertEquals(expectedRoute.destinationLon, navManager.activeRoute?.longitude)
    }
}
