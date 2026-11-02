package com.fifo.voicepipeline.data.memory

import android.util.Log
import com.fifo.voicepipeline.data.model.ConversationFragment
import com.fifo.voicepipeline.data.model.PastConversationItem
import com.fifo.voicepipeline.data.modules.EpisodicMemoryModule
import com.fifo.voicepipeline.data.modules.RelationalGraphModule
import com.fifo.voicepipeline.data.modules.UserIdentityModule
import com.fifo.voicepipeline.data.session.UserSession
import com.fifo.voicepipeline.network.CloudApiClient
import com.fifo.voicepipeline.network.FifoBackendClient
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
 * Recibe los módulos dueños del estado (identidad, memoria, grafo relacional y
 * sesión); no lee ni escribe el accessor deprecado.
 *
 * Si la extracción local falla de forma recuperable, devuelve `false` y el
 * `MemoryCoordinator` reprograma el lote con backoff.
 */
class RepositorySessionMemoryConsolidator(
    private val cloudClient: CloudApiClient,
    private val identity: UserIdentityModule,
    private val memory: EpisodicMemoryModule,
    private val relationalGraph: RelationalGraphModule,
    private val session: UserSession,
    private val userIdProvider: () -> String = { identity.userProfile.value.id }
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
        val userId = userIdProvider()

        memory.addConversationFragment(
            userId,
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

        memory.addConversation(
            userId,
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
            memory.addMemory(
                userId,
                emoji = consolidation.memoryEmoji ?: "⭐",
                title = consolidation.memoryTitle,
                detail = consolidation.memoryDetail
            )
        }

        if (!consolidation.newTaste.isNullOrBlank()) {
            memory.addTaste(userId, consolidation.newTaste.trim())
        }

        if (consolidation.mentionedPersons.isNotEmpty()) {
            relationalGraph.ingestSessionMentions(
                mentions = consolidation.mentionedPersons,
                conversationId = "conv_$stableId"
            )
        }

        val backendTurns = turns.flatMap { turn ->
            listOf(
                "user" to turn.userText,
                "assistant" to turn.effectiveAssistantText()
            )
        }
        val backendResult = FifoBackendClient.analyzeAndConsolidateSession(
            userId = userId,
            turns = backendTurns,
            durationSeconds = durationSeconds,
            primaryTag = consolidation.primaryTag,
            mentionedPersons = consolidation.mentionedPersons,
            groqApiKey = cloudClient.effectiveGroqKey.ifBlank { null }
        )

        if (backendResult != null) {
            if (!backendResult.updatedBioAi.isNullOrBlank()) {
                session.updateBio(backendResult.updatedBioAi)
            }
            if (backendResult.interestTags.isNotEmpty()) {
                session.updateInterestTags(backendResult.interestTags)
            }
            backendResult.memoriesAdded.forEach { memory.addMemoryDirect(userId, it) }
            backendResult.tasteStoriesAdded.forEach { memory.addTasteStoryDirect(userId, it) }
            backendResult.remindersAdded.forEach { memory.addReminderDirect(userId, it) }
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
