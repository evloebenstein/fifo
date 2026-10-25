package com.fifo.voicepipeline.skills

import com.google.gson.JsonParser
import com.fifo.voicepipeline.location.FifoNavigationManager
import com.fifo.voicepipeline.location.InMemoryLocationAdapter
import com.fifo.voicepipeline.location.NearbyPlaceMatch
import com.fifo.voicepipeline.ui.model.CurrentLocationInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Pruebas unitarias para VoiceIntentRouter y GroqToolCatalog.
 *
 * Valida:
 * 1. Los comandos mecánicos sin ambigüedad (llamadas, linterna, volumen, hora) se resuelven en local.
 * 2. Las consultas conversacionales, de memoria episódica, qué dijo alguien, recetas médicas,
 *    vestimentas o recomendaciones NUNCA son interceptadas localmente (retornan null para Groq LPU).
 * 3. GroqToolCatalog genera esquemas JSON válidos para Groq LPU con soporte GraphRAG.
 */
class VoiceIntentRouterTest {

    private lateinit var router: VoiceIntentRouter
    private lateinit var catalog: GroqToolCatalog
    private lateinit var navigationManager: FifoNavigationManager

    @Before
    fun setUp() {
        navigationManager = FifoNavigationManager(
            locationAdapter = InMemoryLocationAdapter(
                fixedLocation = CurrentLocationInfo(isGpsActive = false)
            ),
            locationManagerProvider = { null }
        )
        router = VoiceIntentRouter(context = null, navigationManager = navigationManager)
        catalog = GroqToolCatalog()
    }

    @Test
    fun testPendingDestinationConfirmationUsesInjectedNavigationManager() = runBlocking {
        navigationManager.setPendingDestination(
            NearbyPlaceMatch(
                name = "Farmacia Los Aromos",
                road = "Av. Providencia",
                houseNumber = "123",
                neighbourhood = "Providencia",
                suburb = "Providencia",
                city = "Santiago",
                latitude = -33.43,
                longitude = -70.62,
                distanceMeters = 180,
                bearing = 90f,
                cardinalDirection = "hacia el oriente",
                walkingMinutes = 2,
                drivingMinutes = 1,
                blocks = 1,
                fullAddressText = "Av. Providencia 123, Providencia",
                relativeDirection = "gira a la derecha"
            )
        )
        val executor: suspend (String, Map<String, Any?>) -> SkillResult = { _, _ ->
            SkillResult(success = true, spokenFeedback = "ok")
        }

        val result = router.routeFastIntent("sí, guíame", executor)

        assertNotNull(result)
        assertTrue(navigationManager.isNavigating)
        assertNull(navigationManager.pendingDestination)
        assertEquals("Farmacia Los Aromos", navigationManager.activeRoute?.name)
        assertTrue(result?.spokenFeedback?.contains("Farmacia Los Aromos") == true)
    }

    @Test
    fun testCancelNavigationUsesInjectedNavigationManager() = runBlocking {
        navigationManager.startNavigation(
            name = "Farmacia Los Aromos",
            latitude = -33.43,
            longitude = -70.62,
            fullAddress = "Av. Providencia 123",
            road = "Av. Providencia"
        )
        val executor: suspend (String, Map<String, Any?>) -> SkillResult = { _, _ ->
            SkillResult(success = true, spokenFeedback = "ok")
        }

        val result = router.routeFastIntent("cancela la ruta", executor)

        assertNotNull(result)
        assertFalse(navigationManager.isNavigating)
        assertEquals(true, result?.data?.get("cancelled_navigation"))
    }

    @Test
    fun testTelephonyAnswerAndHangupExecuteLocally() = runBlocking {
        var executedSkill: String? = null
        var executedArgs: Map<String, Any?>? = null

        val executor: suspend (String, Map<String, Any?>) -> SkillResult = { name, args ->
            executedSkill = name
            executedArgs = args
            SkillResult(success = true, spokenFeedback = "Acción ejecutada")
        }

        // Probar colgar
        val hangupResult = router.routeFastIntent("corta la llamada", executor)
        assertNotNull("Debe resolver colgar llamada en local", hangupResult)
        assertEquals("manage_phone_call", executedSkill)
        assertEquals("hangup", executedArgs?.get("action"))

        // Probar contestar
        val answerResult = router.routeFastIntent("contesta la llamada", executor)
        assertNotNull("Debe resolver contestar llamada en local", answerResult)
        assertEquals("manage_phone_call", executedSkill)
        assertEquals("answer", executedArgs?.get("action"))
    }

