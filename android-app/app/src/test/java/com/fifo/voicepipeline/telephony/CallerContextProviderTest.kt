package com.fifo.voicepipeline.telephony

import com.fifo.voicepipeline.ui.model.ContactIdentityResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallerContextProviderTest {

    private fun snapshot(
        callerName: String?,
        phoneNumber: String? = null,
        phase: CallPhase = CallPhase.RINGING
    ) = CallSnapshot(
        phase = phase,
        callerName = callerName,
        phoneNumber = phoneNumber
    )

    private fun identity(
        isKnown: Boolean,
        name: String = "",
        role: String = "",
        recentTopics: String = "",
        notes: String = ""
    ) = ContactIdentityResult(
        isKnown = isKnown,
        contactName = name,
        relationshipRole = role,
        recentTopics = recentTopics,
        contextualNotes = notes
    )

    private fun provider(
        identitySource: CallerIdentitySource = CallerIdentitySource { _, _, _ -> null },
        resolver: LocalCallerContactResolver = LocalCallerContactResolver { _, _ -> null },
        timeoutMs: Long = 800L
    ) = CallerContextProvider(
        identitySource = identitySource,
        localContactResolver = resolver,
        enrichmentTimeoutMs = timeoutMs
    )

    @Test
    fun `placeholder sin contacto local es desconocido y usa el numero`() {
        val context = provider().local(snapshot("Llamada desconocida", "600111222"))

        assertFalse(context.isKnown)
        assertTrue(context.isUnknown)
        assertEquals("600111222", context.displayName)
        assertEquals("600111222", context.phoneNumber)
        assertEquals(CallerContextSource.UNKNOWN, context.source)
    }

    @Test
    fun `nombre local real es conocido de inmediato`() {
        val context = provider().local(snapshot("Carmen", "600111222"))

        assertTrue(context.isKnown)
        assertEquals("Carmen", context.displayName)
        assertEquals(CallerContextSource.LOCAL_CONTACT, context.source)
    }

    @Test
    fun `numero crudo sin nombre es desconocido, no un contacto`() {
        val context = provider().local(snapshot("+34 600 111 222", ""))

        assertFalse(context.isKnown)
        assertEquals("+34 600 111 222", context.displayName)
        assertEquals("+34 600 111 222", context.phoneNumber)
        assertEquals(CallerContextSource.UNKNOWN, context.source)
    }

    @Test
    fun `resolver local por telefono aporta nombre y parentesco`() {
        val resolver = LocalCallerContactResolver { _, phone ->
            if (phone == "600111222") {
                LocalCallerContact(
                    name = "Carmen",
                    relationshipRole = "Hija",
                    contextualMemory = "Prefiere que le hable despacio."
                )
            } else null
        }

        val context = provider(resolver = resolver)
            .local(snapshot("Llamada desconocida", "600111222"))

        assertTrue(context.isKnown)
        assertEquals("Carmen", context.displayName)
        assertEquals("Hija", context.relationshipRole)
        assertEquals("Prefiere que le hable despacio.", context.contextualNotes)
        assertEquals(CallerContextSource.LOCAL_CONTACT, context.source)
    }

    @Test
    fun `backend conocido enriquece nombre, rol y temas`() = runTest {
        val source = CallerIdentitySource { _, _, _ ->
            identity(
                isKnown = true,
                name = "Carmen",
                role = "Hija",
                recentTopics = "Hablamos de su receta",
                notes = "Vive cerca."
            )
        }
        val provider = provider(identitySource = source)
        val local = provider.local(snapshot("Llamada desconocida", "600111222"))

        val enriched = provider.enrich(snapshot("Llamada desconocida", "600111222"), "user-1", local)

        assertTrue(enriched.isKnown)
        assertEquals("Carmen", enriched.displayName)
        assertEquals("Hija", enriched.relationshipRole)
        assertEquals("Hablamos de su receta", enriched.recentTopics)
        assertEquals("Vive cerca.", enriched.contextualNotes)
        assertEquals(CallerContextSource.BACKEND_KNOWN, enriched.source)
    }

    @Test
    fun `backend desconocido no degrada un contacto local conocido`() = runTest {
        val source = CallerIdentitySource { _, _, _ ->
            identity(isKnown = false, name = "Número 600111222", role = "Contacto telefónico")
        }
        val provider = provider(identitySource = source)
        val local = provider.local(snapshot("Carmen", "600111222"))

        val enriched = provider.enrich(snapshot("Carmen", "600111222"), "user-1", local)

        assertTrue(enriched.isKnown)
        assertEquals("Carmen", enriched.displayName)
        assertEquals(CallerContextSource.LOCAL_CONTACT, enriched.source)
    }

    @Test
    fun `backend desconocido con nombre sigue siendo desconocido`() = runTest {
        val source = CallerIdentitySource { _, _, _ ->
            identity(isKnown = false, name = "Juan", role = "Contacto nuevo catalogado")
        }
        val provider = provider(identitySource = source)
        val local = provider.local(snapshot("Llamada desconocida", "600111222"))

        val enriched = provider.enrich(snapshot("Llamada desconocida", "600111222"), "user-1", local)

        assertFalse(enriched.isKnown)
        assertEquals("Juan", enriched.displayName)
        assertNull(enriched.relationshipRole)
        assertEquals(CallerContextSource.BACKEND_NEW, enriched.source)
    }

    @Test
    fun `timeout del backend devuelve el contexto local sin bloquear`() = runTest {
        val source = CallerIdentitySource { _, _, _ ->
            delay(5_000)
            identity(isKnown = true, name = "Carmen")
        }
        val provider = provider(identitySource = source, timeoutMs = 50)
        val local = provider.local(snapshot("Llamada desconocida", "600111222"))

        val result = provider.enrich(snapshot("Llamada desconocida", "600111222"), "user-1", local)

        assertFalse(result.isKnown)
        assertEquals("600111222", result.displayName)
        assertEquals(CallerContextSource.UNKNOWN, result.source)
    }

    @Test
    fun `error de red devuelve el contexto local`() = runTest {
        val source = CallerIdentitySource { _, _, _ -> error("sin red") }
        val provider = provider(identitySource = source)
        val local = provider.local(snapshot("Carmen", "600111222"))

        val result = provider.enrich(snapshot("Carmen", "600111222"), "user-1", local)

        assertTrue(result.isKnown)
        assertEquals("Carmen", result.displayName)
        assertEquals(CallerContextSource.LOCAL_CONTACT, result.source)
    }

    @Test
    fun `sin llamada no consulta el backend`() = runTest {
        var calls = 0
        val source = CallerIdentitySource { _, _, _ ->
            calls++
            identity(isKnown = true, name = "Carmen")
        }
        val provider = provider(identitySource = source)
        val noCall = snapshot(null, null, CallPhase.NONE)

        val result = provider.enrich(noCall, "user-1", provider.local(noCall))

        assertEquals(0, calls)
        assertEquals(CallPhase.NONE, result.phase)
    }

    @Test
    fun `sin user id no consulta el backend`() = runTest {
        var calls = 0
        val source = CallerIdentitySource { _, _, _ ->
            calls++
            identity(isKnown = true, name = "Carmen")
        }
        val provider = provider(identitySource = source)
        val call = snapshot("Carmen", "600111222")

        provider.enrich(call, "", provider.local(call))

        assertEquals(0, calls)
    }

    @Test
    fun `temas vacios o sin charlas previas no se propagan`() = runTest {
        val source = CallerIdentitySource { _, _, _ ->
            identity(
                isKnown = true,
                name = "Carmen",
                role = "Hija",
                recentTopics = "Sin charlas previas registradas"
            )
        }
        val provider = provider(identitySource = source)
        val call = snapshot("Carmen", "600111222")

        val result = provider.enrich(call, "user-1", provider.local(call))

        assertNull(result.recentTopics)
    }

    @Test
    fun `rol generico no se presenta como parentesco`() {
        val resolver = LocalCallerContactResolver { _, _ ->
            LocalCallerContact("Carmen", relationshipRole = "Contacto telefónico")
        }

        val context = provider(resolver = resolver)
            .local(snapshot("Llamada desconocida", "600111222"))

        assertTrue(context.isKnown)
        assertNull(context.relationshipRole)
    }

    @Test
    fun `anuncio conocido con parentesco y nombre del usuario`() {
        val context = CallerContext(
            phase = CallPhase.RINGING,
            displayName = "Carmen",
            phoneNumber = "600111222",
            relationshipRole = "Hija",
            isKnown = true,
            source = CallerContextSource.LOCAL_CONTACT
        )

        val text = context.announcementText("Rosa")

        assertEquals(
            "¡Rosa! Le está llamando Carmen, su hija. Diga 'Fifo contesta' o 'Fifo cuelga'.",
            text
        )
    }

    @Test
    fun `anuncio de numero desconocido no inventa nombre ni parentesco`() {
        val context = CallerContext(
            phase = CallPhase.RINGING,
            displayName = "600111222",
            phoneNumber = "600111222",
            isKnown = false,
            source = CallerContextSource.UNKNOWN
        )

        val text = context.announcementText()

        assertEquals(
            "Le está llamando un número desconocido (600111222). Diga 'Fifo contesta' o 'Fifo cuelga'.",
            text
        )
    }

    @Test
    fun `anuncio de caller id desconocido lo marca como no registrado`() {
        val context = CallerContext(
            phase = CallPhase.RINGING,
            displayName = "Juan",
            phoneNumber = "600111222",
            isKnown = false,
            source = CallerContextSource.BACKEND_NEW
        )

        val text = context.announcementText()

        assertEquals(
            "Le está llamando Juan (no está en sus contactos). Diga 'Fifo contesta' o 'Fifo cuelga'.",
            text
        )
    }

    @Test
    fun `anuncio no lee temas recientes ni notas`() {
        val context = CallerContext(
            phase = CallPhase.RINGING,
            displayName = "Carmen",
            phoneNumber = "600111222",
            relationshipRole = "Hija",
            isKnown = true,
            source = CallerContextSource.BACKEND_KNOWN,
            contextualNotes = "Toma enalapril antes de comer",
            recentTopics = "Hablamos de la receta de la abuela",
            suggestedGreeting = "Llama Carmen (Hija).",
            suggestedFollowup = "Preguntar por la receta"
        )

        val text = context.announcementText()

        assertFalse(text.contains("enalapril"))
        assertFalse(text.contains("receta"))
        assertFalse(text.contains("abuela"))
        assertTrue(text.contains("Carmen"))
        assertTrue(text.contains("Fifo contesta"))
        assertTrue(text.contains("Fifo cuelga"))
    }

    @Test
    fun `anuncio sin nombre ni numero sigue siendo explicito`() {
        val context = CallerContext(
            phase = CallPhase.RINGING,
            displayName = "un número desconocido",
            isKnown = false,
            source = CallerContextSource.UNKNOWN
        )

        assertEquals(
            "Le está llamando un número desconocido. Diga 'Fifo contesta' o 'Fifo cuelga'.",
            context.announcementText()
        )
    }
}
