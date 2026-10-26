package com.fifo.voicepipeline.pipeline.session

import com.fifo.voicepipeline.audio.VadProcessor
import com.fifo.voicepipeline.pipeline.PipelineState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Cascada de invocación acústica + léxica (ADR-0043).
 *
 * Fija el contrato que hace segura la etapa acústica: solo genera candidatos,
 * despierta sin locutar, no corta una locución y no abre turno con un candidato
 * que ya caducó. El detector real (openWakeWord) se sustituye por este falso,
 * así que estas pruebas no dependen de ningún modelo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WakeWordCascadeTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var inMemoryOutbound: InMemoryAudioOutboundAdapter
    private lateinit var inMemoryCognition: InMemorySpeechCognitionAdapter
    private lateinit var outboundEngine: SpeechOutboundEngine
    private lateinit var turnOrchestrator: TurnOrchestrator
    private lateinit var detector: ScriptedWakeWordDetector
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
        detector = ScriptedWakeWordDetector()
        voiceSession = buildSession(nowProvider = System::currentTimeMillis)
    }

    private fun buildSession(nowProvider: () -> Long): VoiceSession = VoiceSession(
        scope = testScope,
        outboundAdapter = inMemoryOutbound,
        speechSegmenter = SpeechSegmenter(
            vad = VadProcessor(clock = { 0L }),
            preRollCapacity = 5
        ),
        speechOutbound = outboundEngine,
        turnOrchestrator = turnOrchestrator,
        speechCognition = inMemoryCognition,
        wakeWordDetector = detector,
        nowProvider = nowProvider
    )

    private fun frame(): ByteArray = ByteArray(640).also { audio ->
        for (i in 0 until audio.size step 2) {
            val high: Short = 16000
            audio[i] = (high.toInt() and 0xFF).toByte()
            audio[i + 1] = ((high.toInt() shr 8) and 0xFF).toByte()
        }
    }

    @Test
    fun testAcousticCandidateWakesWithoutSpeaking() = runTest(testDispatcher) {
        detector.detectionsRemaining = 1

        voiceSession.onAudioFrameReceived(frame())

        assertTrue("La acústica despierta a Fifo", turnOrchestrator.isAwake.value)
        assertEquals(PipelineState.LISTENING, voiceSession.state.value)
        assertEquals(
            "Despertar por acústica nunca locuta: la frase la compone el LLM",
            0,
            inMemoryOutbound.spokenUtterances.size
        )
    }

    @Test
    fun testAcousticCandidateRescuesQueryWithoutWakeWord() = runTest(testDispatcher) {
        inMemoryCognition.responseToReturn = "Son las cinco de la tarde."
        detector.detectionsRemaining = 1
        voiceSession.onAudioFrameReceived(frame())

        // El ruido se comió "Fifo" y el STT solo transcribió la consulta.
        voiceSession.processUserUtterance("¿qué hora es?")
        testDispatcher.scheduler.runCurrent()

        assertEquals(1, inMemoryCognition.receivedQueries.size)
        assertEquals("¿qué hora es?", inMemoryCognition.receivedQueries.first())
        assertEquals("Son las cinco de la tarde.", voiceSession.aiResponse.value)
    }

    @Test
    fun testExpiredAcousticCandidateDoesNotOpenTurn() = runTest(testDispatcher) {
        var clock = 1_000L
        val session = buildSession(nowProvider = { clock })
        detector.detectionsRemaining = 1
        session.onAudioFrameReceived(frame())

        clock += 10_000L
        session.processUserUtterance("¿qué hora es?")
        testDispatcher.scheduler.runCurrent()

        assertEquals("Audio ambiental descartado", session.statusMessage.value)
        assertEquals(0, inMemoryCognition.receivedQueries.size)
    }

    @Test
    fun testCandidateSurvivesALongUtterance() = runTest(testDispatcher) {
        var clock = 1_000L
        val session = buildSession(nowProvider = { clock })
        inMemoryCognition.responseToReturn = "Mañana hará sol."
        detector.detectionsRemaining = 1
        session.onAudioFrameReceived(frame())

        // Seis segundos después la ventana del candidato sigue viva: la pregunta
        // se dijo de un tirón y el segmento todavía no había cerrado.
        clock += 6_000L
        session.processUserUtterance("¿puedes decirme qué tiempo va a hacer mañana por la tarde?")
        testDispatcher.scheduler.runCurrent()

        assertEquals(1, inMemoryCognition.receivedQueries.size)
        assertEquals("Mañana hará sol.", session.aiResponse.value)
    }

    @Test
    fun testDetectorStaysSilentWhileFifoSpeaks() = runTest(testDispatcher) {
        voiceSession.speakResponse("Fifo contesta")
        detector.detectionsRemaining = 1

        voiceSession.onAudioFrameReceived(frame())
        voiceSession.onAudioFrameReceived(frame())

        assertEquals(
            "La propia voz no debe alimentar el detector: el eco contestaría la llamada",
            0,
            detector.framesAccepted
        )
        assertTrue(outboundEngine.isSpeaking)
        assertEquals(PipelineState.SPEAKING, voiceSession.state.value)
    }

    @Test
    fun testAcousticOnlyNeverBargesIn() = runTest(testDispatcher) {
        voiceSession.speakResponse("Te estaba contando una historia muy larga.")
        assertTrue(outboundEngine.isSpeaking)

        voiceSession.processUserUtterance("qué hora es")
        testDispatcher.scheduler.runCurrent()

        // Sin invocación léxica no hay corte por contenido, y la acústica no
        // puede sustituirla porque durante la locución ni siquiera se evalúa.
        assertTrue("El barge-in sigue exigiendo la invocación transcrita", outboundEngine.isSpeaking)
        assertFalse(turnOrchestrator.isWaitingForBargeInResolution)
    }

    /**
     * Detector de prueba: dispara solo cuando se le pide y cuenta las tramas que
     * la sesión le entrega, para poder afirmar que no se evalúa durante el TTS.
     */
    private class ScriptedWakeWordDetector : WakeWordDetector {
        override val modelId: String = "scripted-test"

        var framesAccepted: Int = 0
        var detectionsRemaining: Int = 0

        override fun accept(frame: ByteArray, nowMillis: Long): WakeWordDetection? {
            framesAccepted++
            if (detectionsRemaining <= 0) return null
            detectionsRemaining--
            return WakeWordDetection(score = 0.9, detectedAtMillis = nowMillis, modelId = modelId)
        }

        override fun reset() {
            detectionsRemaining = 0
        }
    }
}
