package com.fifo.voicepipeline.pipeline.session

import com.fifo.voicepipeline.audio.VadProcessor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Integración de la identidad de locutor con el turno (ADR-0048).
 *
 * Fija las garantías de la costura: la identidad viaja con el audio, el valor
 * por defecto es `UNAVAILABLE`, un proveedor lento no retrasa el turno y un
 * proveedor que falla no lo rompe. No depende de ningún modelo de voz.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeakerIdentityIntegrationTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var inMemoryOutbound: InMemoryAudioOutboundAdapter
    private lateinit var inMemoryCognition: InMemorySpeechCognitionAdapter
    private lateinit var outboundEngine: SpeechOutboundEngine
    private lateinit var turnOrchestrator: TurnOrchestrator

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
    }

    private fun buildSession(provider: SpeakerIdentityProvider): VoiceSession = VoiceSession(
        scope = testScope,
        outboundAdapter = inMemoryOutbound,
        speechSegmenter = SpeechSegmenter(
            vad = VadProcessor(clock = { 0L }),
            preRollCapacity = 5
        ),
        speechOutbound = outboundEngine,
        turnOrchestrator = turnOrchestrator,
        speechCognition = inMemoryCognition,
        speakerIdentityProvider = provider
    )

    @Test
    fun `la identidad del audio viaja al turno y al LLM`() = runTest(testDispatcher) {
        val identity = SpeakerIdentity(
            relation = SpeakerRelation.KNOWN,
            profileId = "friend",
            displayName = "María",
            confidence = 0.91,
            modelId = "scripted"
        )
        val provider = ScriptedSpeakerIdentityProvider(identity)
        val session = buildSession(provider)
        inMemoryCognition.transcriptionToReturn = "Fifo, ¿qué hora es?"

        session.processUserAudio(ByteArray(64_000))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(identity, session.speakerIdentity.value)
        assertEquals(identity, inMemoryCognition.receivedSpeakers.single())
        assertEquals("¿qué hora es?", inMemoryCognition.receivedQueries.single())
        assertEquals(1, provider.calls)
    }

    @Test
    fun `sin modelo el turno sigue siendo no disponible y se procesa`() = runTest(testDispatcher) {
        val session = buildSession(NoopSpeakerIdentityProvider)
        inMemoryCognition.transcriptionToReturn = "Fifo, ¿cómo estás?"

        session.processUserAudio(ByteArray(64_000))
        testDispatcher.scheduler.advanceUntilIdle()

        val speaker = inMemoryCognition.receivedSpeakers.single()
        assertEquals(SpeakerRelation.UNAVAILABLE, speaker?.relation)
        assertEquals(NOOP_SPEAKER_MODEL_ID, speaker?.modelId)
        assertEquals(1, inMemoryCognition.receivedQueries.size)
    }

    @Test
    fun `un proveedor lento agota el timeout y no retrasa el turno`() = runTest(testDispatcher) {
        val provider = ScriptedSpeakerIdentityProvider(
            identity = SpeakerIdentity(
                relation = SpeakerRelation.OWNER,
                profileId = "owner",
                displayName = "Ana",
                confidence = 0.99,
                modelId = "scripted"
            ),
            delayMillis = 10_000L
        )
        val session = buildSession(provider)
        inMemoryCognition.transcriptionToReturn = "Fifo, ¿qué hora es?"

        session.processUserAudio(ByteArray(64_000))
        testDispatcher.scheduler.advanceTimeBy(500)
        testDispatcher.scheduler.runCurrent()

        assertEquals(SpeakerRelation.UNAVAILABLE, session.speakerIdentity.value.relation)
        assertEquals(1, inMemoryCognition.receivedQueries.size)
        assertFalse("El timeout debe cancelar el modelo lento", provider.completed)
    }

    @Test
    fun `un proveedor que falla no rompe el turno`() = runTest(testDispatcher) {
        val provider = ScriptedSpeakerIdentityProvider(
            identity = SpeakerIdentity.unavailable("scripted"),
            failure = IllegalStateException("modelo corrupto")
        )
        val session = buildSession(provider)
        inMemoryCognition.transcriptionToReturn = "Fifo, ¿qué hora es?"

        session.processUserAudio(ByteArray(64_000))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(SpeakerRelation.UNAVAILABLE, session.speakerIdentity.value.relation)
        assertEquals("scripted", session.speakerIdentity.value.modelId)
        assertEquals(1, inMemoryCognition.receivedQueries.size)
    }

    @Test
    fun `una consulta escrita no inventa identidad`() = runTest(testDispatcher) {
        val provider = ScriptedSpeakerIdentityProvider(
            identity = SpeakerIdentity(
                relation = SpeakerRelation.OWNER,
                profileId = "owner",
                displayName = "Ana",
                confidence = 0.99,
                modelId = "scripted"
            )
        )
        val session = buildSession(provider)

        session.processUserUtterance("Fifo, ¿cómo estás?")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, provider.calls)
        assertEquals(SpeakerRelation.UNAVAILABLE, session.speakerIdentity.value.relation)
        assertEquals(SpeakerRelation.UNAVAILABLE, inMemoryCognition.receivedSpeakers.single()?.relation)
    }

    private class ScriptedSpeakerIdentityProvider(
        private val identity: SpeakerIdentity,
        private val delayMillis: Long = 0L,
        private val failure: Exception? = null
    ) : SpeakerIdentityProvider {

        override val modelId: String = "scripted"

        var calls: Int = 0
            private set

        var completed: Boolean = false
            private set

        override suspend fun identify(wavData: ByteArray, nowMillis: Long): SpeakerIdentity {
            calls++
            if (delayMillis > 0) delay(delayMillis)
            completed = true
            failure?.let { throw it }
            return identity
        }
    }
}
