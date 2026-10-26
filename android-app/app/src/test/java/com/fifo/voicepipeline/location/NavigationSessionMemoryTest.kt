package com.fifo.voicepipeline.location

import android.location.Location
import com.fifo.voicepipeline.ui.model.CurrentLocationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Estado conversacional de una ruta activa (ADR-0040).
 *
 * Estos tests fijan el contrato de [NavigationSessionMemory]: encargos en el
 * camino, pausas con y sin plazo, correcciones y el bloque de contexto que se
 * inyecta en el prompt. Nada aquí produce frases habladas: el LLM redacta.
 */
class NavigationSessionMemoryTest {

    private var now = 1_000_000L

    private fun memory() = NavigationSessionMemory(clock = { now })

    // ── Encargos ──────────────────────────────────────────────────────────

    @Test
    fun `addStop ignores blank labels`() {
        val memory = memory()
        assertNull(memory.addStop("   "))
        assertTrue(memory.pendingStops().isEmpty())
    }

    @Test
    fun `addStop ignores duplicates regardless of case`() {
        val memory = memory()
        assertNotNull(memory.addStop("comprar pan"))
        assertNull(memory.addStop(" COMPRAR PAN "))
        assertEquals(1, memory.pendingStops().size)
    }

    @Test
    fun `completeStops returns and clears pending stops`() {
        val memory = memory()
        memory.addStop("comprar pan")
        memory.addStop("pasar a la farmacia")

        val done = memory.completeStops()

        assertEquals(listOf("comprar pan", "pasar a la farmacia"), done.map { it.label })
        assertTrue(memory.pendingStops().isEmpty())
    }

    // ── Pausas ────────────────────────────────────────────────────────────

    @Test
    fun `pause without minutes is indefinite`() {
        val memory = memory()
        val pause = memory.pause("conversando con un vecino", null)

        assertTrue(memory.isPaused())
        assertNull(pause.untilMillis)
        assertNull(memory.remainingPauseMinutes())
    }

    @Test
    fun `pause with minutes reports remaining time`() {
        val memory = memory()
        memory.pause("descansando", 5)

        assertEquals(5, memory.remainingPauseMinutes())

        now += 2 * 60_000L
        assertEquals(3, memory.remainingPauseMinutes())
    }

    @Test
    fun `pause with deadline expires by itself`() {
        val memory = memory()
        memory.pause("terminar un trámite", 1)

        now += 61_000L

        assertFalse(memory.isPaused())
        assertNull(memory.pause)
        assertNull(memory.remainingPauseMinutes())
    }

    @Test
    fun `resume clears the pause and returns it`() {
        val memory = memory()
        memory.pause("compra rápida", null)

        val resumed = memory.resume()

        assertEquals("compra rápida", resumed?.reason)
        assertNull(memory.pause)
        assertFalse(memory.isPaused())
        assertNull(memory.resume())
    }

    // ── Correcciones ──────────────────────────────────────────────────────

    @Test
    fun `correction is recorded and cleared`() {
        val memory = memory()
        memory.recordCorrection("la calle correcta es Los Militares")

        assertEquals("la calle correcta es Los Militares", memory.lastCorrection)

        memory.clearCorrection()
        assertNull(memory.lastCorrection)
    }

    @Test
    fun `blank correction does not overwrite the previous one`() {
        val memory = memory()
        memory.recordCorrection("doblar por Apoquindo")

        memory.recordCorrection("  ")

        assertEquals("doblar por Apoquindo", memory.lastCorrection)
    }

    // ── Contexto para el prompt ───────────────────────────────────────────

    @Test
    fun `describeForPrompt is empty without context`() {
        assertEquals("", memory().describeForPrompt())
    }

    @Test
    fun `describeForPrompt exposes stops pause and correction`() {
        val memory = memory()
        memory.addStop("comprar pan")
        memory.pause("conversando", 10)
        memory.recordCorrection("la ruta correcta sigue por Manquehue")

        val context = memory.describeForPrompt()

        assertTrue(context.contains("comprar pan"))
        assertTrue(context.contains("conversando"))
        assertTrue(context.contains("quedan 10 min"))
        assertTrue(context.contains("Manquehue"))
        assertTrue(context.contains("manage_navigation_session"))
    }

