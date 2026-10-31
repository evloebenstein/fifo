package com.fifo.voicepipeline.skills

import com.fifo.voicepipeline.data.context.InMemoryDeepContextPort
import com.fifo.voicepipeline.data.model.DeepContextResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrato del skill `recall_past_context` (ADR-0049).
 *
 * El skill devuelve **payload**, no una frase: `spokenFeedback` siempre queda
 * vacío y el LLM redacta. `no_evidence` es un hecho para que el modelo lo diga
 * con honestidad, no una locución enlatada.
 */
class ContextRecallSkillTest {

    @Test
    fun `found context returns payload without a canned sentence`() = runTest {
        val skill = ContextRecallSkill { query ->
            DeepContextResult(
                relevantExcerpts = listOf("Carmen habló de sus orquídeas.", "Tomás la visitó."),
                foundEntities = listOf("Carmen", "Tomás"),
                synthesizedContext = "Charla sobre jardinería y familia.",
                conversationsSearched = 4
            )
        }

        val result = skill.execute(mapOf("query" to "orquídeas", "context_hint" to "planta"))

        assertTrue(result.success)
        assertEquals("", result.spokenFeedback)
        assertEquals("found", result.data["context_status"])
        assertEquals("orquídeas", result.data["query"])
        assertEquals("Charla sobre jardinería y familia.", result.data["synthesized_context"])
        assertTrue(result.data["excerpts"].toString().contains("orquídeas"))
        assertEquals("Carmen, Tomás", result.data["found_entities"])
        assertEquals("4", result.data["conversations_searched"])
    }

    @Test
    fun `missing evidence is reported as a fact, not spoken`() = runTest {
        val skill = ContextRecallSkill {
            DeepContextResult(
                relevantExcerpts = emptyList(),
                foundEntities = emptyList(),
                synthesizedContext = "",
                conversationsSearched = 2
            )
        }

        val result = skill.execute(mapOf("query" to "telescopio"))

        assertTrue(result.success)
        assertEquals("", result.spokenFeedback)
        assertEquals("no_evidence", result.data["context_status"])
        assertFalse(result.data.containsKey("synthesized_context"))
    }

    @Test
    fun `blank query is rejected without searching`() = runTest {
        val deep = InMemoryDeepContextPort()
        val skill = ContextRecallSkill { query -> deep.search("usr_carmen", query)!! }

        val result = skill.execute(mapOf("query" to "   "))

        assertFalse(result.success)
        assertEquals("", result.spokenFeedback)
        assertEquals("missing_query", result.data["context_status"])
        assertTrue(deep.calls.isEmpty())
    }
}
