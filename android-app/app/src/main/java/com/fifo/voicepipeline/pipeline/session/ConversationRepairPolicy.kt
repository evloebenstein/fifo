package com.fifo.voicepipeline.pipeline.session

import com.fifo.voicepipeline.data.memory.JournalEntry
import java.text.Normalizer
import java.util.Locale

/**
 * Política pura de reparación conversacional.
 *
 * Una reparación usa la ventana de ±1 turno: el turno anterior (el que pudo
 * contener el error) y el turno actual (la corrección). Solo se habla la
 * corrección si el turno original sigue siendo reciente (~10 s); después de
 * esa ventana se guarda en memoria y en OLED, pero no se interrumpe al usuario
 * con una corrección tardía.
 */
object ConversationRepairPolicy {
    const val SPEAK_CORRECTION_WINDOW_MS = 10_000L

    private val explicitCorrectionMarkers = listOf(
        "te equivocaste",
        "se equivoco",
        "estas equivocado",
        "esta equivocada",
        "eso esta mal",
        "eso no es cierto",
        "no es cierto",
        "no era asi",
        "no es asi",
        "no era",
        "corrige",
        "corrigeme",
        "corrigelo",
        "me confundiste",
        "me diste mal",
        "esa respuesta esta mal",
        "esa informacion esta mal"
    )

    /**
     * Detecta una corrección explícita. Un "no" aislado solo cuenta como
     * corrección si el turno anterior quedó marcado como erróneo; así no se
     * confunde con la respuesta natural "No" a una pregunta de Fifo.
     */
    fun isCorrection(
        userText: String,
        previousTurn: JournalEntry?
    ): Boolean {
        val text = normalize(userText)
        if (text.isBlank() || previousTurn == null) return false

        if (explicitCorrectionMarkers.any { text.contains(it) }) return true

        if (previousTurn.needsRepair) {
            if (text == "no") return true
            if (text.startsWith("no ") && !text.startsWith("no gracias")) return true
        }
        return false
    }

    /**
     * Una corrección tardía no debe hablar por sorpresa. Se conserva en el
     * diario y se muestra en OLED, pero no se locuta.
     */
    fun canSpeakCorrection(previousTurn: JournalEntry, now: Long): Boolean {
        val elapsed = now - previousTurn.createdAt
        return elapsed in 0..SPEAK_CORRECTION_WINDOW_MS
    }

    /**
     * Respuestas que representan un error operativo del agente, no una
     * negativa legítima del dominio (p. ej. "no hay recordatorios").
     */
    fun isAgentError(assistantText: String): Boolean {
        val text = normalize(assistantText)
        if (text.isBlank()) return false
        val errorStarts = listOf(
            "disculpe",
            "disculpa",
            "no pude",
            "no pude entender",
            "no se encuentra configurada",
            "no tengo informacion",
            "la respuesta tardo demasiado"
        )
        if (errorStarts.any { text.startsWith(it) }) return true
        return text.contains("error de conexion") ||
            text.contains("error tecnico") ||
            text.contains("no pude procesar") ||
            text.contains("no pude completar")
    }

    private fun normalize(text: String): String {
        return Normalizer.normalize(text.lowercase(Locale.ROOT).trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
