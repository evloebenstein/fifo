package com.fifo.voicepipeline.data.contacts

import com.fifo.voicepipeline.data.memory.MentionedPersonExtraction
import com.fifo.voicepipeline.ui.model.ContactSource
import com.fifo.voicepipeline.ui.model.FriendRecommendation
import com.fifo.voicepipeline.ui.model.RelationalContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationalGraphCoordinatorTest {

    private val coordinator = RelationalGraphCoordinator()

    private fun phoneContact(
        name: String = "Marta",
        phone: String? = "+56911112222"
    ) = RelationalContact(
        id = "ctc_phone_marta",
        contactName = name,
        phoneNumber = phone,
        relationshipRole = "Contacto del teléfono",
        closenessScore = 0.5f,
        trustTier = 3,
        emotionalValence = "neutral",
        contextualMemory = "Contacto guardado en la libreta del celular.",
        mentionCount = 0
    )

    private fun friend(
        id: String = "usr_ana",
        name: String = "Ana",
        affinity: Float = 0.8f
    ) = FriendRecommendation(
        friendId = id,
        fullName = name,
        age = 70,
        quote = "Me gusta tejer.",
        avatarBgHex = 0L,
        bioAi = "Ana participa en talleres de tejido.",
        sharedTags = listOf("tejido", "música"),
        candTags = emptyList(),
        affinityScore = affinity,
        suggestedTopic = "Tejido",
        friendshipStatus = "suggested"
    )

    @Test
    fun `un contacto nuevo del telefono registra su procedencia`() {
        val merge = coordinator.mergePhoneContacts(
            current = emptyList(),
            phoneContacts = listOf(phoneContact()),
            occurredAt = "2026-10-23T10:00:00Z"
        )

        assertEquals(1, merge.contacts.size)
        assertEquals(1, merge.changed.size)
        assertEquals(ContactSource.PHONE_CONTACT, merge.appearances.single().source)
        assertEquals("+56911112222", merge.appearances.single().sourceId)
        assertEquals("Contacto del teléfono", merge.contacts.single().relationshipRole)
    }

    @Test
    fun `un contacto del telefono no borra la memoria aprendida`() {
        val existing = RelationalContact(
            id = "ctc_rel_marta",
            contactName = "Marta",
            phoneNumber = null,
            relationshipRole = "Vecina",
            closenessScore = 0.82f,
            contextualMemory = "Su vecina de toda la vida.",
            mentionCount = 4
        )

        val merge = coordinator.mergePhoneContacts(
            current = listOf(existing),
            phoneContacts = listOf(phoneContact()),
            occurredAt = "2026-10-23T10:00:00Z"
        )

        val merged = merge.contacts.single()
        assertEquals("ctc_rel_marta", merged.id)
        assertEquals("Vecina", merged.relationshipRole)
        assertEquals(0.82f, merged.closenessScore, 0.001f)
        assertEquals(4, merged.mentionCount)
        assertEquals("+56911112222", merged.phoneNumber)
        assertTrue(merged.contextualMemory!!.contains("vecina"))
        assertEquals(1, merge.appearances.size)
    }

    @Test
    fun `un amigo de FifoAmigos no inventa parentesco`() {
        val merge = coordinator.mergeFifoFriends(
            current = emptyList(),
            friends = listOf(friend()),
            occurredAt = "2026-10-23T10:00:00Z"
        )

        val created = merge.contacts.single()
        assertEquals("Amigo de FifoAmigos", created.relationshipRole)
        assertEquals(3, created.trustTier)
        assertNull(created.phoneNumber)
        assertTrue(created.closenessScore <= 0.70f)
        assertEquals(ContactSource.FIFO_FRIEND, merge.appearances.single().source)
        assertEquals("usr_ana", merge.appearances.single().sourceId)
        assertTrue(merge.appearances.single().snippet!!.contains("suggested"))
    }

    @Test
    fun `un amigo existente conserva su rol y su mejor puntuacion`() {
        val existing = RelationalContact(
            id = "ctc_rel_ana",
            contactName = "Ana",
            phoneNumber = "+56900000000",
            relationshipRole = "Hermana",
            closenessScore = 0.95f,
            contextualMemory = "Su hermana mayor."
        )

        val merge = coordinator.mergeFifoFriends(
            current = listOf(existing),
            friends = listOf(friend()),
            occurredAt = "2026-10-23T10:00:00Z"
        )

        val merged = merge.contacts.single()
        assertEquals("Hermana", merged.relationshipRole)
        assertEquals(0.95f, merged.closenessScore, 0.001f)
        assertTrue(merged.contextualMemory!!.contains("hermana"))
        assertTrue(merged.contextualMemory!!.contains("FifoAmigos"))
        assertEquals("ctc_rel_ana", merge.contacts.single().id)
    }

    @Test
    fun `una mencion nueva vincula la conversacion`() {
        val merge = coordinator.mergeSessionMentions(
            current = emptyList(),
            mentions = listOf(
                MentionedPersonExtraction(
                    name = "Lucas",
                    inferredRole = "Nieto",
                    contextSnippet = "Mi nieto Lucas me llamó ayer.",
                    emotionalValence = "afectuoso",
                    closenessDelta = 0.08f
                )
            ),
            conversationId = "conv_abc",
            occurredAt = "2026-10-23T10:00:00Z"
        )

        val created = merge.contacts.single()
        assertEquals("Nieto", created.relationshipRole)
        assertEquals(listOf("conv_abc"), created.linkedConversationIds)
        assertEquals(1, created.mentionCount)
        assertEquals(ContactSource.CONVERSATION, merge.appearances.single().source)
        assertEquals("conv_abc", merge.appearances.single().sourceId)
        assertEquals("Mi nieto Lucas me llamó ayer.", merge.appearances.single().snippet)
    }

    @Test
    fun `una mencion sobre un contacto del telefono aprende el rol`() {
        val merge = coordinator.mergeSessionMentions(
            current = listOf(phoneContact()),
            mentions = listOf(
                MentionedPersonExtraction(
                    name = "Marta",
                    inferredRole = "Vecina",
                    contextSnippet = "Marta me trajo pan.",
                    closenessDelta = 0.1f
                )
            ),
            conversationId = "conv_abc",
            occurredAt = "2026-10-23T10:00:00Z"
        )

        val merged = merge.contacts.single()
        assertEquals("Vecina", merged.relationshipRole)
        assertEquals(1, merged.mentionCount)
        assertEquals(listOf("conv_abc"), merged.linkedConversationIds)
        assertTrue(merged.contextualMemory!!.contains("Marta me trajo pan"))
    }

    @Test
    fun `la misma conversacion no duplica la aparicion`() {
        val first = coordinator.mergeSessionMentions(
            current = emptyList(),
            mentions = listOf(MentionedPersonExtraction(name = "Lucas", contextSnippet = "Lucas")),
            conversationId = "conv_abc",
            occurredAt = "2026-10-23T10:00:00Z"
        )
        val second = coordinator.mergeSessionMentions(
            current = first.contacts,
            mentions = listOf(MentionedPersonExtraction(name = "Lucas", contextSnippet = "Lucas otra vez")),
            conversationId = "conv_abc",
            occurredAt = "2026-10-23T10:05:00Z"
        )

        assertEquals(first.appearances.single().id, second.appearances.single().id)
        assertEquals(listOf("conv_abc"), second.contacts.single().linkedConversationIds)
    }

    @Test
    fun `una mencion sin conversacion no crea aparicion`() {
        val merge = coordinator.mergeSessionMentions(
            current = emptyList(),
            mentions = listOf(MentionedPersonExtraction(name = "Lucas")),
            conversationId = null
        )

        assertTrue(merge.appearances.isEmpty())
        assertEquals(1, merge.changed.size)
    }

    @Test
    fun `nombres vacios se ignoran`() {
        val merge = coordinator.mergeSessionMentions(
            current = emptyList(),
            mentions = listOf(MentionedPersonExtraction(name = "   ")),
            conversationId = "conv_abc"
        )

        assertTrue(merge.contacts.isEmpty())
        assertTrue(merge.changed.isEmpty())
        assertTrue(merge.appearances.isEmpty())
    }

    @Test
    fun `un amigo sin id genera un id estable`() {
        val first = coordinator.mergeFifoFriends(emptyList(), listOf(friend(id = "", name = "Rosa")))
        val second = coordinator.mergeFifoFriends(emptyList(), listOf(friend(id = "", name = "Rosa")))

        assertNotNull(first.contacts.single())
        assertEquals(first.contacts.single().id, second.contacts.single().id)
        assertFalse(first.contacts.single().id.isBlank())
    }

    @Test
    fun `una mencion con telefono explicito lo asocia al contacto`() {
        val existing = RelationalContact(
            id = "ctc_rel_marta",
            contactName = "Marta",
            phoneNumber = null,
            relationshipRole = "Vecina",
            closenessScore = 0.7f,
            contextualMemory = "Su vecina."
        )

        val merge = coordinator.mergeSessionMentions(
            current = listOf(existing),
            mentions = listOf(
                MentionedPersonExtraction(
                    name = "Marta",
                    contextSnippet = "Mi vecina Marta me dio su celular 9 1234 5678.",
                    closenessDelta = 0.05f
                )
            ),
            conversationId = "conv_abc"
        )

        assertEquals("+56912345678", merge.contacts.single().phoneNumber)
    }

    @Test
    fun `una mencion no reemplaza el telefono conocido`() {
        val merge = coordinator.mergeSessionMentions(
            current = listOf(phoneContact()),
            mentions = listOf(
                MentionedPersonExtraction(
                    name = "Marta",
                    contextSnippet = "Su nuevo teléfono es 9 8765 4321."
                )
            ),
            conversationId = "conv_abc"
        )

        assertEquals("+56911112222", merge.contacts.single().phoneNumber)
    }

    @Test
    fun `una mencion nueva con telefono explicito lo guarda`() {
        val merge = coordinator.mergeSessionMentions(
            current = emptyList(),
            mentions = listOf(
                MentionedPersonExtraction(
                    name = "Lucas",
                    inferredRole = "Nieto",
                    contextSnippet = "Mi nieto Lucas me dio su celular +56 9 1111 2222."
                )
            ),
            conversationId = "conv_abc"
        )

        val created = merge.contacts.single()
        assertEquals("Nieto", created.relationshipRole)
        assertEquals("+56911112222", created.phoneNumber)
    }

    @Test
    fun `una mencion sin numero no inventa telefono`() {
        val merge = coordinator.mergeSessionMentions(
            current = emptyList(),
            mentions = listOf(
                MentionedPersonExtraction(
                    name = "Lucas",
                    contextSnippet = "Mi nieto Lucas me llamó ayer."
                )
            ),
            conversationId = "conv_abc"
        )

        assertNull(merge.contacts.single().phoneNumber)
    }

    @Test
    fun `el camino sin conversacion no extrae telefono`() {
        val merge = coordinator.mergeSessionMentions(
            current = emptyList(),
            mentions = listOf(
                MentionedPersonExtraction(
                    name = "Lucas",
                    contextSnippet = "Mi nieto Lucas me dio su celular +56 9 1111 2222."
                )
            ),
            conversationId = null
        )

        assertNull(merge.contacts.single().phoneNumber)
    }
}