    @Test
    fun `reset clears every piece of session state`() {
        val memory = memory()
        memory.addStop("comprar pan")
        memory.pause("pausa", 5)
        memory.recordCorrection("corrección")

        memory.reset()

        assertFalse(memory.hasContext())
        assertTrue(memory.pendingStops().isEmpty())
        assertNull(memory.pause)
        assertNull(memory.lastCorrection)
    }
}

/**
 * Avisos autónomos estructurados del [FifoNavigationManager] (ADR-0040).
 *
 * El motor no redacta: entrega un [NavigationNotice] y el
 * pipeline pide la locución al LLM. [NavigationNotice.fallbackSpeech] es el
 * único texto estático y solo se usa cuando el modelo no responde.
 */
class NavigationNoticeTest {

    private val currentLocation = CurrentLocationInfo(
        latitude = -33.4172,
        longitude = -70.6088,
        address = "Av. Vitacura 2909",
        city = "Vitacura",
        isGpsActive = true
    )

    private lateinit var navManager: FifoNavigationManager
    private val notices = mutableListOf<NavigationNotice>()

    private fun newManager(): FifoNavigationManager {
        val manager = FifoNavigationManager(
            locationAdapter = InMemoryLocationAdapter(fixedLocation = currentLocation),
            locationManagerProvider = { null }
        )
        // En JVM el Handler es un stub sin Looper: entregamos en el acto.
        manager.noticeDispatch = { block -> block() }
        manager.onAutonomousGuidance = { notices += it }
        return manager
    }

    private fun arrivalTick() = Location("test").apply {
        latitude = currentLocation.latitude
        longitude = currentLocation.longitude
        speed = 1.2f
    }

    @Test
    fun `arrival emits a structured notice with the planned stops`() {
        navManager = newManager()
        navManager.startNavigation(
            name = "OXXO Manquehue",
            latitude = currentLocation.latitude,
            longitude = currentLocation.longitude,
            fullAddress = "Manquehue Norte 1234, Vitacura",
            road = "Manquehue Norte"
        )
        navManager.sessionMemory.addStop("comprar pan")

        navManager.simulateLocationTick(arrivalTick())

        assertEquals(1, notices.size)
        val notice = notices.single()
        assertEquals(NavigationNoticeKind.ARRIVED, notice.kind)
        assertEquals("OXXO Manquehue", notice.destinationName)
        assertEquals("Manquehue Norte", notice.streetName)
        assertEquals(listOf("comprar pan"), notice.plannedStops)
        assertTrue(notice.fallbackSpeech().contains("OXXO Manquehue"))
    }

    @Test
    fun `starting a new route clears the previous session memory`() {
        navManager = newManager()
        navManager.startNavigation(
            name = "OXXO Manquehue",
            latitude = currentLocation.latitude,
            longitude = currentLocation.longitude,
            fullAddress = "Manquehue Norte 1234, Vitacura",
            road = "Manquehue Norte"
        )
        navManager.sessionMemory.addStop("comprar pan")
        navManager.sessionMemory.pause("conversando", 5)

        navManager.startNavigation(
            name = "Farmacia",
            latitude = currentLocation.latitude,
            longitude = currentLocation.longitude,
            fullAddress = "Vitacura 100",
            road = "Vitacura"
        )

        assertTrue(navManager.sessionMemory.pendingStops().isEmpty())
        assertFalse(navManager.sessionMemory.isPaused())
    }

    @Test
    fun `stopping navigation clears the session memory`() {
        navManager = newManager()
        navManager.startNavigation(
            name = "OXXO Manquehue",
            latitude = currentLocation.latitude,
            longitude = currentLocation.longitude,
            fullAddress = "Manquehue Norte 1234, Vitacura",
            road = "Manquehue Norte"
        )
        navManager.sessionMemory.recordCorrection("la ruta va por Manquehue")

        navManager.stopNavigation()

        assertFalse(navManager.sessionMemory.hasContext())
    }
}
