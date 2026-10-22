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

    // ── Reglas endurecidas portadas del VoicePipelineManager ───────────────

    @Test
    fun `isWakeWord es puramente lexico y no se abre solo por follow-up`() = runTest(testDispatcher) {
        orchestrator.onSpeakingFinished("¿Quieres que te recuerde tomar agua?")
        assertTrue(orchestrator.expectFollowUp.value)

        // "contesta" aislado NO es una invocación, aunque haya follow-up abierto.
        assertFalse(orchestrator.isWakeWord("contesta"))
        assertFalse(orchestrator.isWakeWord("cuelga"))
        assertTrue(orchestrator.isWakeWord("Fifo contesta"))
    }

    @Test
    fun `isWakeWord descarta aliases riesgosos y acepta variantes foneticas`() {
        val riesgosos = listOf("tito", "dijo", "kiko", "vivo", "pico", "hijo", "tipo")
        riesgosos.forEach {
            assertFalse("'$it' no debe despertar a Fifo", orchestrator.isWakeWord(it))
        }

        val variantes = listOf("fi fo", "fifooo", "fijo", "pifo", "feefo", "fifi", "phifo")
        variantes.forEach {
            assertTrue("'$it' debería despertar a Fifo", orchestrator.isWakeWord(it))
        }
    }

    @Test
    fun `extractQuery elimina la invocacion en cualquier posicion`() {
        assertEquals("dime la hora", orchestrator.extractQuery("dime la hora, Fifo"))
        assertEquals("¿me escuchas?", orchestrator.extractQuery("Fifo, ¿me escuchas?"))
        assertEquals("", orchestrator.extractQuery("Hola Fifo"))
        assertEquals("", orchestrator.extractQuery("Fifo"))
    }

    @Test
    fun `isGreetingOnly distingue saludo puro de consulta`() {
        assertTrue(orchestrator.isGreetingOnly("Fifo"))
        assertTrue(orchestrator.isGreetingOnly("Hola Fifo"))
        assertTrue(orchestrator.isGreetingOnly("Fifo buenas noches"))
        assertFalse(orchestrator.isGreetingOnly("Fifo qué hora es"))
        assertFalse(orchestrator.isGreetingOnly("Fifo, ¿cómo estás?"))
    }

    @Test
    fun `isSilenceCommand acepta palabras cortas solo mientras Fifo habla`() {
        assertFalse(orchestrator.isSilenceCommand("puedes parar"))
        assertTrue(orchestrator.isSilenceCommand("puedes parar", whileSpeaking = true))
        assertTrue(orchestrator.isSilenceCommand("para un momento"))
        assertTrue(orchestrator.isSilenceCommand("un momento"))
        assertTrue(orchestrator.isSilenceCommand("espera", whileSpeaking = true))
    }

    @Test
    fun `isSilenceCommand no confunde palabras de silencio dentro de una instruccion`() {
        val negativos = listOf(
            "Fifo, ¿dónde está el botón de silencio?",
            "busca el modo silencio en los ajustes",
            "quiero que me guíes para llegar"
        )
        negativos.forEach {
            assertFalse("'$it' NO debe detener a Fifo", orchestrator.isSilenceCommand(it))
        }
    }

    @Test
    fun `isSleepCommand reconoce reglas ampliadas y evita buenas noches aislado`() {
        assertTrue(orchestrator.isSleepCommand("hasta mañana"))
        assertTrue(orchestrator.isSleepCommand("a descansar"))
        assertTrue(orchestrator.isSleepCommand("deja de escuchar"))
        assertTrue(orchestrator.isSleepCommand("eso es todo"))
        assertTrue(orchestrator.isSleepCommand("buenas noches, descansa"))
        assertFalse(orchestrator.isSleepCommand("buenas noches"))
    }

    @Test
    fun `isAskingQuestion detecta preguntas sin signo de interrogacion`() {
        assertTrue(orchestrator.isAskingQuestion("¿Te gustaría salir?"))
        assertTrue(orchestrator.isAskingQuestion("Cuéntame"))
        assertTrue(orchestrator.isAskingQuestion("Dime qué te gustaría desayunar"))
        assertTrue(orchestrator.isAskingQuestion("Dime si te parece"))
        assertFalse(orchestrator.isAskingQuestion("El tiempo hoy es soleado con 22 grados."))
        assertFalse(orchestrator.isAskingQuestion(""))
    }

    @Test
    fun `onSpeakingFinished abre follow-up por invitacion aunque no haya signo`() = runTest(testDispatcher) {
        orchestrator.onSpeakingFinished("Dime qué te gustaría desayunar")
        assertTrue(orchestrator.expectFollowUp.value)
        assertEquals(PipelineState.LISTENING, orchestrator.state.value)
    }

    @Test
    fun `onSpeakingFinished preserva follow-up ya abierto antes de hablar`() = runTest(testDispatcher) {
        orchestrator.startFollowUpWindow()
        assertTrue(orchestrator.expectFollowUp.value)

        orchestrator.onSpeakingStarted("Listo, ya está.")
        orchestrator.onSpeakingFinished("Listo, ya está.")

        assertTrue("El follow-up previo no debe perderse", orchestrator.expectFollowUp.value)
        assertEquals(PipelineState.LISTENING, orchestrator.state.value)
    }

    @Test
    fun `ventana follow-up expirada manda a Fifo a dormir`() = runTest(testDispatcher) {
        orchestrator.onSpeakingFinished("¿Quieres que te recuerde algo?")
        assertTrue(orchestrator.expectFollowUp.value)

        advanceTimeBy(14_001L)
        assertFalse(orchestrator.expectFollowUp.value)
        assertEquals(PipelineState.SLEEPING, orchestrator.state.value)
        assertFalse(orchestrator.isAwake.value)
    }

    @Test
    fun `isEchoOfLastSpokenSentence detecta eco durante locucion`() = runTest(testDispatcher) {
        speechOutbound.speak("Te contaré sobre la historia de Chile")
        orchestrator.onSpeakingStarted("Te contaré sobre la historia de Chile")

        assertTrue(
            orchestrator.isEchoOfLastSpokenSentence(
                "te contaré sobre la historia",
                isSpeakingNow = true
            )
        )
        assertFalse(
            orchestrator.isEchoOfLastSpokenSentence("qué hora es", isSpeakingNow = true)
        )
    }

    @Test
    fun `isEchoOfLastSpokenSentence nunca descarta respuestas en follow-up`() = runTest(testDispatcher) {
        speechOutbound.speak("¿Quieres tomar agua?")
        orchestrator.startFollowUpWindow()

        assertFalse(
            "Una respuesta corta que coincide con la pregunta no es eco",
            orchestrator.isEchoOfLastSpokenSentence("agua", isSpeakingNow = false)
        )
    }

    @Test
    fun `isEchoOfLastSpokenSentence fuera de locucion solo descarta repeticion completa`() = runTest(testDispatcher) {
        val frase = "El tiempo hoy es soleado con veintidós grados"
        speechOutbound.speak(frase)
        orchestrator.onSpeakingFinished(frase)

        assertTrue(orchestrator.isEchoOfLastSpokenSentence(frase, isSpeakingNow = false))
        assertFalse(orchestrator.isEchoOfLastSpokenSentence("grados", isSpeakingNow = false))
    }

    @Test
    fun `debounce descarta la misma transcripcion dentro de 2 punto 5 segundos`() {        assertTrue(orchestrator.shouldProcessUtterance("Hola Fifo", now = 1_000L))
        orchestrator.markUtteranceProcessed("Hola Fifo", now = 1_000L)

        assertFalse(orchestrator.shouldProcessUtterance("hola fifo ", now = 2_000L))
        assertTrue(orchestrator.shouldProcessUtterance("Hola Fifo", now = 3_600L))
        assertTrue(orchestrator.shouldProcessUtterance("otra consulta", now = 2_000L))
    }

    @Test
    fun `shouldAllowShortResponses sigue follow-up y barge-in`() = runTest(testDispatcher) {
        assertFalse(orchestrator.shouldAllowShortResponses())

        orchestrator.startFollowUpWindow()
        assertTrue(orchestrator.shouldAllowShortResponses())

        orchestrator.onProcessingStarted()
        assertFalse(orchestrator.shouldAllowShortResponses())

        orchestrator.onBargeInTriggered()
        assertTrue(orchestrator.shouldAllowShortResponses())

        orchestrator.clearBargeInResolution()
        assertFalse(orchestrator.shouldAllowShortResponses())
    }

    @Test
    fun `auto-sleep no duerme mientras Fifo habla o procesa`() = runTest(testDispatcher) {
        orchestrator.onWakeWordDetected("Fifo")
        orchestrator.onSpeakingStarted("Una respuesta larga")
        advanceTimeBy(30_001L)

        assertEquals(PipelineState.SPEAKING, orchestrator.state.value)
        assertTrue(orchestrator.isAwake.value)

        orchestrator.onProcessingStarted()
        advanceTimeBy(30_001L)
        assertEquals(PipelineState.PROCESSING, orchestrator.state.value)
    }

    @Test
    fun `modo continuo extiende auto-sleep a 120 segundos y luego se apaga`() = runTest(testDispatcher) {
        orchestrator.onWakeWordDetected("Fifo")
        orchestrator.setContinuousListening(true)

        advanceTimeBy(25_001L)
        assertEquals(
            "A los 25s el modo continuo debe seguir despierto",
            PipelineState.LISTENING,
            orchestrator.state.value
        )

        advanceTimeBy(95_000L)
        assertEquals(PipelineState.SLEEPING, orchestrator.state.value)
        assertFalse(orchestrator.continuousListeningEnabled)
    }

    @Test
    fun `barge-in sin respuesta solicita reanudacion natural a los 5 segundos`() = runTest(testDispatcher) {
        var reanudacion: String? = null
        val conCallback = TurnOrchestrator(
            scope = testScope,
            speechOutbound = speechOutbound,
            onBargeInResumeRequested = { reanudacion = it }
        )
        speechOutbound.speak("Había una vez un pueblo en el sur de Chile")
        conCallback.onSpeakingStarted("Había una vez un pueblo en el sur de Chile")

        conCallback.onBargeInTriggered()
        assertTrue(conCallback.isWaitingForBargeInResolution)

        advanceTimeBy(5_001L)
        assertEquals("Había una vez un pueblo en el sur de Chile", reanudacion)
        assertFalse(conCallback.isWaitingForBargeInResolution)
        assertNull(conCallback.interruptedSpokenSentence)
    }

    @Test
    fun `barge-in cancelado no solicita reanudacion`() = runTest(testDispatcher) {
        var reanudacion: String? = null
        val conCallback = TurnOrchestrator(
            scope = testScope,
            speechOutbound = speechOutbound,
            onBargeInResumeRequested = { reanudacion = it }
        )
        speechOutbound.speak("Una historia larga")
        conCallback.onSpeakingStarted("Una historia larga")
        conCallback.onBargeInTriggered()

        conCallback.clearBargeInResolution()
        advanceTimeBy(5_001L)

        assertNull(reanudacion)
    }

    @Test
    fun `control de llamada exige decir Fifo`() {
        // Contestar
        assertTrue(orchestrator.isCallAnswerCommand("Fifo contesta"))
        assertTrue(orchestrator.isCallAnswerCommand("Fifo, contesta el teléfono"))
        assertFalse(orchestrator.isCallAnswerCommand("contesta"))
        assertFalse(orchestrator.isCallAnswerCommand("hola contesta"))

        // Colgar
        assertTrue(orchestrator.isCallHangupCommand("Fifo cuelga"))
        assertTrue(orchestrator.isCallHangupCommand("Fifo cuelga la llamada"))
        assertFalse(orchestrator.isCallHangupCommand("cuelga"))
        assertFalse(orchestrator.isCallHangupCommand("Fifo contesta"))

        // "Fifo no contestes" rechaza, no contesta
        assertEquals(
            CallAction.HANGUP,
            orchestrator.classifyCallAction("Fifo no contestes", isRinging = true)
        )
    }

    @Test
    fun `classifyCallAction distingue timbrado llamada activa y estado del llamante`() {
        assertEquals(
            CallAction.ANSWER,
            orchestrator.classifyCallAction("Fifo contesta", isRinging = true)
        )
        assertEquals(
            CallAction.HANGUP,
            orchestrator.classifyCallAction("Fifo cuelga", isRinging = true)
        )
        assertEquals(
            CallAction.CALLER_STATUS,
            orchestrator.classifyCallAction("Fifo, ¿quién llama?", isRinging = true)
        )
        assertNull(orchestrator.classifyCallAction("Fifo contesta", isRinging = false))
        assertNull(orchestrator.classifyCallAction("¿quién llama?", isRinging = true))
    }
}
