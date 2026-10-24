package com.fifo.voicepipeline.data.modules

import com.fifo.voicepipeline.ui.model.ConversationFragment
import com.fifo.voicepipeline.ui.model.DailyRoutine
import com.fifo.voicepipeline.ui.model.FifoMemoryItem
import com.fifo.voicepipeline.ui.model.FifoReminderItem
import com.fifo.voicepipeline.ui.model.FifoTasteStory
import com.fifo.voicepipeline.ui.model.PastConversationItem
import com.fifo.voicepipeline.ui.model.RelationalContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodicContextSearchTest {

    private fun memory(title: String, detail: String) = FifoMemoryItem(
        id = "mem_$title",
        emoji = "⭐",
        title = title,
        detail = detail,
        learnedDate = "Aprendido recientemente"
    )

    private fun fragment(
        id: String,
        summary: String,
        tag: String = "Jardinería",
        topics: List<String> = emptyList(),
        entities: List<String> = emptyList(),
        recordedAt: String = "2026-10-20"
    ) = ConversationFragment(
        id = id,
        serverConversationId = "srv_$id",
        keyTopics = topics,
        namedEntities = entities,
        compactSummary = summary,
        primaryTag = tag,
        recordedAt = recordedAt
    )

    private fun reminder(title: String, time: String, completed: Boolean = false) = FifoReminderItem(
        id = "rem_$title",
        title = title,
        timeStr = time,
        isCompleted = completed
    )

    @Test
    fun `encuentra contacto por rol y alias y agrega entidades`() {
        val contact = RelationalContact(
            id = "ctc_patricia",
            contactName = "Patricia",
            relationshipRole = "Hija",
            closenessScore = 0.95f,
            contextualMemory = "Vive en Providencia"
        )
        val snapshot = EpisodicContextSnapshot(relationalContacts = listOf(contact))

        val byRole = EpisodicContextSearch.search("hija", snapshot)
        assertTrue(byRole.relevantExcerpts.any { it.startsWith("Contacto cercano: Patricia") })
        assertTrue(byRole.relevantExcerpts.any { it.contains("cercanía 95%") })
        assertEquals(listOf("Patricia", "Hija"), byRole.foundEntities)

        val byAlias = EpisodicContextSearch.search(
            "mami",
            snapshot.copy(
                relationalContacts = listOf(contact.copy(alias = "Mami"))
            )
        )
        assertTrue(byAlias.relevantExcerpts.any { it.startsWith("Contacto cercano: Patricia") })
    }

    @Test
    fun `encuentra una rutina diaria por su nombre`() {
        val snapshot = EpisodicContextSnapshot(
            dailyRoutines = listOf(
                DailyRoutine(
                    id = "routine_caminata",
                    routineName = "Caminata matinal",
                    typicalTimeStr = "08:00",
                    notes = "Va al parque con su vecina"
                )
            )
        )

        val result = EpisodicContextSearch.search("caminata", snapshot)

        assertTrue(result.relevantExcerpts.any {
            it.contains("Patrón de rutina diaria: Caminata matinal a las 08:00 (morning)")
        })
        assertTrue(result.foundEntities.contains("Caminata matinal"))
    }

    @Test
    fun `encuentra recuerdos por token y por consulta literal`() {
        val snapshot = EpisodicContextSnapshot(
            memories = listOf(
                memory("Premio de Orquídeas 2018", "Ganó el primer lugar en la exposición floral.")
            )
        )

        val byToken = EpisodicContextSearch.search("orquídeas floral", snapshot)
        val byLiteral = EpisodicContextSearch.search("premio de orquídeas", snapshot)

        assertTrue(byToken.relevantExcerpts.any { it.startsWith("Recuerdo guardado: Premio de Orquídeas 2018") })
        assertTrue(byLiteral.relevantExcerpts.any { it.startsWith("Recuerdo guardado: Premio de Orquídeas 2018") })
    }

    @Test
    fun `encuentra historia de gusto y agrega sus tags a las entidades`() {
        val snapshot = EpisodicContextSnapshot(
            tasteStories = listOf(
                FifoTasteStory(
                    id = "story_boleros",
                    title = "Pasión por los boleros",
                    subtitle = "Música de su juventud",
                    description = "Su padre ponía discos de vinilo los domingos.",
                    tags = listOf("Música", "Boleros", "Lucho Gatica"),
                    iconCategory = "music",
                    learnedFrom = "charla del 12 de octubre"
                )
            )
        )

        val result = EpisodicContextSearch.search("vinilo", snapshot)

        assertTrue(result.relevantExcerpts.any { it.startsWith("Gusto en detalle (Pasión por los boleros)") })
        assertTrue(result.foundEntities.containsAll(listOf("Música", "Boleros", "Lucho Gatica")))
    }

    @Test
    fun `distingue recordatorios pendientes y completados`() {
        val snapshot = EpisodicContextSnapshot(
            reminders = listOf(
                reminder("Tomar Losartán", "09:00", completed = false),
                reminder("Tomar vitamina", "13:00", completed = true)
            )
        )

        val result = EpisodicContextSearch.search("tomar", snapshot)

        assertTrue(result.relevantExcerpts.any { it.contains("Recordatorio (Pendiente): 'Tomar Losartán' para las 09:00") })
        assertTrue(result.relevantExcerpts.any { it.contains("Recordatorio (Completado): 'Tomar vitamina' para las 13:00") })
    }

    @Test
    fun `puntua fragmentos y devuelve como maximo tres`() {
        val snapshot = EpisodicContextSnapshot(
            fragments = listOf(
                fragment("f1", "Riego de jardín", topics = listOf("riego")),
                fragment("f2", "Jardín y rosas", topics = listOf("rosas")),
                fragment("f3", "Riego de jardín comunitario", topics = listOf("riego")),
                fragment("f4", "Jardín secreto", topics = listOf("secreto"))
            )
        )

        val result = EpisodicContextSearch.search("riego jardín", snapshot)
        val fragmentExcerpts = result.relevantExcerpts.filter { it.startsWith("Charla pasada") }

        assertEquals(3, fragmentExcerpts.size)
        assertTrue(fragmentExcerpts[0].contains("Riego de jardín"))
        assertTrue(fragmentExcerpts[1].contains("Riego de jardín comunitario"))
        assertTrue(fragmentExcerpts[2].contains("Jardín y rosas"))
    }

    @Test
    fun `el sesgo temporal prioriza los dos fragmentos mas recientes`() {
        val snapshot = EpisodicContextSnapshot(
            fragments = listOf(
                fragment("f1", "Primera charla", tag = "Hoy"),
                fragment("f2", "Segunda charla", tag = "Ayer"),
                fragment("f3", "Tercera charla", tag = "Antes")
            )
        )

        val result = EpisodicContextSearch.search("la semana pasada", snapshot)
        val fragmentExcerpts = result.relevantExcerpts.filter { it.startsWith("Charla pasada") }

        assertEquals(2, fragmentExcerpts.size)
        assertTrue(fragmentExcerpts[0].contains("Primera charla"))
        assertTrue(fragmentExcerpts[1].contains("Segunda charla"))
    }

    @Test
    fun `una charla pasada no se duplica si el fragmento ya la menciona`() {
        val snapshot = EpisodicContextSnapshot(
            fragments = listOf(fragment("f1", "Riego de orquídeas y rosas")),
            conversations = listOf(
                PastConversationItem(
                    id = "conv_1",
                    title = "Riego de orquídeas",
                    date = "Ayer",
                    duration = "12 min",
                    summary = "Hablaron sobre el riego semanal.",
                    tag = "Jardinería"
                )
            )
        )

        val result = EpisodicContextSearch.search("orquídeas", snapshot)

        assertTrue(result.relevantExcerpts.any { it.startsWith("Charla pasada") })
        assertFalse(result.relevantExcerpts.any { it.startsWith("Registro de charla") })
    }

    @Test
    fun `sin coincidencias devuelve mensaje y cuenta fragmentos y recuerdos`() {
        val snapshot = EpisodicContextSnapshot(
            fragments = listOf(fragment("f1", "Riego"), fragment("f2", "Rosas")),
            memories = listOf(memory("Uno", "Detalle"), memory("Dos", "Detalle"), memory("Tres", "Detalle"))
        )

        val result = EpisodicContextSearch.search("astronomía telescopio", snapshot)

        assertTrue(result.relevantExcerpts.isEmpty())
        assertTrue(result.foundEntities.isEmpty())
        assertTrue(result.synthesizedContext.contains("no encontré registros sobre 'astronomía telescopio'"))
        assertEquals(5, result.conversationsSearched)
    }

    @Test
    fun `las palabras vacias y los tokens cortos no generan coincidencias`() {
        val snapshot = EpisodicContextSnapshot(
            memories = listOf(memory("La casa del lago", "El sol de la tarde"))
        )

        assertTrue(EpisodicContextSearch.search("el la de", snapshot).relevantExcerpts.isEmpty())
        assertTrue(EpisodicContextSearch.search("mi", snapshot).relevantExcerpts.isEmpty())
    }

    @Test
    fun `el resumen sintetizado limita las entidades a cinco`() {
        val snapshot = EpisodicContextSnapshot(
            relationalContacts = listOf(
                RelationalContact(
                    id = "ctc_zoe",
                    contactName = "Zoe",
                    relationshipRole = "Hija",
                    contextualMemory = "Cuida el jardín"
                )
            ),
            fragments = listOf(
                fragment(
                    id = "f1",
                    summary = "Charla sobre jardín",
                    topics = listOf("jardín", "riego"),
                    entities = listOf("A", "B", "C", "D", "E")
                )
            )
        )

        val result = EpisodicContextSearch.search("jardín", snapshot)

        assertTrue(result.foundEntities.size > 5)
        assertTrue(result.synthesizedContext.contains("Zoe, Hija, A, B, C."))
        assertFalse(result.synthesizedContext.contains(", D."))
    }
}
