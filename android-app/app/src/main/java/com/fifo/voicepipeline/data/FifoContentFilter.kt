package com.fifo.voicepipeline.data

import java.text.Normalizer
import java.util.Locale

/**
 * Filtro inteligente de moderación y seguridad de contenidos para Fifo.
 *
 * Detecta y previene:
 * 1. Groserías, insultos, lenguaje de odio o discriminatorio (Español, regionalismos LATAM e Inglés).
 * 2. Connotaciones sexuales explícitas, violencia, drogas o actividades ilícitas.
 * 3. Suplantación de identidad y nombres de sistema reservados (admin, soporte, fifo, policía, etc.).
 * 4. Payloads maliciosos e inyecciones de código (HTML/JS/SQL).
 * 5. Variantes con 'leetspeak' u ofuscaciones (e.g. m1erd4, p0rn, put4, @dm1n).
 */
object FifoContentFilter {

    enum class ModerationType {
        NONE,
        OFFENSIVE,
        RESERVED,
        SUSPICIOUS,
        MALICIOUS_EMAIL
    }

    data class ModerationResult(
        val isValid: Boolean,
        val message: String? = null,
        val flaggedTerm: String? = null,
        val type: ModerationType = ModerationType.NONE
    ) {
        companion object {
            val VALID = ModerationResult(isValid = true)
        }
    }

    // ─── Nombres y palabras reservadas de sistema o soporte ───
    private val RESERVED_WORDS = setOf(
        "fifo", "fifobot", "robotfifo", "fiforobot",
        "admin", "administrator", "administrador", "administradora",
        "soporte", "support", "ayuda", "helpdesk",
        "moderador", "moderadora", "moderator", "mod",
        "staff", "root", "superuser", "sudo",
        "oficial", "official", "seguridad", "security",
        "policia", "police", "carabineros", "fbi", "interpol",
        "sistema", "system", "contacto", "gobierno", "government"
    )

    // ─── Palabras ofensivas, groserías, odio, violencia, sexual ───
    private val FORBIDDEN_WORDS = setOf(
        // Insultos y groserías (Español general y LATAM)
        "puta", "puto", "putas", "putos", "puton", "putona", "putita", "putito",
        "mierda", "mierdas", "mierdon",
        "pendejo", "pendeja", "pendejos", "pendejas",
        "culiao", "culia", "culiaos", "culias", "culiado", "culiada", "conchetumadre", "conchesumadre", "ctm",
        "chucha", "chupala", "chupalo", "chupamela", "chupame", "mamala", "mamalo",
        "maricon", "maricona", "marica", "maricor", "maricones",
        "cabron", "cabrona", "cabrones", "cabronas",
        "malparido", "malparida", "malparidos",
        "gonorrea", "carechimba", "chimba", "mamaguevo", "mamahuevo", "guevon", "guevona", "huevon", "huevona",
        "verga", "vergas", "vergota", "pija", "pito", "zorra", "zorras", "prostituta", "prostituto",
        "coño", "joder", "chinga", "chingar", "chingado", "chingada", "chingon",
        "hdp", "hp", "hijoeputa", "hijodeputa", "hijadeputa",
        "imbecil", "estupido", "estupida", "idiota", "tarado", "tarada", "sorete",
        "pajero", "pajera", "pelotudo", "pelotuda", "boludo", "boluda", "concha",
        "nazi", "hitler", "fascista", "violador", "violadora", "violacion",
        "asesino", "asesina", "asesinato", "sicario", "terrorista", "isis", "alqaeda", "taliban",
        "suicidio", "suicida", "matar", "muerte", "tiroteo",
        "narcotrafico", "narco", "cocaina", "heroina", "metanfetamina", "fentanilo",
        "pedofilo", "pedofila", "pedofilia", "zoofilia", "incesto",

        // Sexual explícito
        "porno", "porn", "pornografia", "xxx", "sexo", "sexual",
        "pene", "penes", "vagina", "vaginas", "tetas", "senos", "clitoris", "ano", "anus",
        "dildo", "erotico", "erotica", "hentai", "orgasmo", "masturbar",

        // Insultos y groserías en Inglés
        "fuck", "fucking", "fucker", "fuckoff",
        "shit", "shitty", "bullshit",
        "asshole", "bitch", "bitches", "bastard", "cunt", "dick", "cock",
        "pussy", "faggot", "nigger", "nigga", "slut", "sluts", "whore", "whores",
        "retard", "wanker", "rape", "rapist", "murder", "kill", "pedophile"
    )

