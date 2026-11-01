package com.fifo.voicepipeline.ui.state

import com.fifo.voicepipeline.data.model.IncomingCallInfo
import com.fifo.voicepipeline.data.model.UserProfileData
import com.fifo.voicepipeline.data.modules.CommunityModule
import com.fifo.voicepipeline.data.modules.EpisodicMemoryModule
import com.fifo.voicepipeline.data.modules.InMemoryCommunityStorageAdapter
import com.fifo.voicepipeline.data.modules.InMemoryMemoryStorageAdapter
import com.fifo.voicepipeline.data.modules.InMemoryUserStorageAdapter
import com.fifo.voicepipeline.data.modules.NoopCommunityRemote
import com.fifo.voicepipeline.data.modules.NoopMemoryRemote
import com.fifo.voicepipeline.data.modules.RelationalGraphModule
import com.fifo.voicepipeline.data.modules.RelationalRemotePort
import com.fifo.voicepipeline.data.modules.UserIdentityModule
import com.fifo.voicepipeline.data.session.ContinuousListeningState
import com.fifo.voicepipeline.data.model.RelationalContact
import com.fifo.voicepipeline.telephony.CallStateCoordinator
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Los *state holders* son proyecciones de solo lectura (ADR-0049).
 *
 * La prueba fija tres cosas:
 * - la pantalla no posee estado: lee los `StateFlow` del módulo;
 * - los comandos llegan al dueño (módulo u orquestador) y no a una copia;
 * - la escucha continua se pide al puente, no a un booleano local.
 */
class FifoStateHoldersTest {

    private lateinit var identity: UserIdentityModule
    private lateinit var memory: EpisodicMemoryModule
    private lateinit var community: CommunityModule
    private lateinit var relationalGraph: RelationalGraphModule

    @Before
    fun setUp() {
        identity = UserIdentityModule(InMemoryUserStorageAdapter())
        identity.setUserProfile(UserProfileData(id = USER_ID, fullName = "Carmen"))
        memory = EpisodicMemoryModule(InMemoryMemoryStorageAdapter(), NoopMemoryRemote)
        memory.loadUserData(USER_ID)
        community = CommunityModule(InMemoryCommunityStorageAdapter(), NoopCommunityRemote)
        community.loadInitialData(USER_ID)
        relationalGraph = RelationalGraphModule(
            memory = memory,
            database = { null },
            scope = TestScope(UnconfinedTestDispatcher()),
            userId = { USER_ID },
            remote = object : RelationalRemotePort {
                override suspend fun postContact(userId: String, contact: RelationalContact) = Unit
                override suspend fun syncContacts(userId: String, contacts: List<RelationalContact>) = Unit
            }
        )
    }

    @Test
    fun `memory commands go to the module that owns the state`() {
        val holder = VoiceUiStateHolder(
            memory = memory,
            identity = identity,
            telephony = CallStateCoordinator(),
            listening = ContinuousListeningState()
        )

        holder.addMemory("🌻", "Orquídeas", "Cuida sus orquídeas.")

        assertEquals(1, holder.memories.value.size)
        assertTrue(memory.memories.value.single().title == "Orquídeas")

        holder.removeMemory(holder.memories.value.single().id)

        assertTrue(holder.memories.value.isEmpty())
    }

    @Test
    fun `continuous listening is requested through the owner bridge`() {
        val listening = ContinuousListeningState()
        val requests = mutableListOf<Boolean>()
        listening.handler = { requests.add(it) }
        val holder = VoiceUiStateHolder(
            memory = memory,
            identity = identity,
            telephony = CallStateCoordinator(),
            listening = listening
        )

        holder.stopContinuousListening()

        assertEquals(listOf(false), requests)
        assertFalse(holder.isContinuousListening.value)
    }

    @Test
    fun `answering and hanging up delegate to the telephony owner`() {
        val telephony = CallStateCoordinator()
        telephony.onAnswerCallAction = { true }
        telephony.onHangupCallAction = { true }
        telephony.setIncomingCall(IncomingCallInfo(callerName = "Tomás", isRinging = true))
        val holder = VoiceUiStateHolder(memory, identity, telephony, ContinuousListeningState())

        assertTrue(holder.answerCall())
        assertEquals(false, holder.incomingCall.value?.isRinging)

        assertTrue(holder.hangupCall())
        assertEquals(null, holder.incomingCall.value)
    }

    @Test
    fun `activities join and creation reach the community module`() {
        val holder = ActivitiesStateHolder(
            community = community,
            relationalGraph = relationalGraph,
            memory = memory,
            identity = identity
        )

        holder.createActivity(
            title = "Caminata",
            description = "Paseo",
            dateLabel = "Sábado",
            locationName = "Plaza"
        )
        val created = holder.activities.value.single()

        assertEquals(USER_ID, created.organizerId)
        assertEquals("Carmen", holder.suggestActivity("Caminata", emptyList()).organizerName)

        holder.toggleJoin(created.id)

        assertFalse(holder.activities.value.single().isJoined)
    }

    private companion object {
        const val USER_ID = "usr_carmen"
    }
}
