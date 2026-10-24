package com.fifo.voicepipeline.telephony

import com.fifo.voicepipeline.ui.model.IncomingCallInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStateCoordinatorTest {

    private fun ringingCall() = IncomingCallInfo(
        callerName = "Hija Carmen",
        phoneNumber = "+34600111222",
        isRinging = true
    )

    @Test
    fun `una llamada nula limpia el estado sin borrar la ultima activa`() {
        val coordinator = CallStateCoordinator()
        val call = ringingCall()

        coordinator.setIncomingCall(call)
        coordinator.setIncomingCall(null)

        assertNull(coordinator.incomingCall.value)
        assertSame(call, coordinator.lastActiveCall)
    }

    @Test
    fun `answer ejecuta el callback y descolga la llamada`() {
        val coordinator = CallStateCoordinator()
        coordinator.setIncomingCall(ringingCall())
        var invocations = 0
        coordinator.onAnswerCallAction = {
            invocations++
            true
        }

        assertTrue(coordinator.answerCurrentCall())

        assertEquals(1, invocations)
        assertEquals(false, coordinator.incomingCall.value?.isRinging)
        assertEquals("Hija Carmen", coordinator.lastActiveCall?.callerName)
    }

    @Test
    fun `answer sin callback devuelve false y conserva el timbrado`() {
        val coordinator = CallStateCoordinator()
        coordinator.setIncomingCall(ringingCall())

        assertFalse(coordinator.answerCurrentCall())

        assertEquals(true, coordinator.incomingCall.value?.isRinging)
    }

    @Test
    fun `answer fallido no cambia el estado`() {
        val coordinator = CallStateCoordinator()
        coordinator.setIncomingCall(ringingCall())
        coordinator.onAnswerCallAction = { false }

        assertFalse(coordinator.answerCurrentCall())

        assertEquals(true, coordinator.incomingCall.value?.isRinging)
        assertEquals("Hija Carmen", coordinator.lastActiveCall?.callerName)
    }

    @Test
    fun `hangup ejecuta el callback y limpia la llamada`() {
        val coordinator = CallStateCoordinator()
        val call = ringingCall()
        coordinator.setIncomingCall(call)
        var invocations = 0
        coordinator.onHangupCallAction = {
            invocations++
            true
        }

        assertTrue(coordinator.hangupCurrentCall())

        assertEquals(1, invocations)
        assertNull(coordinator.incomingCall.value)
        assertSame(call, coordinator.lastActiveCall)
    }

    @Test
    fun `hangup sin callback limpia la llamada y devuelve false`() {
        val coordinator = CallStateCoordinator()
        coordinator.setIncomingCall(ringingCall())

        assertFalse(coordinator.hangupCurrentCall())

        assertNull(coordinator.incomingCall.value)
        assertEquals("Hija Carmen", coordinator.lastActiveCall?.callerName)
    }

    @Test
    fun `el callback de colgar se puede desenlazar`() {
        val coordinator = CallStateCoordinator()
        coordinator.onHangupCallAction = { true }
        coordinator.onHangupCallAction = null

        assertFalse(coordinator.hangupCurrentCall())
    }
}