    // ─── Nombres legítimos protegidos para evitar falsos positivos ───
    private val LEGITIMATE_NAMES_ALLOWLIST = setOf(
        "penelope", "concepcion", "asensio", "asuncion", "dickson", "nicolas",
        "lucas", "lucia", "luciano", "bernardo", "anibal", "fernando", "casas",
        "paz", "rosa", "blanca", "armando", "romano", "franco", "germain"
    )

    // ─── Patrones de inyección de código y payloads sospechosos ───
    private val SUSPICIOUS_CODE_PATTERNS = listOf(
        Regex("(?i)<script.*?>"),
        Regex("(?i)</script>"),
        Regex("(?i)javascript:"),
        Regex("(?i)onload\\s*="),
        Regex("(?i)onerror\\s*="),
        Regex("(?i)eval\\s*\\("),
        Regex("(?i)alert\\s*\\("),
        Regex("(?i)\\bdrop\\s+table\\b"),
        Regex("(?i)\\bselect\\b.*?\\bfrom\\b"),
        Regex("(?i)\\binsert\\s+into\\b"),
        Regex("(?i)\\bdelete\\s+from\\b"),
        Regex("(?i)--|/\\*|\\*/")
    )

    /**
     * Normaliza un texto removiendo acentos, espacios duplicados y traduciendo leetspeak común.
     */
    fun normalizeText(input: String): String {
        val decomposed = Normalizer.normalize(input.trim(), Normalizer.Form.NFD)
        val withoutAccents = decomposed.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
        return withoutAccents.lowercase(Locale.ROOT)
    }

    /**
     * Traduce sustituciones visuales u ofuscaciones ('leetspeak') a texto estándar.
     * Ejemplo: "p0rn0" -> "porno", "m1erd4" -> "mierda", "put4" -> "puta".
     */
    fun decodeLeetspeak(input: String): String {
        return normalizeText(input)
            .replace("@", "a")
            .replace("4", "a")
            .replace("3", "e")
            .replace("1", "i")
            .replace("!", "i")
            .replace("|", "i")
            .replace("0", "o")
            .replace("5", "s")
            .replace("$", "s")
            .replace("7", "t")
            .replace("8", "b")
            .replace(Regex("(.)\\1{2,}"), "$1$1") // Colapsa repeticiones exageradas: puuuuuta -> puuta
    }

