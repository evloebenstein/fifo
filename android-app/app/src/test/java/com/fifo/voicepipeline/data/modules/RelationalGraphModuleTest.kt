package com.fifo.voicepipeline.data.modules

import com.fifo.voicepipeline.data.memory.MentionedPersonExtraction
import com.fifo.voicepipeline.data.model.FriendRecommendation
import com.fifo.voicepipeline.data.model.RelationalContact
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifica que [RelationalGraphModule] sea el dueño del grafo relacional:
 * ingesta, fusión, rol genérico→concreto y publicación por la costura remota,
 * sin tocar Android ni la red.
 */
class RelationalGraphModuleTest {

    private lateinit var memory: EpisodicMemoryModule
    private lateinit var remote: RecordingRelationalRemote
    private lateinit var graph: RelationalGraphModule

    @Before
    fun setUp() {
        memory = EpisodicMemoryModule(InMemoryMemoryStorageAdapter())
        memory.loadUserData(USER_ID)
        remote = RecordingRelationalRemote()
        graph = RelationalGraphModule(
            memory = memory,
            database = { null },
            scope = TestScope(UnconfinedTestDispatcher()),
            userId = { USER_ID },
            remote = remote,
            nowIso = { "2026-10-30T12:00:00Z" }
        )
    }

    @Test
    fun `ingestContacts adds new contacts and publishes them once`() {
        val ingested = graph.ingestContacts(
            listOf(
                phoneContact("ctc_phone_maria_1111", "María", "+56911111111"),
                phoneContact("ctc_phone_pedro_2222", "Pedro", "+56922222222")
            )
        )

        assertEquals(2, ingested)
        assertEquals(2, memory.relationalContacts.value.size)
        assertEquals(1, remote.syncBatches.size)
        assertEquals(2, remote.syncBatches.single().size)
    }

    @Test
    fun `re-ingesting a contact upgrades a generic role without duplicating it`() {
        graph.ingestContacts(listOf(phoneContact("ctc_phone_maria_1111", "María", "+56911111111")))

        graph.ingestContacts(
            listOf(phoneContact("ctc_phone_maria_1111", "María", "+56911111111").copy(relationshipRole = "Hija"))
        )

        val contacts = memory.relationalContacts.value
        assertEquals(1, contacts.size)
        assertEquals("Hija", contacts.single().relationshipRole)
    }

    @Test
    fun `session mentions increment the mention count and publish the contact`() {
        graph.ingestSessionMentions(
            mentions = listOf(
                MentionedPersonExtraction(
                    name = "Tomás",
                    inferredRole = "Nieto",
                    contextSnippet = "Tomás me visitó el domingo.",
                    emotionalValence = "afectuoso"
                )
            ),
            conversationId = "conv_1"
        )
        graph.ingestSessionMentions(
            mentions = listOf(
                MentionedPersonExtraction(
                    name = "Tomás",
                    contextSnippet = "Tomás trajo pan amasado."
                )
            ),
            conversationId = "conv_2"
        )

        val tomas = memory.relationalContacts.value.single { it.contactName == "Tomás" }
        assertEquals(2, tomas.mentionCount)
        assertEquals("Nieto", tomas.relationshipRole)
        assertEquals(listOf("conv_1", "conv_2"), tomas.linkedConversationIds)
        assertEquals(2, remote.postedContacts.size)
    }

    @Test
    fun `findContactByRoleOrName resolves by role and by name`() {
        graph.ingestSessionMentions(
            mentions = listOf(
                MentionedPersonExtraction(
                    name = "Dra. Rojas",
                    inferredRole = "Médica",
                    contextSnippet = "Mi médica de cabecera."
                )
            ),
            conversationId = "conv_1"
        )

        assertNotNull(graph.findContactByRoleOrName("médica"))
        assertNotNull(graph.findContactByRoleOrName("Dra. Rojas"))
    }

    @Test
    fun `ingestFifoFriends creates a friend contact without inventing kinship`() {
        val ingested = graph.ingestFifoFriends(
            listOf(
                FriendRecommendation(
                    friendId = "fr_1",
                    fullName = "Elena",
                    age = 68,
                    quote = "Me gusta tejer.",
                    avatarBgHex = 0xFF38BDF8,
                    bioAi = "Teje mantas para sus nietos.",
                    sharedTags = listOf("Tejido"),
                    candTags = emptyList(),
                    affinityScore = 0.8f,
                    suggestedTopic = "Tejido"
                )
            )
        )

        assertEquals(1, ingested)
        val elena = memory.relationalContacts.value.single { it.contactName == "Elena" }
        assertEquals("Amigo de FifoAmigos", elena.relationshipRole)
        assertTrue(elena.contextualMemory.orEmpty().contains("FifoAmigos"))
    }

    private fun phoneContact(id: String, name: String, phone: String) = RelationalContact(
        id = id,
        contactName = name,
        phoneNumber = phone,
        relationshipRole = "Contacto del teléfono",
        closenessScore = 0.5f,
        trustTier = 3
    )

    private class RecordingRelationalRemote : RelationalRemotePort {
        val postedContacts = mutableListOf<RelationalContact>()
        val syncBatches = mutableListOf<List<RelationalContact>>()

        override suspend fun postContact(userId: String, contact: RelationalContact) {
            postedContacts.add(contact)
        }

        override suspend fun syncContacts(userId: String, contacts: List<RelationalContact>) {
            syncBatches.add(contacts)
        }
    }

    private companion object {
        const val USER_ID = "usr_carmen"
    }
}
