package com.fifo.voicepipeline.data.memory

import android.util.Log
import com.fifo.voicepipeline.data.FifoDataRepository
import com.fifo.voicepipeline.network.CloudApiClient
import com.fifo.voicepipeline.network.FifoBackendClient
import com.fifo.voicepipeline.ui.model.ConversationFragment
import com.fifo.voicepipeline.ui.model.PastConversationItem
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Consolidador de producción: convierte un lote de turnos durables en las
 * mismas mutaciones que usaba el camino legacy, pero con turnos etiquetados
 * correctamente (`user`/`assistant`) y con IDs estables para que un reintento
 * no duplique fragmentos ni conversaciones.
 *
 * Si la extracción local falla de forma recuperable, devuelve `false` y el
 * `MemoryCoordinator` reprograma el lote con backoff.
 */
class RepositorySessionMemoryConsolidator(
    private val cloudClient: CloudApiClient,
    private val userIdProvider: () -> String = { FifoDataRepository.userProfile.value.id }
) : SessionMemoryConsolidator {

    private companion object {
        const val TAG = "SessionMemoryConsolidator"
    }

    override suspend fun consolidate(turns: List<JournalEntry>): Boolean {
        if (turns.isEmpty()) return true

        val consolidation = try {
            if (cloudClient.isGroqActive) {
                // null significa "no había nada significativo que recordar":
                // el turno puede marcarse consolidado sin inventar memoria.
                cloudClient.extractSessionMemoryChecked(
                    turns.map { it.userText to it.effectiveAssistantText() }
                ) ?: return true
            } else {
                fallbackConsolidation(turns)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo extraer memoria de la sesión: ${e.message}")
            return false
        }

        return try {
            persist(consolidation, turns)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo persistir la memoria de la sesión: ${e.message}", e)
            false
        }
    }

    private suspend fun persist(
        consolidation: SessionMemoryConsolidation,
        turns: List<JournalEntry>
    ) {
        val durationSeconds = (turns.size * 20).coerceAtLeast(30)
        val isoTimestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val stableId = turns.first().id

        FifoDataRepository.addConversationFragment(
            ConversationFragment(
                id = "frag_$stableId",
                serverConversationId = "srv_$stableId",
                keyTopics = consolidation.keyTopics.ifEmpty { listOf(consolidation.primaryTag) },
                namedEntities = consolidation.namedEntities,
                detectedMood = consolidation.detectedMood,
                compactSummary = consolidation.compactSummary
                    .ifBlank { "Charla sobre ${consolidation.primaryTag}" },
                primaryTag = consolidation.primaryTag,
                durationSeconds = durationSeconds,
                recordedAt = isoTimestamp
            )
        )

        FifoDataRepository.addConversation(
            PastConversationItem(
                id = "conv_$stableId",
                title = consolidation.conversationTitle
                    .ifBlank { "Charla sobre ${consolidation.primaryTag}" },
                date = "Hoy",
                duration = "$durationSeconds seg",
                summary = consolidation.compactSummary,
                tag = consolidation.primaryTag,
                iconName = iconFor(consolidation.primaryTag)
            )
        )

        if (!consolidation.memoryTitle.isNullOrBlank() &&
            !consolidation.memoryDetail.isNullOrBlank()
        ) {
            FifoDataRepository.addMemory(
                emoji = consolidation.memoryEmoji ?: "⭐",
                title = consolidation.memoryTitle,
                detail = consolidation.memoryDetail
            )
        }

        if (!consolidation.newTaste.isNullOrBlank()) {
            FifoDataRepository.addTaste(consolidation.newTaste.trim())
        }

        if (consolidation.mentionedPersons.isNotEmpty()) {
            FifoDataRepository.updateContactsFromSessionMentions(consolidation.mentionedPersons)
        }

        val backendTurns = turns.flatMap { turn ->
            listOf(
                "user" to turn.userText,
                "assistant" to turn.effectiveAssistantText()
            )
        }
        val backendResult = FifoBackendClient.analyzeAndConsolidateSession(
            userId = userIdProvider(),
            turns = backendTurns,
            durationSeconds = durationSeconds,
            primaryTag = consolidation.primaryTag,
            mentionedPersons = consolidation.mentionedPersons,
            groqApiKey = cloudClient.effectiveGroqKey.ifBlank { null }
        )

        if (backendResult != null) {
            if (!backendResult.updatedBioAi.isNullOrBlank()) {
                FifoDataRepository.updateBio(backendResult.updatedBioAi)
            }
            if (backendResult.interestTags.isNotEmpty()) {
                FifoDataRepository.updateInterestTags(backendResult.interestTags)
            }
            backendResult.memoriesAdded.forEach { FifoDataRepository.addMemoryDirect(it) }
            backendResult.tasteStoriesAdded.forEach { FifoDataRepository.addTasteStoryDirect(it) }
            backendResult.remindersAdded.forEach { FifoDataRepository.addReminderDirect(it) }
        }
    }

    /**
     * Si el turno fue reparado, la memoria debe aprender la respuesta corregida,
     * no la versión errónea que alcanzó a escucharse.
     */
    private fun JournalEntry.effectiveAssistantText(): String =
        repairText?.takeIf { it.isNotBlank() } ?: assistantText

    private fun fallbackConsolidation(turns: List<JournalEntry>): SessionMemoryConsolidation {
        val firstUserText = turns.firstOrNull()?.userText?.take(120).orEmpty()
        return SessionMemoryConsolidation(
            primaryTag = "Conversación",
            keyTopics = turns.take(3).map { it.userText.take(40) },
            compactSummary = if (firstUserText.isBlank()) {
                "Charla breve con Fifo."
            } else {
                "El usuario conversó sobre $firstUserText."
            },
            conversationTitle = "Charla con Fifo"
        )
    }

    private fun iconFor(tag: String): String = when (tag.lowercase(Locale.ROOT)) {
        "deporte", "deportes", "ejercicio", "jiujitsu" -> "air"
        "familia", "amigos" -> "heart"
        "comida", "cocina" -> "restaurant"
        "música", "musica" -> "music"
        else -> "chat"
    }
}