    /**
     * Valida el nombre y apellido del usuario.
     * Asegura un trato amigable, respetuoso y libre de términos maliciosos u ofensivos.
     */
    fun validateFullName(fullName: String): ModerationResult {
        val trimmed = fullName.trim()
        if (trimmed.isBlank()) {
            return ModerationResult(isValid = false, message = "El nombre no puede estar en blanco.")
        }

        // Revisar inyecciones o scripts
        for (pattern in SUSPICIOUS_CODE_PATTERNS) {
            if (pattern.containsMatchIn(trimmed)) {
                return ModerationResult(
                    isValid = false,
                    message = "El nombre contiene caracteres o expresiones no permitidas.",
                    type = ModerationType.SUSPICIOUS
                )
            }
        }

        val normalized = normalizeText(trimmed)
        val leetDecoded = decodeLeetspeak(trimmed)

        // Tokens individuales por palabras
        val rawTokens = trimmed.split(Regex("[\\s._-]+")).filter { it.isNotBlank() }
        val normTokens = normalized.split(Regex("[\\s._-]+")).filter { it.isNotBlank() }
        val leetTokens = leetDecoded.split(Regex("[\\s._-]+")).filter { it.isNotBlank() }

        for (token in normTokens + leetTokens) {
            val cleanToken = token.filter { it.isLetter() }
            if (cleanToken.isBlank()) continue

            // Si está explícitamente en la lista de permitidos, se ignora
            if (LEGITIMATE_NAMES_ALLOWLIST.contains(cleanToken)) continue

            // Revisar nombres reservados
            if (RESERVED_WORDS.contains(cleanToken)) {
                return ModerationResult(
                    isValid = false,
                    message = "El nombre no puede incluir '$token' porque es un término oficial reservado para Fifo.",
                    flaggedTerm = token,
                    type = ModerationType.RESERVED
                )
            }

            // Revisar términos ofensivos
            if (FORBIDDEN_WORDS.contains(cleanToken)) {
                return ModerationResult(
                    isValid = false,
                    message = "Fifo cuida un espacio amigable y seguro. Por favor no uses términos ofensivos o inapropiados.",
                    flaggedTerm = token,
                    type = ModerationType.OFFENSIVE
                )
            }
        }

        // Comprobación de subcadenas críticas de alta gravedad
        val criticalBadWords = listOf("mierda", "puta", "pendejo", "conchetumadre", "nazi", "hitler", "fuck", "nigger")
        for (bad in criticalBadWords) {
            if (normTokens.any { LEGITIMATE_NAMES_ALLOWLIST.contains(it) }) continue
            if (leetDecoded.contains(bad)) {
                return ModerationResult(
                    isValid = false,
                    message = "Fifo cuida un espacio amigable y seguro. Por favor no uses términos ofensivos o inapropiados.",
                    flaggedTerm = bad,
                    type = ModerationType.OFFENSIVE
                )
            }
        }

        return ModerationResult.VALID
    }

    /**
     * Valida el @ID de usuario de Fifo.
     * Rechaza términos reservados de sistema, groserías, ofensas o nombres engañosos.
     */
    fun validateUsername(usernameHandle: String): ModerationResult {
        val clean = usernameHandle.trim().removePrefix("@").lowercase(Locale.ROOT)
        if (clean.isBlank()) {
            return ModerationResult(isValid = false, message = "El @ID no puede estar vacío.")
        }
        if (clean.length < 3) {
            return ModerationResult(isValid = false, message = "El @ID debe tener al menos 3 caracteres.")
        }
        if (clean.length > 20) {
            return ModerationResult(isValid = false, message = "El @ID no puede superar los 20 caracteres.")
        }

        // Solo permitir letras, números, puntos y guiones bajos
        if (!clean.matches(Regex("^[a-z0-9._]+$"))) {
            return ModerationResult(
                isValid = false,
                message = "El @ID solo puede contener letras, números, puntos y guiones bajos.",
                type = ModerationType.SUSPICIOUS
            )
        }

        // Revisar inyecciones
        for (pattern in SUSPICIOUS_CODE_PATTERNS) {
            if (pattern.containsMatchIn(clean)) {
                return ModerationResult(
                    isValid = false,
                    message = "El @ID contiene secuencias o símbolos no permitidos.",
                    type = ModerationType.SUSPICIOUS
                )
            }
        }

        val decoded = decodeLeetspeak(clean)
        val tokens = clean.split(Regex("[._]+")).filter { it.isNotBlank() }
        val decodedTokens = decoded.split(Regex("[._]+")).filter { it.isNotBlank() }

        // Comprobación de nombres de sistema reservados (exacto o compuesto)
        for (reserved in RESERVED_WORDS) {
            if (clean == reserved || decoded == reserved) {
                return ModerationResult(
                    isValid = false,
                    message = "El @ID '@$clean' está reservado para la administración y soporte de Fifo.",
                    flaggedTerm = reserved,
                    type = ModerationType.RESERVED
                )
            }
            if (tokens.contains(reserved) || decodedTokens.contains(reserved)) {
                return ModerationResult(
                    isValid = false,
                    message = "El @ID no puede incluir '$reserved' por razones de seguridad y soporte.",
                    flaggedTerm = reserved,
                    type = ModerationType.RESERVED
                )
            }
        }

        // Comprobación de groserías e insultos (exacto o subcadena)
        for (forbidden in FORBIDDEN_WORDS) {
            if (clean == forbidden || decoded == forbidden || tokens.contains(forbidden) || decodedTokens.contains(forbidden)) {
                return ModerationResult(
                    isValid = false,
                    message = "Este @ID contiene términos no permitidos o inapropiados.",
                    flaggedTerm = forbidden,
                    type = ModerationType.OFFENSIVE
                )
            }
            // Subcadena directa si la palabra prohibida es suficientemente larga (>= 4 letras)
            if (forbidden.length >= 4 && (clean.contains(forbidden) || decoded.contains(forbidden))) {
                // Verificar si no es un nombre legítimo permitido
                val isWhitelisted = LEGITIMATE_NAMES_ALLOWLIST.any { clean.contains(it) }
                if (!isWhitelisted) {
                    return ModerationResult(
                        isValid = false,
                        message = "Este @ID contiene palabras o connotaciones no permitidas.",
                        flaggedTerm = forbidden,
                        type = ModerationType.OFFENSIVE
                    )
                }
            }
        }

        return ModerationResult.VALID
    }

