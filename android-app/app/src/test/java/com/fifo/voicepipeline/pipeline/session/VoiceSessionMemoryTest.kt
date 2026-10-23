package com.fifo.voicepipeline.pipeline.session

import com.fifo.voicepipeline.audio.VadProcessor
import com.fifo.voicepipeline.data.memory.InMemoryConversationJournal
import com.fifo.voicepipeline.data.memory.InMemorySessionMemoryConsolidator
import com.fifo.voicepipeline.data.memory.JournalStatus
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

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSessionMemoryTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var journal: InMemoryConversationJournal
    private lateinit var consolidator: InMemorySessionMemoryConsolidator
    private lateinit var memory: MemoryCoordinator
    private lateinit var cognition: InMemorySpeechCognitionAdapter
    private var now = 1_000L

    @Before
    fun setUp() {
        journal = InMemoryConversationJournal()
        consolidator = InMemorySessionMemoryConsolidator()
        memory = MemoryCoordinator(
            journal = journal,
            consolidator = consolidator,
            scope = testScope,
            userIdProvider = { "usr_test" },
            clock = { now },
            sessionId = "ses_test"
        )
        cognition = InMemorySpeechCognitionAdapter()
    }

    private fun newSession(
        outbound: AudioOutboundAdapter = InMemoryAudioOutboundAdapter()
    ): Pair<VoiceSession, SpeechOutboundEngine> {
        val engine = SpeechOutboundEngine(outbound)
        val orchestrator = TurnOrchestrator(
            scope = testScope,
            speechOutbound = engine,
            autoSleepTimeoutMs = 10_000L,
            followUpTimeoutMs = 5_000L
        )
        val session = VoiceSession(
            scope = testScope,
            outboundAdapter = outbound,
            speechSegmenter = SpeechSegmenter(
                vad = VadProcessor(clock = { 0L }),
                preRollCapacity = 5
            ),
            speechOutbound = engine,
            turnOrchestrator = orchestrator,
            speechCognition = cognition,
            memory = memory,
            nowProvider = { now }
        )
        return session to engine
    }

    @Test
    fun testTurnIsJournalledBeforeSpeechStarts() = runTest(testDispatcher) {
        var entriesWhenSpeechStarted = -1
        val outbound = RecordingOutboundAdapter {
            entriesWhenSpeechStarted = journal.all().size
        }
        val (session, _) = newSession(outbound)

        session.processUserUtterance("Fifo, ¿qué hora es?")
        testDispatcher.scheduler.runCurrent()

        assertEquals(1, entriesWhenSpeechStarted)
        val entry = journal.all().single()
        assertEquals("Fifo, ¿qué hora es?", entry.userText)
        assertEquals("Hola, estoy muy bien, gracias por preguntar.", entry.assistantText)
        assertEquals(JournalStatus.PENDING, entry.status)
        assertFalse(entry.needsRepair)
        assertEquals(1, outbound.spoken.size)
    }

    @Test
    fun testAgentErrorTurnIsMarkedForRepair() = runTest(testDispatcher) {
        cognition.responseToReturn = "Disculpe, no pude procesar la solicitud."
        val (session, _) = newSession()

        session.processUserUtterance("Fifo, ¿qué hora es?")
        testDispatcher.scheduler.runCurrent()

        assertTrue(journal.all().single().needsRepair)
    }

    @Test
    fun testCorrectionWithinWindowSpeaksRepairedTurn() = runTest(testDispatcher) {
        val previous = memory.recordTurn(
            userText = "Fifo, ¿a qué hora abre la tienda?",
            assistantText = "La tienda abre a las ocho."
        )!!
        now = 5_000L
        val outbound = RecordingOutboundAdapter()
        val (session, _) = newSession(outbound)

        cognition.repairResponseToReturn = "Tienes razón, abre a las nueve."
        session.processUserUtterance("Fifo, te equivocaste, abre a las nueve")
        testDispatcher.scheduler.runCurrent()

        assertEquals(1, cognition.repairRequests.size)
        assertEquals("te equivocaste, abre a las nueve", cognition.repairRequests.single().first)
        assertEquals(1, outbound.spoken.size)
        assertEquals("Tienes razón, abre a las nueve.", outbound.spoken.single())
        assertEquals("Tienes razón, abre a las nueve.", session.aiResponse.value)
        assertEquals(PipelineState.SPEAKING, session.state.value)

        val repaired = journal.all().first { it.id == previous.id }
        assertEquals("Tienes razón, abre a las nueve.", repaired.repairText)
        assertFalse(repaired.needsRepair)
        assertEquals(2, journal.all().size)
    }

    @Test
    fun testBareNoAfterErrorIsAcceptedWithoutWakeWord() = runTest(testDispatcher) {
        memory.recordTurn(
            userText = "Fifo, ¿quién llama?",
            assistantText = "Disculpe, no pude consultar la llamada.",
            needsRepair = true
        )
        now = 2_000L
        val outbound = RecordingOutboundAdapter()
        val (session, _) = newSession(outbound)

        cognition.repairResponseToReturn = "Tienes razón, es María."
        session.processUserUtterance("No, era María")
        testDispatcher.scheduler.runCurrent()

        assertEquals(1, cognition.repairRequests.size)
        assertEquals("Tienes razón, es María.", outbound.spoken.single())
    }

    @Test
    fun testLateCorrectionIsStoredButNotSpoken() = runTest(testDispatcher) {
        val previous = memory.recordTurn(
            userText = "Fifo, ¿a qué hora abre la tienda?",
            assistantText = "La tienda abre a las ocho."
        )!!
        now = 12_000L
        val outbound = RecordingOutboundAdapter()
        val (session, _) = newSession(outbound)

        cognition.repairResponseToReturn = "Tienes razón, abre a las nueve."
        session.processUserUtterance("Fifo, te equivocaste, abre a las nueve")
        testDispatcher.scheduler.runCurrent()

        assertTrue("Una corrección tardía no debe locutarse", outbound.spoken.isEmpty())
        assertEquals("Tienes razón, abre a las nueve.", session.aiResponse.value)
        assertEquals("Corrección guardada", session.statusMessage.value)
        assertEquals(
            "Tienes razón, abre a las nueve.",
            journal.all().first { it.id == previous.id }.repairText
        )
    }

    @Test
    fun testSleepRequestsPendingConsolidation() = runTest(testDispatcher) {
        val (session, _) = newSession()
        session.processUserUtterance("Fifo, ¿qué hora es?")
        testDispatcher.scheduler.runCurrent()

        session.dispatchIntent(VoiceSessionIntent.GoToSleep)
        testDispatcher.scheduler.runCurrent()

        assertEquals(1, consolidator.consolidatedBatches.size)
        assertEquals(JournalStatus.CONSOLIDATED, journal.all().single().status)
    }

    private class RecordingOutboundAdapter(
        private val onSpeak: (() -> Unit)? = null
    ) : AudioOutboundAdapter {
        val spoken = mutableListOf<String>()
        private var finishCallback: (() -> Unit)? = null

        override var isSpeaking: Boolean = false
            private set

        override fun speak(
            text: String,
            onRmsUpdate: ((Double) -> Unit)?,
            onFinish: (() -> Unit)?
        ) {
            spoken.add(text)
            isSpeaking = true
            finishCallback = onFinish
            onSpeak?.invoke()
            onRmsUpdate?.invoke(0.5)
        }

        override fun stop() {
            isSpeaking = false
            finishCallback = null
        }
    }
}
