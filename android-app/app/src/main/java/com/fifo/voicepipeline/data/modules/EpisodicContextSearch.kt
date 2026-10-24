package com.fifo.voicepipeline.data.modules

import com.fifo.voicepipeline.ui.model.ConversationFragment
import com.fifo.voicepipeline.ui.model.DailyRoutine
import com.fifo.voicepipeline.ui.model.DeepContextResult
import com.fifo.voicepipeline.ui.model.FifoMemoryItem
import com.fifo.voicepipeline.ui.model.FifoReminderItem
import com.fifo.voicepipeline.ui.model.FifoTasteStory
import com.fifo.voicepipeline.ui.model.PastConversationItem
import com.fifo.voicepipeline.ui.model.RelationalContact

/**
 * Instantánea de la memoria episódica local que usa la búsqueda profunda.
 */
internal data class EpisodicContextSnapshot(
    val fragments: List<ConversationFragment> = emptyList(),
    val memories: List<FifoMemoryItem> = emptyList(),
    val conversations: List<PastConversationItem> = emptyList(),
    val tastes: List<String> = emptyList(),
    val tasteStories: List<FifoTasteStory> = emptyList(),
    val reminders: List<FifoReminderItem> = emptyList(),
    val relationalContacts: List<RelationalContact> = emptyList(),
    val dailyRoutines: List<DailyRoutine> = emptyList()
)

/**
 * Búsqueda local de contexto profundo sobre la memoria episódica.
 *
 * Es una política pura: recibe la instantánea de datos y la consulta, y no
 * conoce red, SQLite ni `StateFlow`. `EpisodicMemoryModule` decide primero si el
 * backend responde; si no, delega aquí como fallback offline.
 *
 * Reglas:
 * - normaliza a minúsculas, pero no elimina acentos (la memoria se guarda tal
 *   como se habló);
 * - descarta palabras vacías y tokens de menos de 3 caracteres;
 * - los fragmentos puntúan 2 por token y 4 por coincidencia literal, con un
 *   sesgo temporal para “ayer”, “la semana pasada” y similares;
 * - devuelve como máximo 3 fragmentos y 5 entidades en el resumen.
 */
internal object EpisodicContextSearch {

    private val TOKEN_SEPARATOR = Regex("[^a-záéíóúñ0-9]+")
    private const val MIN_TOKEN_LENGTH = 3
    private const val MAX_FRAGMENTS = 3
    private const val MAX_ENTITIES = 5

    private val STOP_WORDS = setOf(
        "el", "la", "los", "las", "un", "una", "unos", "unas", "de", "del", "a", "al", "en", "con",
        "por", "para", "que", "es", "fue", "fui", "como", "hace", "donde", "cual", "mi", "mis",
        "tu", "tus", "su", "sus", "ese", "esa", "esos", "esas", "este", "esta", "estos", "estas",
        "quiero", "ir", "nombre", "llama", "llamaba", "local", "lugar", "sitio", "cosa", "algo", "pero"
    )

