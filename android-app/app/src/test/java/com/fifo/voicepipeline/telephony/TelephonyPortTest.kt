package com.fifo.voicepipeline.telephony

import com.fifo.voicepipeline.ui.model.IncomingCallInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelephonyPortTest {

    @Test
    fun `sin llamada se proyecta a NONE`() {
        val snapshot = (null as IncomingCallInfo?).toCallSnapshot()
        assertEquals(CallPhase.NONE, snapshot.phase)
        assertFalse(snapshot.hasCall)
    }

    @Test
    fun `llamada entrante se proyecta a RINGING con nombre y numero`() {
        val info = IncomingCallInfo(
            callerName = "Hija Carmen",
            phoneNumber = "+34600111222",
            isRinging = true
        )
        val snapshot = info.toCallSnapshot()
        assertEquals(CallPhase.RINGING, snapshot.phase)
        assertTrue(snapshot.isRinging)
        assertEquals("Hija Carmen", snapshot.callerName)
        assertEquals("+34600111222", snapshot.phoneNumber)
    }

    @Test
    fun `llamada descolgada se proyecta a ACTIVE`() {
        val info = IncomingCallInfo(
            callerName = "Dr. Pérez",
            phoneNumber = "+34600999888",
            isRinging = false
        )
        val snapshot = info.toCallSnapshot()
        assertEquals(CallPhase.ACTIVE, snapshot.phase)
        assertTrue(snapshot.hasCall)
        assertFalse(snapshot.isRinging)
    }

    @Test
    fun `answer en memoria pasa de RINGING a ACTIVE`() {
        val port = InMemoryTelephonyPort(
            CallSnapshot(
                phase = CallPhase.RINGING,
                callerName = "Carmen",
                phoneNumber = "600111222"
            )
        )

        assertTrue(port.answer())
        assertEquals(CallPhase.ACTIVE, port.callState.value.phase)
        assertEquals("Carmen", port.callState.value.callerName)
        assertEquals(1, port.answerInvocations)
    }

    @Test
    fun `hangup en memoria limpia la llamada`() {
        val port = InMemoryTelephonyPort(
            CallSnapshot(phase = CallPhase.ACTIVE, callerName = "Carmen")
        )

        assertTrue(port.hangup())
        assertEquals(CallPhase.NONE, port.callState.value.phase)
        assertFalse(port.callState.value.hasCall)
        assertEquals(1, port.hangupInvocations)
    }

    @Test
    fun `answer fallido no cambia la fase`() {
        val port = InMemoryTelephonyPort(
            CallSnapshot(phase = CallPhase.RINGING, callerName = "Carmen")
        )
        port.answerResult = false

        assertFalse(port.answer())
        assertEquals(CallPhase.RINGING, port.callState.value.phase)
    }

    @Test
    fun `start y stop son idempotentes`() {
        val port = InMemoryTelephonyPort()
        port.start()
        port.start()
        assertTrue(port.isStarted)
        port.stop()
        assertFalse(port.isStarted)
    }
}
