package com.fifo.voicepipeline.pipeline.session

import com.fifo.voicepipeline.data.memory.JournalEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRepairPolicyTest {

    private fun turn(
        assistant: String = "La tienda abre a las ocho.",
        needsRepair: Boolean = false,
        createdAt: Long = 1_000L
    ) = JournalEntry(
        userId = "usr",
        sessionId = "ses",
        userText = "Fifo, ¿a qué hora abre?",
        assistantText = assistant,
        createdAt = createdAt,
        needsRepair = needsRepair
    )

    @Test
    fun testExplicitCorrectionIsDetected() {
        assertTrue(
            ConversationRepairPolicy.isCorrection(
                "Fifo, te equivocaste, abre a las nueve",
                turn()
            )
        )
        assertTrue(
            ConversationRepairPolicy.isCorrection(
                "No, eso está mal, era a las nueve",
                turn()
            )
        )
        assertFalse(
            ConversationRepairPolicy.isCorrection(
                "Fifo, ¿cómo está el clima?",
                turn()
            )
        )
    }

    @Test
    fun testBareNoOnlyRepairsAnErroneousTurn() {
        assertFalse(
            ConversationRepairPolicy.isCorrection("No", turn(needsRepair = false))
        )
        assertTrue(
            ConversationRepairPolicy.isCorrection("No", turn(needsRepair = true))
        )
        assertTrue(
            ConversationRepairPolicy.isCorrection("No, era María", turn(needsRepair = true))
        )
    }

    @Test
    fun testCorrectionOnlySpeaksInsideTenSecondWindow() {
        val previous = turn(createdAt = 1_000L)
        assertTrue(ConversationRepairPolicy.canSpeakCorrection(previous, 1_000L))
        assertTrue(ConversationRepairPolicy.canSpeakCorrection(previous, 11_000L))
        assertFalse(ConversationRepairPolicy.canSpeakCorrection(previous, 11_001L))
    }

    @Test
    fun testAgentErrorsAreMarkedForRepair() {
        assertTrue(ConversationRepairPolicy.isAgentError("Disculpe, no pude procesar la solicitud."))
        assertTrue(ConversationRepairPolicy.isAgentError("La respuesta tardó demasiado tiempo."))
        assertTrue(ConversationRepairPolicy.isAgentError("No tengo información de esta llamada."))
        assertFalse(ConversationRepairPolicy.isAgentError("No hay recordatorios para hoy."))
    }
}
