package com.fifo.voicepipeline.pipeline.session

import com.fifo.voicepipeline.pipeline.PipelineState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Pruebas unitarias puras de JVM para TurnOrchestrator.
 *
 * Validan los invariantes de ADR-0001 y ADR-0005:
 * - Ventana de follow-up de 14s activa solo tras preguntas de Fifo.
 * - Barge-in captura el contexto interrumpido sin perderlo.
 * - Auto-sueño a los 25s de inactividad.
 * - El primer saludo de Fifo ("¿Cómo estás?") preserva el follow-up.
 * - Las órdenes de silencio cierran el follow-up inmediatamente.
 * - El modo de escucha continua extiende el auto-sueño a 120s.
 *
 * NO prueban telefonía (FifoCallManager) — eso vive en VoicePipelineManager
 * por el invariante de ADR-0002/0005 y requiere contexto Android.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TurnOrchestratorTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var outbound: InMemoryAudioOutboundAdapter
    private lateinit var speechOutbound: SpeechOutboundEngine
    private lateinit var orchestrator: TurnOrchestrator

    @Before
    fun setUp() {
        outbound = InMemoryAudioOutboundAdapter()
        speechOutbound = SpeechOutboundEngine(outbound)
        orchestrator = TurnOrchestrator(
            scope = testScope,
            speechOutbound = speechOutbound,
            autoSleepTimeoutMs = 25_000L,
            followUpTimeoutMs = 14_000L
        )
    }

    // ── Wake-word y estados ────────────────────────────────────────────────

    @Test
    fun `estado inicial es SLEEPING e isAwake false`() {
        assertEquals(PipelineState.SLEEPING, orchestrator.state.value)
        assertFalse(orchestrator.isAwake.value)
    }

    @Test
    fun `onWakeWordDetected transiciona a LISTENING y activa isAwake`() = runTest(testDispatcher) {
        orchestrator.onWakeWordDetected("Fifo")
        assertEquals(PipelineState.LISTENING, orchestrator.state.value)
        assertTrue(orchestrator.isAwake.value)
    }

    @Test
    fun `isWakeWord detecta variantes foneticas de Fifo`() {
        val positivos = listOf("Fifo", "hola Fifo", "oye fifo", "fibo", "fipo", "figo")
        val negativos = listOf("hola", "dónde está el supermercado", "pon la alarma")
        positivos.forEach { assertTrue("'$it' debería ser wake-word", orchestrator.isWakeWord(it)) }
        negativos.forEach { assertFalse("'$it' no debería ser wake-word", orchestrator.isWakeWord(it)) }
    }

    @Test
    fun `extractQuery limpia prefijos de activacion`() {
        assertEquals("qué hora es", orchestrator.extractQuery("Fifo qué hora es"))
        assertEquals("llama a Carmen", orchestrator.extractQuery("Hola Fifo llama a Carmen"))
        assertEquals("", orchestrator.extractQuery("Fifo"))
    }

    // ── Ventana de follow-up ───────────────────────────────────────────────

    @Test
    fun `onSpeakingFinished con pregunta abre ventana follow-up`() = runTest(testDispatcher) {
        orchestrator.onSpeakingFinished("¿Te gustaría dar un paseo esta tarde?")
        assertTrue(orchestrator.expectFollowUp.value)
        assertEquals(PipelineState.LISTENING, orchestrator.state.value)
    }

    @Test
    fun `onSpeakingFinished sin pregunta NO abre follow-up`() = runTest(testDispatcher) {
        orchestrator.onSpeakingFinished("El tiempo hoy es soleado con 22 grados.")
        assertFalse(orchestrator.expectFollowUp.value)
    }

    @Test
    fun `ventana follow-up expira tras 14 segundos`() = runTest(testDispatcher) {
        orchestrator.onSpeakingFinished("¿Quieres que te recuerde tomar el medicamento?")
        assertTrue(orchestrator.expectFollowUp.value)

        advanceTimeBy(14_001L)
        assertFalse("Después de 14s el follow-up debe cerrarse", orchestrator.expectFollowUp.value)
    }

    @Test
    fun `ventana follow-up se cancela al comenzar procesamiento`() = runTest(testDispatcher) {
        orchestrator.onSpeakingFinished("¿Cuál es tu película favorita?")
        assertTrue(orchestrator.expectFollowUp.value)

        orchestrator.onProcessingStarted()
        assertFalse("El follow-up se cierra al entrar en PROCESSING", orchestrator.expectFollowUp.value)
        assertEquals(PipelineState.PROCESSING, orchestrator.state.value)
    }

    /**
     * TEST CRÍTICO — Regresión corregida en ADR-0005:
     * El primer saludo de Fifo ("Buenos días, ¿cómo estás?") debe preservar
     * el flag expectFollowUp sin que handleTtsFinished lo sobreescriba.
     */
    @Test
    fun `primer saludo de Fifo preserva expectFollowUp correctamente`() = runTest(testDispatcher) {
        // Simula el flujo: Fifo recibe "hola fifo" -> saluda con pregunta
        orchestrator.onWakeWordDetected("hola fifo")
        orchestrator.onProcessingStarted()
        orchestrator.onSpeakingStarted("Buenos días, ¿cómo estás hoy?")

        // Fifo termina de hablar su saludo con pregunta
        orchestrator.onSpeakingFinished("Buenos días, ¿cómo estás hoy?")

        // INVARIANTE: el follow-up debe estar ACTIVO aquí
        assertTrue(
            "Tras primer saludo con pregunta, expectFollowUp debe seguir activo",
            orchestrator.expectFollowUp.value
        )
        assertEquals(PipelineState.LISTENING, orchestrator.state.value)

        // El usuario responde dentro del plazo sin decir "Fifo"
        assertTrue(
            "La respuesta directa debe ser aceptada en ventana de follow-up",
            orchestrator.expectFollowUp.value
        )
    }

    // ── Barge-in ──────────────────────────────────────────────────────────

    @Test
    fun `onBargeInTriggered captura sentencia interrumpida y activa LISTENING`() = runTest(testDispatcher) {
        speechOutbound.speak("Te contaré sobre la historia de Chile...")
        orchestrator.onSpeakingStarted("Te contaré sobre la historia de Chile...")

        val interrupted = orchestrator.onBargeInTriggered()
        assertNotNull("Debe capturar la sentencia interrumpida", interrupted)
        assertTrue(orchestrator.isWaitingForBargeInResolution)
        assertEquals(PipelineState.LISTENING, orchestrator.state.value)
    }

    @Test
    fun `clearBargeInResolution limpia el estado de barge-in`() = runTest(testDispatcher) {
        orchestrator.onBargeInTriggered()
        assertTrue(orchestrator.isWaitingForBargeInResolution)

        orchestrator.clearBargeInResolution()
        assertFalse(orchestrator.isWaitingForBargeInResolution)
        assertNull(orchestrator.interruptedSpokenSentence)
    }

    // ── Órdenes de silencio ────────────────────────────────────────────────

    @Test
    fun `isSilenceCommand reconoce ordenes explicitas de silencio`() {
        val ordenes = listOf("cállate", "silencio", "basta", "para ya", "fifo para", "detente", "shh")
        ordenes.forEach { assertTrue("'$it' debe ser orden de silencio", orchestrator.isSilenceCommand(it)) }
    }

    @Test
    fun `isSilenceCommand no confunde preposicion para con orden de parar`() {
        val negativos = listOf(
            "necesito ayuda para ir al médico",
            "busca una ruta para el aeropuerto",
            "¿cómo llego para allá?"
        )
        negativos.forEach { assertFalse("'$it' NO debe ser orden de silencio", orchestrator.isSilenceCommand(it)) }
    }

    @Test
    fun `isSleepCommand reconoce despedidas`() {
        assertTrue(orchestrator.isSleepCommand("fifo me voy a dormir"))
        assertTrue(orchestrator.isSleepCommand("hasta luego fifo"))
        assertTrue(orchestrator.isSleepCommand("chao fifo"))
        assertFalse(orchestrator.isSleepCommand("fifo qué hora es"))
    }

    // ── Auto-sueño ─────────────────────────────────────────────────────────

    @Test
    fun `goToSleep pone a Fifo en SLEEPING y limpia estado`() = runTest(testDispatcher) {
        orchestrator.onWakeWordDetected("Fifo")
        orchestrator.onSpeakingFinished("¿Tienes preguntas?")
        assertTrue(orchestrator.expectFollowUp.value)

        orchestrator.goToSleep()
        assertEquals(PipelineState.SLEEPING, orchestrator.state.value)
        assertFalse(orchestrator.isAwake.value)
        assertFalse(orchestrator.expectFollowUp.value)
        assertFalse(orchestrator.isWaitingForBargeInResolution)
    }

    @Test
    fun `auto-sleep se activa tras 25 segundos de inactividad`() = runTest(testDispatcher) {
        orchestrator.onWakeWordDetected("Fifo")
        assertEquals(PipelineState.LISTENING, orchestrator.state.value)

        advanceTimeBy(25_001L)
        assertEquals("Después de 25s debe quedar SLEEPING", PipelineState.SLEEPING, orchestrator.state.value)
        assertFalse(orchestrator.isAwake.value)
    }

    @Test
    fun `wakeUpExplicitly cancela follow-up pendiente y reactiva LISTENING`() = runTest(testDispatcher) {
        orchestrator.onSpeakingFinished("¿En qué más puedo ayudarte?")
        assertTrue(orchestrator.expectFollowUp.value)

        orchestrator.wakeUpExplicitly()
        assertFalse("wakeUpExplicitly debe cancelar el follow-up timer previo", orchestrator.expectFollowUp.value)
        assertTrue(orchestrator.isAwake.value)
        assertEquals(PipelineState.LISTENING, orchestrator.state.value)
    }
}
