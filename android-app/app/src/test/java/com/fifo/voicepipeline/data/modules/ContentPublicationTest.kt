package com.fifo.voicepipeline.data.modules

import com.fifo.voicepipeline.data.model.CommunityActivityItem
import com.fifo.voicepipeline.data.model.DailyRoutine
import com.fifo.voicepipeline.data.model.FifoMemoryItem
import com.fifo.voicepipeline.data.model.FifoReminderItem
import com.fifo.voicepipeline.data.model.FifoTasteStory
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
    fun `a new taste is stored locally and published once`() {
        assertTrue(memory.addTaste(USER_ID, "Jardinería"))
        assertFalse(memory.addTaste(USER_ID, "Jardinería"))

        assertEquals(listOf("Jardinería"), memory.tastes.value)
        assertEquals(listOf("Jardinería"), memoryRemote.addedTastes)
        assertTrue(memoryRemote.removedTastes.isEmpty())

        assertTrue(memory.removeTaste(USER_ID, "Jardinería"))
        assertEquals(listOf("Jardinería"), memoryRemote.removedTastes)
    }

    @Test
    fun `a taste story is stored locally and published once`() {
        val story = FifoTasteStory(
            id = "taste_1",
            title = "Orquídeas",
            subtitle = "Riego por inmersión",
            description = "Carmen cuida sus orquídeas cada mañana.",
            tags = listOf("Jardinería"),
            iconCategory = "nature",
            learnedFrom = "Conversación hablada con Fifo"
        )

        memory.addTasteStory(USER_ID, story)

        assertEquals(story, memory.tasteStories.value.single())
        assertEquals(listOf(story), memoryRemote.stories)
    }

    @Test
    fun `a reminder is stored locally and published on every change`() {
        val item = memory.addReminder(USER_ID, "Tomar la presión", "20:00", "health")

        assertEquals(item, memory.reminders.value.single())
        assertEquals(listOf(item), memoryRemote.reminders)

        memory.completeReminder(USER_ID, item.id)
        assertEquals(listOf(item.id), memoryRemote.completedReminders)

        memory.removeReminder(USER_ID, item.id)
        assertTrue(memory.reminders.value.isEmpty())
        assertEquals(listOf(item.id), memoryRemote.deletedReminders)
    }

    @Test
    fun `a daily routine update is published`() {
        val routine = DailyRoutine(id = "routine_1", routineName = "Caminata")

        memory.addOrUpdateDailyRoutine(USER_ID, routine)

        assertEquals(listOf(routine), memory.dailyRoutines.value)
        assertEquals(listOf(routine), memoryRemote.routines)
    }

    @Test
    fun `a social post is stored locally and published once`() {
        community.addSocialPost(
            content = "Hoy caminé por el parque.",
            category = "Bienestar",
            author = "Carmen",
            username = "carmen",
            age = 68,
            userId = USER_ID
        )

        assertEquals(1, community.socialPosts.value.size)
        assertEquals(listOf("Hoy caminé por el parque."), communityRemote.posts)
    }

    @Test
    fun `a social post without a user stays local`() {
        community.addSocialPost(content = "Sin sesión", category = "Bienestar", userId = "")

        assertEquals(1, community.socialPosts.value.size)
        assertTrue(communityRemote.posts.isEmpty())
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
        val addedTastes = mutableListOf<String>()
        val removedTastes = mutableListOf<String>()
        val stories = mutableListOf<FifoTasteStory>()
        val reminders = mutableListOf<FifoReminderItem>()
        val completedReminders = mutableListOf<String>()
        val deletedReminders = mutableListOf<String>()
        val routines = mutableListOf<DailyRoutine>()

        override suspend fun postMemory(userId: String, item: FifoMemoryItem) {
            posted.add(item)
        }

        override suspend fun addTaste(userId: String, taste: String) {
            addedTastes.add(taste)
        }

        override suspend fun removeTaste(userId: String, taste: String) {
            removedTastes.add(taste)
        }

        override suspend fun postTasteStory(userId: String, story: FifoTasteStory) {
            stories.add(story)
        }

        override suspend fun postReminder(userId: String, item: FifoReminderItem) {
            reminders.add(item)
        }

        override suspend fun completeReminder(reminderId: String) {
            completedReminders.add(reminderId)
        }

        override suspend fun deleteReminder(reminderId: String) {
            deletedReminders.add(reminderId)
        }

        override suspend fun postDailyRoutine(userId: String, routine: DailyRoutine) {
            routines.add(routine)
        }
    }

    private class RecordingCommunityRemote : CommunityRemotePort {
        val joins = mutableListOf<Triple<String, String, Boolean>>()
        val created = mutableListOf<CommunityActivityItem>()
        val posts = mutableListOf<String>()

        override suspend fun toggleJoinActivity(activityId: String, userId: String, join: Boolean) {
            joins.add(Triple(activityId, userId, join))
        }

        override suspend fun createCommunityActivity(item: CommunityActivityItem) {
            created.add(item)
        }

        override suspend fun postSocialPost(userId: String, content: String, category: String) {
            posts.add(content)
        }
    }

    private companion object {
        const val USER_ID = "usr_carmen"
    }
}