    fun search(query: String, snapshot: EpisodicContextSnapshot): DeepContextResult {
        val queryLower = query.lowercase().trim()
        val queryTokens = queryLower
            .split(TOKEN_SEPARATOR)
            .filter { it.length >= MIN_TOKEN_LENGTH && it !in STOP_WORDS }

        val isRecentTemporal = queryLower.contains("ayer") || queryLower.contains("antier") ||
            queryLower.contains("semana pasada") || queryLower.contains("hace poco") ||
            queryLower.contains("recien") || queryLower.contains("reciente") ||
            queryLower.contains("último") || queryLower.contains("ultimo")

        val excerpts = mutableListOf<String>()
        val foundEntities = mutableSetOf<String>()

        // A. Grafo relacional de contactos.
        for (contact in snapshot.relationalContacts) {
            val contactText = "${contact.contactName} ${contact.relationshipRole} ${contact.alias ?: ""} ${contact.contextualMemory}".lowercase()
            val matchesAlias = contact.alias != null && queryLower.contains(contact.alias.lowercase())
            if (queryTokens.any { contactText.contains(it) } ||
                queryLower.contains(contact.contactName.lowercase()) ||
                matchesAlias
            ) {
                excerpts.add(
                    "Contacto cercano: ${contact.contactName} (${contact.relationshipRole}, " +
                        "cercanía ${(contact.closenessScore * 100).toInt()}%). Memoria: ${contact.contextualMemory}"
                )
                foundEntities.add(contact.contactName)
                foundEntities.add(contact.relationshipRole)
            }
        }

        // B. Rutinas y hábitos diarios.
        for (routine in snapshot.dailyRoutines) {
            val routineText = ("${routine.routineName} ${routine.notes ?: ""} ${routine.timeAnchor} " +
                "${routine.typicalTimeStr ?: ""} ${routine.category}").lowercase()
            if (queryTokens.any { routineText.contains(it) } ||
                queryLower.contains(routine.routineName.lowercase())
            ) {
                excerpts.add(
                    "Patrón de rutina diaria: ${routine.routineName} a las " +
                        "${routine.typicalTimeStr ?: ""} (${routine.timeAnchor}). Detalle: ${routine.notes ?: ""}"
                )
                foundEntities.add(routine.routineName)
            }
        }

        // C. Recuerdos persistentes.
        for (memory in snapshot.memories) {
            val memoryText = "${memory.title} ${memory.detail}".lowercase()
            if (matchesText(memoryText, queryLower, queryTokens)) {
                excerpts.add("Recuerdo guardado: ${memory.title} - ${memory.detail}")
            }
        }

        // D. Gustos en detalle.
        for (story in snapshot.tasteStories) {
            val storyText = ("${story.title} ${story.subtitle} ${story.description} " +
                story.tags.joinToString(" ")).lowercase()
            if (matchesText(storyText, queryLower, queryTokens)) {
                excerpts.add("Gusto en detalle (${story.title}): ${story.subtitle}. Detalle completo: ${story.description}")
                foundEntities.addAll(story.tags)
            }
        }

        // E. Recordatorios.
        for (reminder in snapshot.reminders) {
            val reminderText = "${reminder.title} ${reminder.timeStr}".lowercase()
            if (matchesText(reminderText, queryLower, queryTokens)) {
                val status = if (reminder.isCompleted) "Completado" else "Pendiente"
                excerpts.add("Recordatorio ($status): '${reminder.title}' para las ${reminder.timeStr}")
            }
        }

        // F. Fragmentos de conversaciones pasadas, con puntuación y sesgo temporal.
        val scoredFragments = snapshot.fragments.mapIndexed { index, fragment ->
            val fragmentText = ("${fragment.primaryTag} ${fragment.compactSummary} " +
                "${fragment.keyTopics.joinToString(" ")} ${fragment.namedEntities.joinToString(" ")}").lowercase()
            var score = 0
            for (token in queryTokens) {
                if (fragmentText.contains(token)) score += 2
            }
            if (queryLower.length >= 4 && fragmentText.contains(queryLower)) score += 4
            if (isRecentTemporal && index == 0) score += 3
            if (isRecentTemporal && index == 1) score += 1
            fragment to score
        }.filter { it.second > 0 }.sortedByDescending { it.second }

        for ((fragment, _) in scoredFragments.take(MAX_FRAGMENTS)) {
            excerpts.add("Charla pasada (${fragment.primaryTag}): ${fragment.compactSummary}")
            foundEntities.addAll(fragment.namedEntities)
            foundEntities.addAll(fragment.keyTopics)
        }

        // G. Charlas pasadas consolidadas.
        for (conversation in snapshot.conversations) {
            val conversationText = "${conversation.title} ${conversation.summary} ${conversation.tag}".lowercase()
            if (matchesText(conversationText, queryLower, queryTokens) &&
                excerpts.none { it.contains(conversation.title, ignoreCase = true) }
            ) {
                excerpts.add("Registro de charla: ${conversation.title} - ${conversation.summary}")
            }
        }

        // H. Gustos e intereses simples.
        val matchedTastes = snapshot.tastes.filter { taste ->
            val tasteLower = taste.lowercase()
            queryTokens.any { tasteLower.contains(it) } || queryLower.contains(tasteLower)
        }
        if (matchedTastes.isNotEmpty()) {
            excerpts.add("Gustos registrados del usuario: ${matchedTastes.joinToString(", ")}")
        }

        if (excerpts.isEmpty()) {
            return DeepContextResult(
                relevantExcerpts = emptyList(),
                foundEntities = emptyList(),
                synthesizedContext = "Revisé la memoria y conversaciones pasadas, pero no encontré registros sobre '$query'.",
                conversationsSearched = snapshot.fragments.size + snapshot.memories.size
            )
        }

        val synthesized = buildString {
            append("En la memoria y registros pasados del usuario: ")
            excerpts.forEach { append("$it. ") }
            if (foundEntities.isNotEmpty()) {
                append("Entidades y lugares relacionados: ${foundEntities.take(MAX_ENTITIES).joinToString(", ")}.")
            }
        }.trim()

        return DeepContextResult(
            relevantExcerpts = excerpts,
            foundEntities = foundEntities.toList(),
            synthesizedContext = synthesized,
            conversationsSearched = snapshot.fragments.size + snapshot.memories.size
        )
    }

    /**
     * Coincidencia usada por recuerdos, historias y recordatorios: cualquier
     * token presente, o la consulta literal cuando tiene al menos 4 caracteres.
     */
    private fun matchesText(text: String, queryLower: String, queryTokens: List<String>): Boolean =
        queryTokens.any { text.contains(it) } ||
            (queryLower.length >= 4 && text.contains(queryLower))
}
