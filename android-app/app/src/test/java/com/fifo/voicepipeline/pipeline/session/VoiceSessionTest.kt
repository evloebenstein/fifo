package com.fifo.voicepipeline.pipeline.session

import com.fifo.voicepipeline.audio.VadProcessor
import com.fifo.voicepipeline.pipeline.PipelineState
import com.fifo.voicepipeline.telephony.CallPhase
import com.fifo.voicepipeline.telephony.CallSnapshot
import com.fifo.voicepipeline.telephony.InMemoryTelephonyPort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSessionTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var inMemoryOutbound: InMemoryAudioOutboundAdapter
    private lateinit var inMemoryCognition: InMemorySpeechCognitionAdapter
    private lateinit var outboundEngine: SpeechOutboundEngine
    private lateinit var turnOrchestrator: TurnOrchestrator
    private lateinit var voiceSession: VoiceSession

    @Before
    fun setUp() {
        inMemoryOutbound = InMemoryAudioOutboundAdapter()
        inMemoryCognition = InMemorySpeechCognitionAdapter()
        outboundEngine = SpeechOutboundEngine(inMemoryOutbound)
        turnOrchestrator = TurnOrchestrator(
            scope = testScope,
            speechOutbound = outboundEngine,
            autoSleepTimeoutMs = 10_000L,
            followUpTimeoutMs = 5_000L
        )
        voiceSession = VoiceSession(
            scope = testScope,
            outboundAdapter = inMemoryOutbound,
            speechSegmenter = SpeechSegmenter(
                vad = VadProcessor(clock = { 0L }),
                preRollCapacity = 5
            ),
            speechOutbound = outboundEngine,
            turnOrchestrator = turnOrchestrator,
            speechCognition = inMemoryCognition
        )
    }

    private fun highEnergyFrame(): ByteArray = ByteArray(640).also { frame ->
        for (i in 0 until frame.size step 2) {
            val highVal: Short = 16000
            frame[i] = (highVal.toInt() and 0xFF).toByte()
            frame[i + 1] = ((highVal.toInt() shr 8) and 0xFF).toByte()
        }
    }

    @Test
    fun testSpeechOutboundEngine_sanitizesMarkdownAndUrls() {
        val markdownSentence = "Hola **amigo**, visita https://fifo.app para más detalles *hoy*."
        outboundEngine.speak(markdownSentence)

        assertEquals(1, inMemoryOutbound.spokenUtterances.size)
        val spoken = inMemoryOutbound.spokenUtterances.first()

        assertFalse("No debe contener asteriscos de Markdown", spoken.contains("*"))
        assertFalse("No debe contener URLs crudas", spoken.contains("https://"))
        assertTrue("Debe contener el saludo limpio", spoken.contains("Hola amigo"))
    }

    @Test
    fun testTurnOrchestrator_wakeWordAndStateTransition() = runTest(testDispatcher) {
        assertEquals(PipelineState.SLEEPING, turnOrchestrator.state.value)
        assertFalse(turnOrchestrator.isAwake.value)

        turnOrchestrator.onWakeWordDetected("Fifo")
        assertEquals(PipelineState.LISTENING, turnOrchestrator.state.value)
        assertTrue(turnOrchestrator.isAwake.value)
    }

    @Test
    fun testTurnOrchestrator_silenceCommands() {
        assertTrue(turnOrchestrator.isSilenceCommand("Fifo cállate por favor"))
        assertTrue(turnOrchestrator.isSilenceCommand("Silencio"))
        assertTrue(turnOrchestrator.isSilenceCommand("Para un momento"))
        assertTrue(turnOrchestrator.isSilenceCommand("Basta Fifo"))
        assertFalse(turnOrchestrator.isSilenceCommand("¿Cómo estás hoy Fifo?"))
    }

    @Test
    fun testVoiceSession_highRmsDoesNotCutSpeechWithoutTranscriptContent() = runTest(testDispatcher) {
        voiceSession.speakResponse("Hola María, ¿cómo estuvo tu caminata matutina por el parque?")
        assertEquals(PipelineState.SPEAKING, voiceSession.state.value)
        assertTrue(outboundEngine.isSpeaking)

        // Energía alta durante la locución: solo mueve la boca/OLED. No corta TTS.
        voiceSession.onAudioFrameReceived(highEnergyFrame())
        voiceSession.onAudioFrameReceived(highEnergyFrame())

        assertTrue("El RMS por sí solo no debe cortar la locución", outboundEngine.isSpeaking)
        assertEquals(PipelineState.SPEAKING, voiceSession.state.value)
        assertFalse(turnOrchestrator.isWaitingForBargeInResolution)
    }

    @Test
    fun testVoiceSession_silenceCommandCutsSpeechByTranscriptContent() = runTest(testDispatcher) {
        voiceSession.speakResponse("Hola María, ¿cómo estuvo tu caminata matutina por el parque?")
        assertTrue(outboundEngine.isSpeaking)

        voiceSession.processUserUtterance("Fifo cállate")
        testDispatcher.scheduler.runCurrent()

        assertFalse("El contenido de la transcripción sí debe cortar TTS", outboundEngine.isSpeaking)
        assertEquals(PipelineState.SLEEPING, voiceSession.state.value)
    }

    @Test
    fun testVoiceSession_newFifoQueryWhileSpeakingCutsAndProcessesByTranscript() = runTest(testDispatcher) {
        voiceSession.speakResponse("Te estaba contando una historia muy larga.")
        assertTrue(outboundEngine.isSpeaking)

        inMemoryCognition.responseToReturn = "Son las cinco de la tarde."
        voiceSession.processUserUtterance("Fifo, ¿qué hora es?")
        testDispatcher.scheduler.runCurrent()

        assertEquals(2, inMemoryOutbound.spokenUtterances.size)
        assertEquals("Son las cinco de la tarde.", inMemoryOutbound.spokenUtterances.last())
        assertTrue("La respuesta nueva se está locutando", outboundEngine.isSpeaking)
        assertEquals(1, inMemoryCognition.receivedQueries.size)
        assertEquals("¿qué hora es?", inMemoryCognition.receivedQueries.first())
        assertEquals("Son las cinco de la tarde.", voiceSession.aiResponse.value)
    }

    @Test
    fun testVoiceSession_followUpWindowAfterQuestion() = runTest(testDispatcher) {
        voiceSession.speakResponse("¿Quieres que te recuerde tomar agua en una hora?")
        inMemoryOutbound.finishCurrentSpeech()
        testDispatcher.scheduler.runCurrent()

        assertEquals(PipelineState.LISTENING, voiceSession.state.value)
        assertTrue("Debe esperar respuesta de seguimiento", turnOrchestrator.expectFollowUp.value)
    }

    @Test
    fun testVoiceSession_wakeWordGating_discardsUnaddressedAmbientSpeech() = runTest(testDispatcher) {
        // En reposo, si alguien habla sin decir "Fifo" y no hay follow-up, se descarta
        voiceSession.processUserUtterance("Oye pásame la sal por favor")
        testDispatcher.scheduler.runCurrent()

        assertEquals("Audio ambiental descartado", voiceSession.statusMessage.value)
        assertEquals(0, inMemoryCognition.receivedQueries.size)
    }

    @Test
    fun testVoiceSession_followUpWithoutWakeWord_acceptsDirectAnswer() = runTest(testDispatcher) {
        // 1. Fifo hace una pregunta y termina de hablar -> Se activa la ventana de seguimiento
        voiceSession.speakResponse("¿A qué hora te gustaría salir a pasear?")
        inMemoryOutbound.finishCurrentSpeech()
        testDispatcher.scheduler.runCurrent()

        assertTrue(turnOrchestrator.expectFollowUp.value)

        // 2. El usuario responde directamente SIN decir "Fifo"
        inMemoryCognition.responseToReturn = "A las cinco de la tarde es una excelente hora."
        voiceSession.processUserUtterance("A las cinco de la tarde")
        testDispatcher.scheduler.runCurrent()

        assertEquals(1, inMemoryCognition.receivedQueries.size)
        assertEquals("A las cinco de la tarde", inMemoryCognition.receivedQueries.first())
        assertEquals(PipelineState.SPEAKING, voiceSession.state.value)
    }

    @Test
    fun testVoiceSession_silenceCommandProducesAbsoluteSilenceWithoutRoboticTemplates() = runTest(testDispatcher) {
        // Cuando el usuario ordena silencio, Fifo no emite ninguna locución hablada
        voiceSession.speakResponse("Estaba contándote una historia muy larga...")
        testDispatcher.scheduler.runCurrent()

        voiceSession.processUserUtterance("Fifo cállate")
        testDispatcher.scheduler.runCurrent()

        assertFalse(outboundEngine.isSpeaking)
        assertEquals(PipelineState.SLEEPING, voiceSession.state.value)
        assertEquals("Fifo en silencio", voiceSession.statusMessage.value)
    }

    @Test
    fun testVoiceSession_sleepCommandUsesDynamicLlmResponseThenSleeps() = runTest(testDispatcher) {
        // El usuario dice que se va a dormir. La respuesta NO está hardcodeada; viene del LLM.
        inMemoryCognition.responseToReturn = "Que descanses muy bien Pedro, que sueñes con los angelitos."

        voiceSession.processUserUtterance("Fifo me voy a dormir, que descanses")
        testDispatcher.scheduler.runCurrent()

        // 1. LLM procesó la consulta personalizada
        assertEquals(1, inMemoryCognition.receivedQueries.size)
        assertEquals("me voy a dormir, que descanses", inMemoryCognition.receivedQueries.first())

        // 2. Fifo habla la despedida empática del LLM
        assertEquals(PipelineState.SPEAKING, voiceSession.state.value)
        assertEquals("Que descanses muy bien Pedro, que sueñes con los angelitos.", voiceSession.aiResponse.value)

        // 3. Al terminar la locución de la despedida, se duerme automáticamente
        inMemoryOutbound.finishCurrentSpeech()
        testDispatcher.scheduler.runCurrent()

        assertEquals(PipelineState.SLEEPING, voiceSession.state.value)
        assertFalse(voiceSession.isAwake.value)
    }

    @Test
    fun testVoiceSession_bargeInSilenceTriggersDynamicLlmResumption() = runTest(testDispatcher) {
        // 1. Fifo habla y el usuario interrumpe diciendo solo "Fifo"
        voiceSession.speakResponse("Había una vez un pueblo muy hermoso en el sur de Chile...")
        testDispatcher.scheduler.runCurrent()

        turnOrchestrator.onBargeInTriggered()
        testDispatcher.scheduler.runCurrent()

        inMemoryCognition.resumptionToReturn = "Como te decía antes de la pausa, aquel pueblo era muy hermoso."

        // 2. El usuario solo dijo "Fifo" o quedó en silencio
        voiceSession.processUserUtterance("Fifo")
        testDispatcher.scheduler.runCurrent()

        // 3. El LLM genera reanudación dinámica contextual
        assertEquals(1, inMemoryCognition.resumptionRequests.size)
        assertTrue(inMemoryCognition.resumptionRequests.first().contains("Había una vez"))
        assertEquals("Como te decía antes de la pausa, aquel pueblo era muy hermoso.", voiceSession.aiResponse.value)
    }

    @Test
    fun testVoiceSession_telephonyIntentPausesAndResumes() = runTest(testDispatcher) {
        voiceSession.speakResponse("Dando indicaciones para caminar...")
        testDispatcher.scheduler.runCurrent()
        assertTrue(outboundEngine.isSpeaking)

        // Entra llamada telefónica
        voiceSession.dispatchIntent(VoiceSessionIntent.IncomingCall)
        testDispatcher.scheduler.runCurrent()

        assertFalse("El habla debe detenerse de inmediato durante una llamada", outboundEngine.isSpeaking)
        assertEquals(PipelineState.LISTENING, voiceSession.state.value)
        assertTrue("Fifo debe quedar despierto para oír 'Fifo contesta'", voiceSession.isAwake.value)
        assertEquals("Llamada telefónica en curso", voiceSession.statusMessage.value)

        // Finaliza la llamada telefónica
        voiceSession.dispatchIntent(VoiceSessionIntent.CallEnded)
        testDispatcher.scheduler.runCurrent()

        assertEquals(PipelineState.LISTENING, voiceSession.state.value)
        assertTrue(voiceSession.isAwake.value)
    }

    @Test
    fun testVoiceSession_simulatesSkillExecutionAndCustomResponse() = runTest(testDispatcher) {
        // Simular que el cerebro de Groq ejecuta una herramienta y devuelve la confirmación hablada
        inMemoryCognition.simulatedToolSpokenFeedback = "Linterna encendida para ayudarle a ver mejor, don Pedro."

        voiceSession.processUserUtterance("Fifo prende la linterna por favor")
        testDispatcher.scheduler.runCurrent()

        assertEquals(1, inMemoryCognition.receivedQueries.size)
        assertEquals("prende la linterna por favor", inMemoryCognition.receivedQueries.first())

        assertEquals(PipelineState.SPEAKING, voiceSession.state.value)
        assertEquals("Linterna encendida para ayudarle a ver mejor, don Pedro.", voiceSession.aiResponse.value)
    }

    @Test
    fun testCallActionRequiresFullWakeWordAndRoutesOutsideSession() = runTest(testDispatcher) {
        val telephony = InMemoryTelephonyPort(
            CallSnapshot(phase = CallPhase.RINGING, callerName = "María")
        )
        val outbound = InMemoryAudioOutboundAdapter()
        val engine = SpeechOutboundEngine(outbound)
        val orchestrator = TurnOrchestrator(scope = testScope, speechOutbound = engine)
        val cognition = InMemorySpeechCognitionAdapter()
        var handled: CallAction? = null

        val session = VoiceSession(
            scope = testScope,
            outboundAdapter = outbound,
            speechSegmenter = SpeechSegmenter(),
            speechOutbound = engine,
            turnOrchestrator = orchestrator,
            speechCognition = cognition,
            callState = telephony.callState,
            onCallAction = { action ->
                handled = action
                if (action == CallAction.ANSWER) telephony.answer()
                true
            }
        )

        // "contesta" aislado no es una orden válida: la palabra de activación es obligatoria.
        session.processUserUtterance("contesta")
        testDispatcher.scheduler.runCurrent()
        assertNull(handled)
        assertEquals(0, telephony.answerInvocations)
        assertEquals(0, cognition.receivedQueries.size)

        // "Fifo contesta" sí se enruta al control de llamada, fuera de VoiceSession.
        session.processUserUtterance("Fifo contesta")
        testDispatcher.scheduler.runCurrent()
        assertEquals(CallAction.ANSWER, handled)
        assertEquals(1, telephony.answerInvocations)
        assertEquals(0, cognition.receivedQueries.size)
        assertEquals("Contestando...", session.statusMessage.value)
    }

    @Test
    fun testCallActionEchoFromRingingAnnouncementDoesNotAnswer() = runTest(testDispatcher) {
        val telephony = InMemoryTelephonyPort(
            CallSnapshot(phase = CallPhase.RINGING, callerName = "María")
        )
        val outbound = InMemoryAudioOutboundAdapter()
        val engine = SpeechOutboundEngine(outbound)
        val orchestrator = TurnOrchestrator(scope = testScope, speechOutbound = engine)
        var handled: CallAction? = null

        val session = VoiceSession(
            scope = testScope,
            outboundAdapter = outbound,
            speechSegmenter = SpeechSegmenter(),
            speechOutbound = engine,
            turnOrchestrator = orchestrator,
            speechCognition = InMemorySpeechCognitionAdapter(),
            callState = telephony.callState,
            onCallAction = { action ->
                handled = action
                true
            }
        )

        session.speakResponse("Le está llamando María. Diga 'Fifo contesta' o 'Fifo cuelga'.")
        session.processUserUtterance("Fifo contesta")
        testDispatcher.scheduler.runCurrent()

        assertNull("El eco del anuncio no debe contestar la llamada", handled)
        assertEquals(0, telephony.answerInvocations)
    }

    @Test
    fun testConversationContextProvider_customContextInjection() {

        val customProvider = object : ConversationContextProvider {
            override fun buildContextWindow(): String {
                return "CONTEXTO FIJADO: Usuario Pedro, Ciudad Santiago, Destino activo Farmacia."
            }
        }

        val contextWindow = customProvider.buildContextWindow()
        assertTrue(contextWindow.contains("Pedro"))
        assertTrue(contextWindow.contains("Santiago"))
        assertTrue(contextWindow.contains("Farmacia"))
    }
}

