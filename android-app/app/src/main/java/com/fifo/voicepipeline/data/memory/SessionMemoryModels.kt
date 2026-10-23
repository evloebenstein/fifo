package com.fifo.voicepipeline.data.memory

/**
 * Entidad de persona mencionada en una conversación para el Grafo Relacional.
 */
data class MentionedPersonExtraction(
    val name: String,
    val inferredRole: String? = null,
    val contextSnippet: String = "",
    val emotionalValence: String = "afectuoso",
    val closenessDelta: Float = 0.05f
)

/**
 * Modelo de consolidación de sesión de conversación para extracción y guardado en SQLite.
 */
data class SessionMemoryConsolidation(
    val primaryTag: String = "General",
    val keyTopics: List<String> = emptyList(),
    val namedEntities: List<String> = emptyList(),
    val detectedMood: String = "tranquilo",
    val compactSummary: String = "",
    val conversationTitle: String = "Charla con Fifo",
    val memoryTitle: String? = null,
    val memoryDetail: String? = null,
    val memoryEmoji: String? = null,
    val newTaste: String? = null,
    val mentionedPersons: List<MentionedPersonExtraction> = emptyList()
)