    /**
     * Valida la dirección de correo electrónico del usuario para evitar correos con
     * nombres explícitamente maliciosos o groseros vinculados a Fifo.
     */
    fun validateEmail(email: String): ModerationResult {
        val trimmed = email.trim()
        if (trimmed.isBlank()) {
            return ModerationResult(isValid = false, message = "El correo no puede estar en blanco.")
        }
        if (!trimmed.contains("@") || !trimmed.contains(".")) {
            return ModerationResult(isValid = false, message = "El formato de correo no es válido.")
        }

        val localPart = trimmed.substringBefore("@")
        val domain = trimmed.substringAfter("@").lowercase(Locale.ROOT)

        // Verificar inyecciones
        for (pattern in SUSPICIOUS_CODE_PATTERNS) {
            if (pattern.containsMatchIn(trimmed)) {
                return ModerationResult(
                    isValid = false,
                    message = "El correo contiene caracteres no permitidos.",
                    type = ModerationType.SUSPICIOUS
                )
            }
        }

        // Verificar que el usuario no use un prefijo con insultos o palabras denigrantes
        val decodedLocal = decodeLeetspeak(localPart)
        val tokens = decodedLocal.split(Regex("[._+-]+")).filter { it.isNotBlank() }

        for (forbidden in FORBIDDEN_WORDS) {
            if (tokens.contains(forbidden) || (forbidden.length >= 5 && decodedLocal.contains(forbidden))) {
                return ModerationResult(
                    isValid = false,
                    message = "El correo contiene términos inapropiados.",
                    flaggedTerm = forbidden,
                    type = ModerationType.OFFENSIVE
                )
            }
        }

        // Dominios claramente sospechosos / temporales maliciosos si fuera necesario
        val suspiciousDomains = setOf("mailinator.com", "tempmail.com", "10minutemail.com", "guerrillamail.com")
        if (suspiciousDomains.contains(domain)) {
            return ModerationResult(
                isValid = false,
                message = "Por favor utiliza un correo personal o proveedor confiable (Google, Outlook, etc.).",
                type = ModerationType.MALICIOUS_EMAIL
            )
        }

        return ModerationResult.VALID
    }

    /**
     * Helper booleano rápido para verificar si un término es seguro para ser mostrado o sugerido.
     */
    fun isContentAppropriate(text: String): Boolean {
        val nameRes = validateFullName(text)
        if (!nameRes.isValid) return false
        val userRes = validateUsername(text.removePrefix("@"))
        return userRes.isValid
    }
}
