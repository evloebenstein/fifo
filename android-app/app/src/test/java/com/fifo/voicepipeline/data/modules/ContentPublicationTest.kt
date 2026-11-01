package com.fifo.voicepipeline.data.modules

import com.fifo.voicepipeline.data.model.CommunityActivityItem
import com.fifo.voicepipeline.data.model.FifoMemoryItem
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Publicación desde los módulos (ADR-0049).
 *
 * Fija la regla de propiedad: el módulo guarda el estado local **y** publica por
 * su costura. La UI y el accessor ya no conocen `FifoBackendClient` para estas
 * acciones; si la red falla, la escritura local no se pierde.
 */
class ContentPublicationTest {

    private lateinit var memoryRemote: RecordingMemoryRemote
    private lateinit var communityRemote: RecordingCommunityRemote
    private lateinit var memory: EpisodicMemoryModule
    private lateinit var community: CommunityModule

    @Before
    fun setUp() {
        memoryRemote = RecordingMemoryRemote()
        communityRemote = RecordingCommunityRemote()
        val scope = TestScope(UnconfinedTestDispatcher())
        memory = EpisodicMemoryModule(
            storageAdapter = InMemoryMemoryStorageAdapter(),
            remote = memoryRemote,
            scope = scope
        )
        community = CommunityModule(
            storageAdapter = InMemoryCommunityStorageAdapter(),
            remote = communityRemote,
            scope = scope
        )
        memory.loadUserData(USER_ID)
        community.loadInitialData(USER_ID)
    }

    @Test
    fun `a new memory is stored locally and published once`() {
        val item = memory.addMemory(USER_ID, "🌻", "Orquídeas", "Carmen cuida sus orquídeas.")

        assertEquals(1, memory.memories.value.size)
        assertEquals(item.id, memory.memories.value.single().id)
        assertEquals(listOf(item), memoryRemote.posted)
    }

    @Test
    fun `removing a memory does not publish anything`() {
        val item = memory.addMemory(USER_ID, "🌻", "Orquídeas", "Detalle")
        memoryRemote.posted.clear()

        memory.removeMemory(USER_ID, item.id)

        assertTrue(memory.memories.value.isEmpty())
        assertTrue(memoryRemote.posted.isEmpty())
    }

    @Test
    fun `toggling a join publishes the new state`() {
        community.createCommunityActivity(activity("act_1"), userId = "")
        communityRemote.created.clear()

        community.toggleJoinActivity("act_1", USER_ID)

        assertTrue(community.communityActivities.value.single().isJoined)
        assertEquals(listOf(Triple("act_1", USER_ID, true)), communityRemote.joins)

        community.toggleJoinActivity("act_1", USER_ID)

        assertFalse(community.communityActivities.value.single().isJoined)
        assertEquals(true, communityRemote.joins.first().third)
        assertEquals(false, communityRemote.joins.last().third)
    }

    @Test
    fun `creating an activity joins the creator and publishes it`() {
        val id = community.createActivity(
            title = "Caminata",
            description = "Paseo por el parque",
            dateLabel = "Sábado 10:00",
            locationName = "Plaza",
            organizerId = USER_ID,
            spotsMax = 8
        )

        val created = community.communityActivities.value.single { it.id == id }
        assertTrue(created.isJoined)
        assertEquals(USER_ID, created.organizerId)
        assertEquals(1, communityRemote.created.size)
        assertEquals(id, communityRemote.created.single().id)
    }

    @Test
    fun `creating an activity without a user does not join anyone`() {
        val id = community.createActivity(
            title = "Importada",
            description = "Viene del backend",
            dateLabel = "Domingo",
            locationName = "Sede",
            organizerId = ""
        )

        val created = community.communityActivities.value.single { it.id == id }
        assertFalse(created.isJoined)
        assertEquals(1, communityRemote.created.size)
    }

    private fun activity(id: String) = CommunityActivityItem(
        id = id,
        title = "Taller",
        description = "Tejido",
        dateLabel = "Viernes",
        locationName = "Sede"
    )

    private class RecordingMemoryRemote : MemoryRemotePort {
        val posted = mutableListOf<FifoMemoryItem>()

        override suspend fun postMemory(userId: String, item: FifoMemoryItem) {
            posted.add(item)
        }
    }

    private class RecordingCommunityRemote : CommunityRemotePort {
        val joins = mutableListOf<Triple<String, String, Boolean>>()
        val created = mutableListOf<CommunityActivityItem>()

        override suspend fun toggleJoinActivity(activityId: String, userId: String, join: Boolean) {
            joins.add(Triple(activityId, userId, join))
        }

        override suspend fun createCommunityActivity(item: CommunityActivityItem) {
            created.add(item)
        }
    }

    private companion object {
        const val USER_ID = "usr_carmen"
    }
}
