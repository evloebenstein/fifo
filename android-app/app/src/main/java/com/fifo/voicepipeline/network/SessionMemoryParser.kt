package com.fifo.voicepipeline.network

import com.fifo.voicepipeline.data.memory.MentionedPersonExtraction
import com.fifo.voicepipeline.data.memory.SessionMemoryConsolidation
import com.google.gson.JsonParser

/**
 * Parser puro de la respuesta JSON del consolidador de memoria.
 *
 * Es la única pieza que conoce el formato del LLM; el transporte HTTP y la
 * decisión de reintentar viven en [GroqSessionMemoryExtractor].
 */
internal object SessionMemoryParser {

    fun parse(jsonResponse: String): SessionMemoryConsolidation {
        val obj = JsonParser.parseString(jsonResponse).asJsonObject

        val primaryTag = obj.get("primaryTag")?.asString ?: "General"

        val keyTopics = mutableListOf<String>()
        obj.getAsJsonArray("keyTopics")?.forEach { elem ->
            if (!elem.isJsonNull) keyTopics.add(elem.asString)
        }

        val namedEntities = mutableListOf<String>()
        obj.getAsJsonArray("namedEntities")?.forEach { elem ->
            if (!elem.isJsonNull) namedEntities.add(elem.asString)
        }

        val detectedMood = obj.get("detectedMood")?.asString ?: "tranquilo"
        val compactSummary = obj.get("compactSummary")?.asString ?: ""
        val conversationTitle = obj.get("conversationTitle")?.asString ?: "Charla con Fifo"
        val memoryTitle = obj.get("memoryTitle").asNullableString()
        val memoryDetail = obj.get("memoryDetail").asNullableString()
        val memoryEmoji = obj.get("memoryEmoji").asNullableString()
        val newTaste = obj.get("newTaste").asNullableString()

        val mentionedPersons = mutableListOf<MentionedPersonExtraction>()
        obj.getAsJsonArray("mentionedPersons")?.forEach { elem ->
            if (elem.isJsonObject) {
                val pObj = elem.asJsonObject
                val name = pObj.get("name")?.run { if (!isJsonNull) asString else "" } ?: ""
                if (name.isNotBlank()) {
                    val inferredRole = pObj.get("inferredRole").asNullableString()
                    val snippet = pObj.get("contextSnippet")?.run { if (!isJsonNull) asString else "" } ?: ""
                    val valence = pObj.get("emotionalValence")?.run { if (!isJsonNull) asString else "afectuoso" } ?: "afectuoso"
                    val delta = pObj.get("closenessDelta")?.run {
                        if (!isJsonNull && isJsonPrimitive && asJsonPrimitive.isNumber) asFloat else 0.05f
                    } ?: 0.05f
                    mentionedPersons.add(
                        MentionedPersonExtraction(
                            name = name.trim(),
                            inferredRole = inferredRole?.trim(),
                            contextSnippet = snippet.trim(),
                            emotionalValence = valence,
                            closenessDelta = delta
                        )
                    )
                }
            }
        }

        return SessionMemoryConsolidation(
            primaryTag = primaryTag,
            keyTopics = keyTopics,
            namedEntities = namedEntities,
            detectedMood = detectedMood,
            compactSummary = compactSummary,
            conversationTitle = conversationTitle,
            memoryTitle = memoryTitle,
            memoryDetail = memoryDetail,
            memoryEmoji = memoryEmoji,
            newTaste = newTaste,
            mentionedPersons = mentionedPersons
        )
    }

    private fun com.google.gson.JsonElement?.asNullableString(): String? =
        this?.run { if (isJsonNull || asString.equals("null", true)) null else asString }
}