    @Test
    fun testHardwareControlsExecuteLocally() = runBlocking {
        var executedSkill: String? = null
        var executedArgs: Map<String, Any?>? = null

        val executor: suspend (String, Map<String, Any?>) -> SkillResult = { name, args ->
            executedSkill = name
            executedArgs = args
            SkillResult(success = true, spokenFeedback = "Hardware ejecutado")
        }

        // Linterna
        val flashResult = router.routeFastIntent("enciende la linterna", executor)
        assertNotNull(flashResult)
        assertEquals("control_device_hardware", executedSkill)
        assertEquals("flashlight", executedArgs?.get("feature"))
        assertEquals("on", executedArgs?.get("state"))

        // Volumen
        val volResult = router.routeFastIntent("baja el volumen", executor)
        assertNotNull(volResult)
        assertEquals("control_device_hardware", executedSkill)
        assertEquals("volume", executedArgs?.get("feature"))
        assertEquals("down", executedArgs?.get("state"))

        // Hora directa
        val timeResult = router.routeFastIntent("¿qué hora es?", executor)
        assertNotNull(timeResult)
        assertEquals("control_device_hardware", executedSkill)
        assertEquals("time", executedArgs?.get("feature"))
    }

    @Test
    fun testSleepAndSilenceCommandsExecuteLocally() = runBlocking {
        val executor: suspend (String, Map<String, Any?>) -> SkillResult = { _, _ ->
            SkillResult(success = true, spokenFeedback = "ok")
        }

        val sleepResult = router.routeFastIntent("Fifo descansa", executor)
        assertNotNull(sleepResult)
        assertTrue(sleepResult?.spokenFeedback?.contains("descansando") == true)
        assertEquals(true, sleepResult?.data?.get("go_to_sleep"))

        val silenceResult = router.routeFastIntent("silencio", executor)
        assertNotNull(silenceResult)
        assertEquals(true, silenceResult?.data?.get("go_to_sleep"))
    }

    @Test
    fun testConversationalMemoryQueriesNeverInterceptedLocally() = runBlocking {
        val executor: suspend (String, Map<String, Any?>) -> SkillResult = { _, _ ->
            fail("Nunca debe ejecutar un skill local ante consultas de memoria o conversacionales")
            SkillResult(success = false, spokenFeedback = "")
        }

        // 1. Restaurante con María el año pasado y estado de apertura
        val q1 = router.routeFastIntent("Fifo, ¿te acuerdas del restaurante al que fui el año pasado con María? ¿Sigue abierto?", executor)
        assertNull("Pregunta sobre restaurante pasado debe ir a Groq", q1)

        // 2. Qué dijo la doctora sobre la receta médica
        val q2 = router.routeFastIntent("¿Te acuerdas qué me dijo la doctora que tenía que hacer con la receta de las pastillas?", executor)
        assertNull("Pregunta sobre indicación de doctora debe ir a Groq", q2)

        // 3. Vestimenta o ropa que llevaba una persona
        val q3 = router.routeFastIntent("¿Te mencioné qué ropa llevaba puesta mi nieta en el paseo?", executor)
        assertNull("Pregunta sobre ropa/vestimenta debe ir a Groq", q3)

        // 4. Nombre olvidado de un lugar o local
        val q4 = router.routeFastIntent("¿Cómo era que se llamaba ese local de pastas al que fuimos?", executor)
        assertNull("Pregunta de nombre olvidado debe ir a Groq", q4)

        // 5. Recomendaciones abiertas
        val q5 = router.routeFastIntent("Recomiéndame qué puedo almorzar hoy algo rico", executor)
        assertNull("Petición de recomendación debe ir a Groq", q5)

        // 6. Dudas sobre conversaciones pasadas
        val q6 = router.routeFastIntent("¿Qué opinas de lo que hablamos ayer de las plantas?", executor)
        assertNull("Opinión sobre charla pasada debe ir a Groq", q6)
    }

    @Test
    fun testGroqToolCatalogProducesValidCompactJson() {
        val jsonStr = catalog.getCompactToolsJson()
        assertNotNull("JSON no debe ser nulo", jsonStr)
        assertTrue("JSON debe tener contenido", jsonStr.isNotBlank())

        val parsed = JsonParser.parseString(jsonStr)
        assertTrue("Debe parsearse como un JsonArray", parsed.isJsonArray)
        val arr = parsed.asJsonArray
        assertTrue("Debe contener al menos 10 herramientas", arr.size() >= 10)

        // Validar presencia de recall_past_context y web_search
        val toolNames = arr.map {
            it.asJsonObject.getAsJsonObject("function").get("name").asString
        }
        assertTrue("Debe contener recall_past_context", toolNames.contains("recall_past_context"))
        assertTrue("Debe contener web_search", toolNames.contains("web_search"))
        assertTrue("Debe contener manage_phone_call", toolNames.contains("manage_phone_call"))
        assertTrue("Debe contener control_device_hardware", toolNames.contains("control_device_hardware"))

        // Validar que recall_past_context tenga descripción rica GraphRAG
        val recallTool = arr.first { it.asJsonObject.getAsJsonObject("function").get("name").asString == "recall_past_context" }
        val desc = recallTool.asJsonObject.getAsJsonObject("function").get("description").asString
        assertTrue("Debe mencionar memoria profunda o grafo", desc.contains("memoria profunda") || desc.contains("grafo"))
    }
}
