package com.fifo.voicepipeline.telephony

import com.fifo.voicepipeline.ui.model.ContactIdentityResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallerContextCoordinatorTest {

    private fun identity(name: String = "Carmen", role: String = "Hija") = ContactIdentityResult(
        isKnown = true,
        contactName = name,
        relationshipRole = role
    )

    @Test
    fun `emite contexto local antes de que responda el backend`() = runTest {
        val gate = CompletableDeferred<ContactIdentityResult?>()
        val source = CallerIdentitySource { _, _, _ -> gate.await() }
        val provider = CallerContextProvider(identitySource = source)
        val telephony = InMemoryTelephonyPort()
        val coordinator = CallerContextCoordinator(
            telephony = telephony,
            provider = provider,
            scope = this,
            userIdProvider = { "user-1" }
        )
        coordinator.start()

        telephony.emit(
            CallSnapshot(
                phase = CallPhase.RINGING,
                callerName = "Llamada desconocida",
                phoneNumber = "600111222"
            )
        )
        runCurrent()

        val immediate = coordinator.context.value
        assertFalse(immediate!!.isKnown)
        assertEquals("600111222", immediate.displayName)

        gate.complete(identity())
        advanceUntilIdle()

        val enriched = coordinator.context.value
        assertTrue(enriched!!.isKnown)
        assertEquals("Carmen", enriched.displayName)
        assertEquals("Hija", enriched.relationshipRole)
        assertEquals(CallerContextSource.BACKEND_KNOWN, enriched.source)

        coordinator.stop()
    }

    @Test
    fun `al terminar la llamada limpia el contexto`() = runTest {
        val source = CallerIdentitySource { _, _, _ -> identity() }
        val provider = CallerContextProvider(identitySource = source)
        val telephony = InMemoryTelephonyPort()
        val coordinator = CallerContextCoordinator(telephony, provider, this) { "user-1" }
        coordinator.start()

        telephony.emit(CallSnapshot(CallPhase.RINGING, "Carmen", "600111222"))
        advanceUntilIdle()
        assertEquals("Carmen", coordinator.context.value?.displayName)

        telephony.emit(CallSnapshot())
        runCurrent()

        assertNull(coordinator.context.value)
        coordinator.stop()
    }

    @Test
    fun `sin user id no consulta el backend y emite local`() = runTest {
        var calls = 0
        val source = CallerIdentitySource { _, _, _ ->
            calls++
            identity()
        }
        val provider = CallerContextProvider(identitySource = source)
        val telephony = InMemoryTelephonyPort()
        val coordinator = CallerContextCoordinator(telephony, provider, this) { "" }
        coordinator.start()

        telephony.emit(CallSnapshot(CallPhase.RINGING, "Carmen", "600111222"))
        advanceUntilIdle()

        assertEquals(0, calls)
        assertEquals("Carmen", coordinator.context.value?.displayName)
        assertEquals(CallerContextSource.LOCAL_CONTACT, coordinator.context.value?.source)
        coordinator.stop()
    }

    @Test
    fun `resultado viejo no pisa una llamada ya terminada`() = runTest {
        val gate = CompletableDeferred<ContactIdentityResult?>()
        val source = CallerIdentitySource { _, _, _ -> gate.await() }
        val provider = CallerContextProvider(identitySource = source)
        val telephony = InMemoryTelephonyPort()
        val coordinator = CallerContextCoordinator(telephony, provider, this) { "user-1" }
        coordinator.start()

        telephony.emit(CallSnapshot(CallPhase.RINGING, "Carmen", "600111222"))
        runCurrent()
        telephony.emit(CallSnapshot())
        runCurrent()

        gate.complete(identity())
        advanceUntilIdle()

        assertNull(coordinator.context.value)
        coordinator.stop()
    }

    @Test
    fun `stop cancela y limpia`() = runTest {
        val source = CallerIdentitySource { _, _, _ -> identity() }
        val provider = CallerContextProvider(identitySource = source)
        val telephony = InMemoryTelephonyPort()
        val coordinator = CallerContextCoordinator(telephony, provider, this) { "user-1" }
        coordinator.start()

        telephony.emit(CallSnapshot(CallPhase.RINGING, "Carmen", "600111222"))
        advanceUntilIdle()
        coordinator.stop()

        assertNull(coordinator.context.value)

        telephony.emit(CallSnapshot(CallPhase.RINGING, "Otra", "600999888"))
        advanceUntilIdle()
        assertNull(coordinator.context.value)
    }
}
