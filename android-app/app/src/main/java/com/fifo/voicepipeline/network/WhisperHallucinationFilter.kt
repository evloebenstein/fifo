package com.fifo.voicepipeline.network

import java.text.Normalizer

/**
 * Filtro de alucinaciones típicas de Whisper ante silencio, ruido o audio de video.
 *
 * Es una pieza pura y JVM-testeable: no conoce HTTP ni el pipeline de voz.
 */
internal object WhisperHallucinationFilter {

    fun isHallucination(rawText: String, allowShortResponses: Boolean = false): Boolean {
        val norm = Normalizer.normalize(rawText.lowercase().trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (norm.isBlank() || norm.length <= 1) return true

        // Alucinaciones absolutas: frases que Whisper genera sistemáticamente ante silencio
        // o ruido de video/TV. Siempre se descartan, sin importar el contexto.
        val absoluteHallucinations = setOf(
            "gracias por ver", "gracias por ver el video",
            "gracias por ver este video", "gracias por mirar", "gracias por su atencion",
            "subtitulos realizados por la comunidad de amara org",
            "subtitulos por la comunidad de amara org", "amara org", "amara",
            "suscribete", "suscribete al canal", "suscribanse", "dale like y suscribete",
            "dale like", "compartir", "comenta",
            "thank you for watching", "thanks for watching", "thank you",
            "conex", "conexion", "conexion con el robot fifo", "conectro",
            "filin gud", "filin", "you", "bye", "bye bye",
            "musica", "aplausos", "continuara",
            "conversacion de bunos", "conversacion", "oh", "ah", "eh"
        )
        if (absoluteHallucinations.contains(norm)) return true

        // Palabras breves ambiguas: pueden ser respuestas reales ("sí", "no", "gracias")
        // o alucinaciones ante silencio. Solo se descartan si Fifo NO espera una respuesta.
        if (!allowShortResponses) {
            val ambiguousShortWords = setOf(
                "si", "no", "ok", "okay", "gracias", "muchas gracias",
                "bueno", "dale", "claro", "perfecto"
            )
            if (ambiguousShortWords.contains(norm)) return true
        }

        return false
    }
}
