package com.fifo.voicepipeline.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMemoryParserTest {

    @Test
    fun `parsea consolidacion completa y personas mencionadas`() {
        val json = """
        {
          "primaryTag": "Familia",
          "keyTopics": ["nieto", "almuerzo"],
          "namedEntities": ["Tomás", "Carmen"],
          "detectedMood": "contento",
          "compactSummary": "Tomás vino a almorzar el domingo.",
          "conversationTitle": "Visita de Tomás",
          "memoryTitle": "Almuerzo familiar",
          "memoryDetail": "Tomás visitó a la usuaria el domingo.",
          "memoryEmoji": "🍲",
          "newTaste": "cazuela",
          "mentionedPersons": [
            {
              "name": "Tomás",
              "inferredRole": "Nieto",
              "contextSnippet": "vino a almorzar",
              "emotionalValence": "afectuoso",
              "closenessDelta": 0.2
            }
          ]
        }
        """.trimIndent()

        val result = SessionMemoryParser.parse(json)

        assertEquals("Familia", result.primaryTag)
        assertEquals(listOf("nieto", "almuerzo"), result.keyTopics)
        assertEquals(listOf("Tomás", "Carmen"), result.namedEntities)
        assertEquals("contento", result.detectedMood)
        assertEquals("Visita de Tomás", result.conversationTitle)
        assertEquals("Almuerzo familiar", result.memoryTitle)
        assertEquals("cazuela", result.newTaste)
        assertEquals(1, result.mentionedPersons.size)
        assertEquals("Tomás", result.mentionedPersons.first().name)
        assertEquals("Nieto", result.mentionedPersons.first().inferredRole)
        assertEquals(0.2f, result.mentionedPersons.first().closenessDelta, 0.001f)
    }

    @Test
    fun `interpreta nulls y descarta personas sin nombre`() {
        val json = """
        {
          "primaryTag": "General",
          "keyTopics": [],
          "namedEntities": [],
          "detectedMood": "tranquilo",
          "compactSummary": "Charla breve.",
          "conversationTitle": "Charla con Fifo",
          "memoryTitle": null,
          "memoryDetail": "null",
          "memoryEmoji": null,
          "newTaste": null,
          "mentionedPersons": [
            { "name": "" },
            { "name": "  " },
            { "name": "Rosa", "inferredRole": null }
          ]
        }
        """.trimIndent()

        val result = SessionMemoryParser.parse(json)

        assertNull(result.memoryTitle)
        assertNull(result.memoryDetail)
        assertNull(result.memoryEmoji)
        assertNull(result.newTaste)
        assertEquals(1, result.mentionedPersons.size)
        assertEquals("Rosa", result.mentionedPersons.first().name)
        assertNull(result.mentionedPersons.first().inferredRole)
    }

    @Test
    fun `usa valores por defecto cuando faltan campos opcionales`() {
        val json = """{"compactSummary":"Solo un resumen"}"""

        val result = SessionMemoryParser.parse(json)

        assertEquals("General", result.primaryTag)
        assertTrue(result.keyTopics.isEmpty())
        assertEquals("tranquilo", result.detectedMood)
        assertEquals("Charla con Fifo", result.conversationTitle)
        assertTrue(result.mentionedPersons.isEmpty())
    }
}
