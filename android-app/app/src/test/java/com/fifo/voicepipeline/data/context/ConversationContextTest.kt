package com.fifo.voicepipeline.data.context

import com.fifo.voicepipeline.data.model.ConversationFragment
import com.fifo.voicepipeline.data.model.CurrentLocationInfo
import com.fifo.voicepipeline.data.model.DeepContextResult
import com.fifo.voicepipeline.data.model.UserProfileData
import com.fifo.voicepipeline.data.modules.EpisodicMemoryModule
import com.fifo.voicepipeline.data.modules.InMemoryMemoryStorageAdapter
import com.fifo.voicepipeline.data.modules.InMemoryUserStorageAdapter
import com.fifo.voicepipeline.data.modules.UserIdentityModule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * Tabla de verdad del contexto conversacional (ADR-0049).
 *
 * Cubre:
 * - la ventana de prompt (hora, ubicación, navegación y memoria compacta);
 * - la política de recuperación como guía del sistema, no como locución;
 * - la capa profunda: backend primero y respaldo local;
 * - el estado `no_evidence` cuando no hay nada que recordar.
 */
class ConversationContextTest {

    private lateinit var identity: UserIdentityModule
    private lateinit var memory: EpisodicMemoryModule
    private lateinit var deep: InMemoryDeepContextPort

    @Before
    fun setUp() {
        identity = UserIdentityModule(InMemoryUserStorageAdapter())
        memory = EpisodicMemoryModule(InMemoryMemoryStorageAdapter())
        deep = InMemoryDeepContextPort()
        identity.setUserProfile(
            UserProfileData(
                id = "usr_carmen",
                fullName = "Carmen",
                city = "Santiago",
                preferredAddress = "Av. Los Aromos 123",
                estimatedAge = 72
            )
        )
        memory.loadUserData("usr_carmen")
        memory.addConversationFragment(
            "usr_carmen",
            ConversationFragment(
                id = "frag_1",
                serverConversationId = "conv_1",
                keyTopics = listOf("orquídeas"),
                namedEntities = listOf("Tomás"),
                compactSummary = "Carmen contó cómo cuida sus orquídeas y el cumpleaños de Tomás.",
                primaryTag = "Jardinería"
            )
        )
    }

    private fun build(
        gps: CurrentLocationInfo? = null,
        navigation: String = ""
    ) = ConversationContext(
        identity = identity,
        memory = memory,
        deepContext = deep,
        location = { gps },
        navigation = { navigation },
        clock = {
            Calendar.getInstance().apply {
                set(2026, Calendar.OCTOBER, 30, 9, 5)
            }
        }
    )

    @Test
    fun `context window includes time, profile, compact memory and recovery guide`() {
        val window = build().buildContextWindow()

        assertTrue(window.contains("HORA ACTUAL: 09:05"))
        assertTrue(window.contains("Carmen"))
        assertTrue(window.contains("Av. Los Aromos 123"))
        assertTrue(window.contains("orquídeas"))
        assertTrue(window.contains("recall_past_context"))
        assertTrue(window.contains("Busca antes de afirmar"))
        // La guía es texto de sistema: no debe contener una respuesta enlatada.
        assertFalse(window.contains("No tengo información sobre eso"))
    }

    @Test
    fun `recovery guide lists the five triggers and the no-invention rule`() {
        val window = build().buildContextWindow()

        listOf("1.", "2.", "3.", "4.", "5.").forEach { trigger ->
            assertTrue("Falta el disparador $trigger", window.contains(trigger))
        }
        assertTrue(window.contains("nunca inventes"))
    }

    @Test
    fun `gps location wins over the estimated address`() {
        val window = build(
            gps = CurrentLocationInfo(
                latitude = -33.45,
                longitude = -70.66,
                address = "Providencia",
                city = "Santiago",
                isGpsActive = true
            )
        ).buildContextWindow()

        assertTrue(window.contains("UBICACIÓN GPS ACTUAL: Providencia"))
        assertTrue(window.contains("-33.45, -70.66"))
        assertFalse(window.contains("UBICACIÓN ESTIMADA"))
    }

    @Test
    fun `navigation context is injected when a route is active`() {
        val window = build(navigation = "RUTA ACTIVA: 4 cuadras al norte.").buildContextWindow()

        assertTrue(window.contains("RUTA ACTIVA: 4 cuadras al norte."))
    }

    @Test
    fun `searchDeep prefers the backend response`() = runTest {
        deep.respondWith(
            DeepContextResult(
                relevantExcerpts = listOf("Extracto del servidor sobre orquídeas."),
                foundEntities = listOf("Tomás"),
                synthesizedContext = "Contexto remoto",
                conversationsSearched = 3
            )
        )

        val result = build().searchDeep("orquídeas")

        assertEquals("Contexto remoto", result.synthesizedContext)
        assertEquals(listOf("usr_carmen" to "orquídeas"), deep.calls)
    }

    @Test
    fun `searchDeep falls back to the local index when the backend is unavailable`() = runTest {
        deep.respondWith(null)

        val result = build().searchDeep("orquídeas")

        assertTrue(result.relevantExcerpts.isNotEmpty())
        assertTrue(result.relevantExcerpts.any { it.contains("orquídeas") })
    }

    @Test
    fun `searchDeep falls back to local when the backend has no excerpts`() = runTest {
        deep.respondWith(
            DeepContextResult(
                relevantExcerpts = emptyList(),
                foundEntities = emptyList(),
                synthesizedContext = "",
                conversationsSearched = 0
            )
        )

        val result = build().searchDeep("orquídeas")

        assertTrue(result.relevantExcerpts.any { it.contains("orquídeas") })
    }

    @Test
    fun `searchDeep returns no evidence when neither layer matches`() = runTest {
        deep.respondWith(null)

        val result = build().searchDeep("astronomía telescopio")

        assertTrue(result.relevantExcerpts.isEmpty())
    }
}
