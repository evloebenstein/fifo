package com.fifo.voicepipeline.data.contacts

import com.fifo.voicepipeline.data.memory.MentionedPersonExtraction
import com.fifo.voicepipeline.ui.model.ContactAppearance
import com.fifo.voicepipeline.ui.model.ContactPhoneHint
import com.fifo.voicepipeline.ui.model.ContactSource
import com.fifo.voicepipeline.ui.model.FriendRecommendation
import com.fifo.voicepipeline.ui.model.PhoneHintStatus
import com.fifo.voicepipeline.ui.model.RelationalContact

/**
 * Resultado de una ingesta al grafo relacional.
 *
 * `contacts` es la lista completa ya fusionada, `changed` solo los contactos que
 * deben persistirse y `appearances` las apariciones nuevas (deduplicadas por
 * origen y persona). `phoneHints` son teléfonos mencionados que esperan
 * confirmación del usuario; no se copian al contacto todavía.
 */
data class RelationalGraphMerge(
    val contacts: List<RelationalContact>,
    val changed: List<RelationalContact>,
    val appearances: List<ContactAppearance>,
    val phoneHints: List<ContactPhoneHint> = emptyList()
)

/**
 * Política pura de fusión del grafo relacional.
 *
 * Combina tres fuentes sin conocer Android, SQLite ni HTTP:
 *
 * - libreta del teléfono ([mergePhoneContacts]);
 * - amigos de FifoAmigos ([mergeFifoFriends]);
 * - personas mencionadas en conversaciones ([mergeSessionMentions]).
 *
 * No inventa parentescos: una recomendación de FifoAmigos entra como
 * `Amigo de FifoAmigos`, y una mención sin rol entra como `Persona cercana`.
 * Tampoco inventa teléfonos: una mención solo genera un indicio pendiente
 * ([PhoneHintExtractor]) y nunca reemplaza el que ya conoce la libreta.
 */
class RelationalGraphCoordinator {

    fun mergePhoneContacts(
        current: List<RelationalContact>,
        phoneContacts: List<RelationalContact>,
        occurredAt: String = ""
    ): RelationalGraphMerge {
        val contacts = current.toMutableList()
        val changed = mutableListOf<RelationalContact>()
        val appearances = mutableListOf<ContactAppearance>()

        phoneContacts.forEach { phoneContact ->
            val index = contacts.indexOfFirst { existing ->
                existing.contactName.equals(phoneContact.contactName, ignoreCase = true) ||
                    (!existing.phoneNumber.isNullOrBlank() && existing.phoneNumber == phoneContact.phoneNumber)
            }

            val resolved = if (index >= 0) {
                val existing = contacts[index]
                val updated = existing.copy(
                    phoneNumber = phoneContact.phoneNumber ?: existing.phoneNumber,
                    relationshipRole = if (
                        existing.relationshipRole in GENERIC_ROLES &&
                        phoneContact.relationshipRole !in GENERIC_ROLES
                    ) {
                        phoneContact.relationshipRole
                    } else {
                        existing.relationshipRole
                    },
                    alias = existing.alias ?: phoneContact.alias
                )
                contacts[index] = updated
                if (updated != existing) changed.add(updated)
                updated
            } else {
                contacts.add(phoneContact)
                changed.add(phoneContact)
                phoneContact
            }

            appearances.add(
                appearance(
                    contactId = resolved.id,
                    source = ContactSource.PHONE_CONTACT,
                    sourceId = phoneContact.phoneNumber,
                    snippet = "Contacto guardado en la libreta del teléfono.",
                    occurredAt = occurredAt
                )
            )
        }

        return RelationalGraphMerge(
            contacts = contacts,
            changed = changed.distinctBy { it.id },
            appearances = appearances.distinctBy { it.id }
        )
    }

