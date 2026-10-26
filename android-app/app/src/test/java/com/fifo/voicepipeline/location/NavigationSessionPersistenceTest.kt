package com.fifo.voicepipeline.location

import com.fifo.voicepipeline.ui.model.CurrentLocationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persistencia de la ruta activa (ADR-0041).
 *
 * Cerrar la app no debe borrar el acompañamiento: la foto guardada repone el
 * destino, los encargos, la pausa y la corrección al volver a arrancar. Una
 * sesión demasiado vieja se descarta para no guiar hacia un viaje terminado.
 */
class NavigationSessionPersistenceTest {

    private val currentLocation = CurrentLocationInfo(
        latitude = -33.4172,
        longitude = -70.6088,
        address = "Av. Vitacura 2909",
        city = "Vitacura",
        isGpsActive = true
    )

    private fun newManager(store: NavigationSessionStore) = FifoNavigationManager(
        locationAdapter = InMemoryLocationAdapter(fixedLocation = currentLocation),
        locationManagerProvider = { null },
        sessionStore = store
    )

    private fun FifoNavigationManager.startRoute(name: String = "OXXO Manquehue") {
        startNavigation(
            name = name,
            latitude = currentLocation.latitude,
            longitude = currentLocation.longitude,
            fullAddress = "Manquehue Norte 1234, Vitacura",
            road = "Manquehue Norte"
        )
    }

    // ── Snapshot / restore de la memoria ──────────────────────────────────

    @Test
    fun `snapshot and restore round-trip the session state`() {
        var now = 1_000_000L
        val original = NavigationSessionMemory(clock = { now })
        original.addStop("comprar pan")
        original.pause("conversando", 10)
        original.recordCorrection("la ruta sigue por Manquehue")

        val restored = NavigationSessionMemory(clock = { now })
        restored.restore(original.snapshot())

        assertEquals(listOf("comprar pan"), restored.pendingStops().map { it.label })
        assertTrue(restored.isPaused())
        assertEquals("conversando", restored.pause?.reason)
        assertEquals(10, restored.remainingPauseMinutes())
        assertEquals("la ruta sigue por Manquehue", restored.lastCorrection)
    }

    @Test
    fun `restore drops a pause whose deadline already passed`() {
        val memory = NavigationSessionMemory(clock = { 1_000_000L })
        memory.restore(
            NavigationSessionMemory.Snapshot(
                stops = emptyList(),
                pause = NavigationSessionMemory.Pause(
                    reason = "descansando",
                    untilMillis = 900_000L,
                    startedAtMillis = 800_000L
                ),
                lastCorrection = null,
                lastCorrectionAtMillis = 0L
            )
        )

        assertFalse(memory.isPaused())
        assertNull(memory.pause)
    }

    @Test
    fun `onChanged only fires for effective mutations`() {
        val memory = NavigationSessionMemory(clock = { 1_000_000L })
        var changes = 0
        memory.onChanged = { changes++ }

        memory.addStop("comprar pan")
        memory.addStop("COMPRAR PAN")
        memory.pause("pausa", null)
        memory.resume()
        memory.resume()
        memory.recordCorrection("por Manquehue")
        memory.recordCorrection("   ")
        memory.clearCorrection()
        memory.reset()

        assertEquals(6, changes)
    }

    @Test
    fun `restore does not notify the store`() {
        val memory = NavigationSessionMemory(clock = { 1_000_000L })
        var changes = 0
        memory.onChanged = { changes++ }

        memory.restore(
            NavigationSessionMemory.Snapshot(
                stops = listOf(NavigationSessionMemory.PlannedStop("pan", 1_000L)),
                pause = null,
                lastCorrection = null,
                lastCorrectionAtMillis = 0L
            )
        )

        assertEquals(0, changes)
    }

    // ── Persistencia desde el manager ─────────────────────────────────────

    @Test
    fun `starting a route persists the destination`() {
        val store = InMemoryNavigationSessionStore()
        newManager(store).startRoute()

        val saved = store.persisted
        assertNotNull(saved)
        assertEquals("OXXO Manquehue", saved?.destinationName)
        assertEquals("Manquehue Norte 1234, Vitacura", saved?.fullAddress)
    }

    @Test
    fun `session changes are persisted as they happen`() {
        val store = InMemoryNavigationSessionStore()
        val manager = newManager(store)
        manager.startRoute()

        manager.sessionMemory.addStop("comprar pan")
        manager.sessionMemory.pause("conversando con un vecino", 5)
        manager.sessionMemory.recordCorrection("la ruta sigue por Manquehue")

        val saved = store.persisted
        assertEquals(listOf("comprar pan"), saved?.stops?.map { it.label })
        assertEquals("conversando con un vecino", saved?.pauseReason)
        assertNotNull(saved?.pauseUntilMillis)
        assertEquals("la ruta sigue por Manquehue", saved?.lastCorrection)
    }

    @Test
    fun `stopping a route clears the persisted session`() {
        val store = InMemoryNavigationSessionStore()
        val manager = newManager(store)
        manager.startRoute()
        assertNotNull(store.persisted)

        manager.stopNavigation()

        assertNull(store.persisted)
    }

    // ── Restauración al arrancar ──────────────────────────────────────────

    @Test
    fun `restore brings back the route and its session after a restart`() {
        val store = InMemoryNavigationSessionStore()
        val before = newManager(store)
        before.startRoute()
        before.sessionMemory.addStop("comprar pan")
        before.sessionMemory.pause("conversando", 10)
        before.sessionMemory.recordCorrection("la ruta sigue por Manquehue")

        val after = newManager(store)
        val restored = after.restorePersistedSession()

        assertNotNull(restored)
        assertEquals("OXXO Manquehue", restored?.name)
        assertTrue(after.isNavigating)
        assertEquals(listOf("comprar pan"), after.sessionMemory.pendingStops().map { it.label })
        assertTrue(after.sessionMemory.isPaused())
        assertEquals("conversando", after.sessionMemory.pause?.reason)
        assertEquals("la ruta sigue por Manquehue", after.sessionMemory.lastCorrection)
    }

    @Test
    fun `a session older than six hours is discarded`() {
        val store = InMemoryNavigationSessionStore()
        val now = System.currentTimeMillis()
        store.save(
            PersistedNavigationSession(
                savedAtMillis = now - PersistedNavigationSession.DEFAULT_TTL_MILLIS - 1_000L,
                destinationName = "OXXO Manquehue",
                latitude = currentLocation.latitude,
                longitude = currentLocation.longitude,
                fullAddress = "Manquehue Norte 1234, Vitacura",
                road = "Manquehue Norte",
                routeStartedAtMillis = now - 7 * 60 * 60 * 1000L
            )
        )

        val manager = newManager(store)
        assertNull(manager.restorePersistedSession(nowMillis = now))
        assertFalse(manager.isNavigating)
        assertNull(store.persisted)
    }

    @Test
    fun `restore keeps an active route and does not overwrite it`() {
        val store = InMemoryNavigationSessionStore()
        val manager = newManager(store)
        manager.startRoute("Farmacia")

        val restored = manager.restorePersistedSession()

        assertEquals("Farmacia", restored?.name)
        assertEquals("Farmacia", manager.activeRoute?.name)
    }

    @Test
    fun `restore without a persisted session changes nothing`() {
        val manager = newManager(InMemoryNavigationSessionStore())

        assertNull(manager.restorePersistedSession())
        assertFalse(manager.isNavigating)
    }
}
