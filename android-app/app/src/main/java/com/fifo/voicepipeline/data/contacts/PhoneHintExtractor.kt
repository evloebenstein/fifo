package com.fifo.voicepipeline.data.contacts

/**
 * Extrae un indicio telefónico explícito del contexto de una persona mencionada.
 *
 * Es deliberadamente conservador: no inventa números ni completa dígitos. Solo
 * acepta una secuencia de 8 a 15 dígitos cuando:
 *
 * - el contacto todavía no tiene teléfono;
 * - la secuencia parece un número internacional (`+`, `00` o prefijo `56`), o
 *   el contexto contiene una palabra que indica teléfono (`teléfono`, `celular`,
 *   `número`, `WhatsApp`, `llámame`, etc.).
 *
 * Rechaza años, fechas `YYYYMMDD`, números demasiado cortos o largos y
 * secuencias de un solo dígito repetido.
 */
object PhoneHintExtractor {

    private const val MIN_DIGITS = 8
    private const val MAX_DIGITS = 15

    private val CANDIDATE = Regex("""(?<![\d+])(\+?\d[\d\s().-]{5,}\d)(?![\d])""")
    private val CUE = Regex(
        """\b(tel[eé]fono|celular|m[oó]vil|n[uú]mero|fono|whatsapp|ll[aá]ma(?:me|r|do)?|m[aá]rca(?:me|r|do)?)\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Devuelve el teléfono normalizado, o `null` si no hay un indicio válido.
     *
     * @param snippet contexto inmediato de la mención.
     * @param currentPhone teléfono ya conocido; si existe, nunca se reemplaza.
     */
    fun extract(snippet: String, currentPhone: String? = null): String? {
        if (!currentPhone.isNullOrBlank() || snippet.isBlank()) return null

        val hasCue = CUE.containsMatchIn(snippet)
        CANDIDATE.findAll(snippet).forEach { match ->
            val raw = match.groupValues[1]
            val digits = raw.filter(Char::isDigit)
            if (digits.length !in MIN_DIGITS..MAX_DIGITS) return@forEach
            if (digits.all { it == digits.first() }) return@forEach
            if (looksLikeDate(digits)) return@forEach

            val international = raw.startsWith("+") ||
                digits.startsWith("00") ||
                (digits.length >= 11 && digits.startsWith("56"))
            if (!international && !hasCue) return@forEach

            return normalize(raw, digits)
        }
        return null
    }

    private fun normalize(raw: String, digits: String): String = when {
        raw.startsWith("+") -> "+$digits"
        digits.startsWith("00") -> "+${digits.drop(2)}"
        digits.length == 11 && digits.startsWith("56") -> "+$digits"
        digits.length == 9 && digits.startsWith("9") -> "+56$digits"
        else -> digits
    }

    private fun looksLikeDate(digits: String): Boolean {
        if (digits.length != 8) return false
        val year = digits.substring(0, 4).toIntOrNull() ?: return false
        val month = digits.substring(4, 6).toIntOrNull() ?: return false
        val day = digits.substring(6, 8).toIntOrNull() ?: return false
        return year in 1900..2100 && month in 1..12 && day in 1..31
    }
}