    fun mergeFifoFriends(
        current: List<RelationalContact>,
        friends: List<FriendRecommendation>,
        occurredAt: String = ""
    ): RelationalGraphMerge {
        val contacts = current.toMutableList()
        val changed = mutableListOf<RelationalContact>()
        val appearances = mutableListOf<ContactAppearance>()

        friends.forEach { friend ->
            val name = friend.fullName.trim()
            if (name.isBlank()) return@forEach

            val index = contacts.indexOfFirst { it.contactName.equals(name, ignoreCase = true) }
            val learnedScore = minOf(0.70f, 0.45f + friend.affinityScore.coerceIn(0f, 1f) * 0.25f)
            val friendContext = friendContext(friend)

            val resolved = if (index >= 0) {
                val existing = contacts[index]
                val updated = existing.copy(
                    closenessScore = maxOf(existing.closenessScore, learnedScore),
                    contextualMemory = appendMemory(existing.contextualMemory, friendContext)
                )
                contacts[index] = updated
                if (updated != existing) changed.add(updated)
                updated
            } else {
                val created = RelationalContact(
                    id = friendContactId(friend, name),
                    contactName = name,
                    phoneNumber = null,
                    relationshipRole = "Amigo de FifoAmigos",
                    closenessScore = learnedScore,
                    trustTier = 3,
                    emotionalValence = "neutral",
                    contextualMemory = friendContext.take(400),
                    mentionCount = 0,
                    lastMentionedAt = "",
                    alias = null
                )
                contacts.add(created)
                changed.add(created)
                created
            }

            appearances.add(
                appearance(
                    contactId = resolved.id,
                    source = ContactSource.FIFO_FRIEND,
                    sourceId = friend.friendId.ifBlank { name.lowercase() },
                    snippet = "$friendContext [${friend.friendshipStatus}]",
                    occurredAt = occurredAt
                )
            )
        }

        return RelationalGraphMerge(
            contacts = contacts,
            changed = changed.distinctBy { it.id },
            appearances = appearances.distinctBy { it.id }
        )
    }

    fun mergeSessionMentions(
        current: List<RelationalContact>,
        mentions: List<MentionedPersonExtraction>,
        conversationId: String? = null,
        occurredAt: String = ""
    ): RelationalGraphMerge {
        val contacts = current.toMutableList()
        val changed = mutableListOf<RelationalContact>()
        val appearances = mutableListOf<ContactAppearance>()
        val phoneHints = mutableListOf<ContactPhoneHint>()
        val allowPhoneHint = !conversationId.isNullOrBlank()

        mentions.forEach { mention ->
            val mentionName = mention.name.trim()
            if (mentionName.isBlank()) return@forEach

            val index = contacts.indexOfFirst { existing -> matchesMention(existing, mentionName, mention.inferredRole) }
            val resolved = if (index >= 0) {
                val existing = contacts[index]
                val valenceBonus = when (mention.emotionalValence.lowercase()) {
                    "afectuoso", "gratitud" -> 0.04f
                    "preocupacion", "apoyo" -> 0.03f
                    else -> 0.02f
                }
                val newScore = minOf(
                    1.0f,
                    existing.closenessScore + mention.closenessDelta.coerceAtLeast(0.03f) + valenceBonus
                )
                val newRole = if (
                    existing.relationshipRole in GENERIC_ROLES &&
                    !mention.inferredRole.isNullOrBlank()
                ) {
                    mention.inferredRole
                } else {
                    existing.relationshipRole
                }
                val updated = existing.copy(
                    closenessScore = newScore,
                    mentionCount = existing.mentionCount + 1,
                    relationshipRole = newRole,
                    contextualMemory = enrichMentionMemory(existing.contextualMemory, mention.contextSnippet),
                    emotionalValence = mention.emotionalValence,
                    lastMentionedAt = "Hoy",
                    linkedConversationIds = linkConversation(existing.linkedConversationIds, conversationId)
                )
                contacts[index] = updated
                changed.add(updated)
                updated
            } else {
                val created = RelationalContact(
                    id = "ctc_rel_${newContactSuffix(mentionName)}",
                    contactName = mentionName,
                    phoneNumber = null,
                    relationshipRole = mention.inferredRole ?: "Persona cercana",
                    closenessScore = 0.65f,
                    trustTier = 3,
                    emotionalValence = mention.emotionalValence,
                    contextualMemory = mention.contextSnippet.ifBlank {
                        "Mencionado(a) en charla reciente con Fifo."
                    },
                    mentionCount = 1,
                    lastMentionedAt = "Hoy",
                    alias = mention.inferredRole?.lowercase(),
                    linkedConversationIds = linkConversation(emptyList(), conversationId)
                )
                contacts.add(created)
                changed.add(created)
                created
            }

            if (allowPhoneHint) {
                PhoneHintExtractor.extract(
                    snippet = mention.contextSnippet,
                    currentPhone = resolved.phoneNumber
                )?.let { phone ->
                    phoneHints.add(
                        phoneHint(
                            contactId = resolved.id,
                            phoneNumber = phone,
                            snippet = mention.contextSnippet,
                            occurredAt = occurredAt
                        )
                    )
                }
            }

            if (!conversationId.isNullOrBlank()) {
                appearances.add(
                    appearance(
                        contactId = resolved.id,
                        source = ContactSource.CONVERSATION,
                        sourceId = conversationId,
                        snippet = mention.contextSnippet.ifBlank { null },
                        occurredAt = occurredAt
                    )
                )
            }
        }

        return RelationalGraphMerge(
            contacts = contacts,
            changed = changed.distinctBy { it.id },
            appearances = appearances.distinctBy { it.id },
            phoneHints = phoneHints.distinctBy { it.id }
        )
    }

    private fun matchesMention(
        existing: RelationalContact,
        mentionName: String,
        inferredRole: String?
    ): Boolean {
        val existingName = existing.contactName.lowercase().trim()
        val name = mentionName.lowercase()
        val roleMatch = inferredRole?.let { role ->
            existing.relationshipRole.contains(role, ignoreCase = true)
        } ?: false
        return existingName == name ||
            existingName.contains(name) ||
            name.contains(existingName) ||
            (existing.alias != null && existing.alias.equals(name, ignoreCase = true)) ||
            roleMatch
    }

    private fun enrichMentionMemory(previous: String?, snippet: String): String {
        return buildString {
            val prev = previous?.trim().orEmpty()
            if (prev.isNotBlank() && !prev.contains("libreta del celular")) {
                append(prev)
                if (!prev.endsWith(".")) append(".")
                if (snippet.isNotBlank() && !prev.contains(snippet)) {
                    append(" ")
                    append(snippet)
                }
            } else if (snippet.isNotBlank()) {
                append(snippet)
            } else {
                append("Mencionado(a) con afecto en conversaciones recientes.")
            }
        }.take(400)
    }

    private fun appendMemory(previous: String?, addition: String): String {
        val prev = previous?.trim().orEmpty()
        val extra = addition.trim()
        if (extra.isBlank()) return prev
        if (prev.isBlank()) return extra.take(400)
        if (prev.contains(extra)) return prev
        val separator = if (prev.endsWith(".")) " " else ". "
        return (prev + separator + extra).take(400)
    }

    private fun linkConversation(existing: List<String>, conversationId: String?): List<String> {
        if (conversationId.isNullOrBlank()) return existing
        return (existing + conversationId).distinct().takeLast(MAX_LINKED_CONVERSATIONS)
    }

    private fun friendContext(friend: FriendRecommendation): String {
        val parts = mutableListOf<String>()
        if (friend.quote.isNotBlank()) parts.add(friend.quote.trim())
        if (friend.bioAi.isNotBlank()) parts.add(friend.bioAi.trim())
        if (friend.sharedTags.isNotEmpty()) {
            parts.add("Intereses compartidos: ${friend.sharedTags.joinToString(", ")}.")
        }
        val detail = parts.joinToString(" ").ifBlank { "Perfil de FifoAmigos." }
        return "FifoAmigos: $detail".take(400)
    }

    private fun friendContactId(friend: FriendRecommendation, name: String): String {
        val suffix = friend.friendId.ifBlank {
            name.lowercase().replace(Regex("[^a-z0-9]"), "_").take(24)
        }
        return "ctc_fifo_$suffix"
    }

    private fun newContactSuffix(name: String): String {
        val slug = name.lowercase().replace(Regex("[^a-z0-9]"), "_").take(16)
        return "${slug}_${Integer.toHexString(name.hashCode())}"
    }

    private fun appearance(
        contactId: String,
        source: ContactSource,
        sourceId: String?,
        snippet: String?,
        occurredAt: String
    ): ContactAppearance {
        val key = "$contactId|${source.name}|${sourceId.orEmpty()}"
        return ContactAppearance(
            id = "app_${Integer.toHexString(key.hashCode())}",
            contactId = contactId,
            source = source,
            sourceId = sourceId,
            snippet = snippet,
            occurredAt = occurredAt
        )
    }

    private fun phoneHint(
        contactId: String,
        phoneNumber: String,
        snippet: String?,
        occurredAt: String
    ): ContactPhoneHint {
        val key = "$contactId|$phoneNumber"
        return ContactPhoneHint(
            id = "hint_${Integer.toHexString(key.hashCode())}",
            contactId = contactId,
            phoneNumber = phoneNumber,
            snippet = snippet?.ifBlank { null },
            createdAt = occurredAt,
            status = PhoneHintStatus.PENDING
        )
    }

    private companion object {
        val GENERIC_ROLES = setOf("Contacto del teléfono", "Desconocido")
        const val MAX_LINKED_CONVERSATIONS = 20
    }
}
