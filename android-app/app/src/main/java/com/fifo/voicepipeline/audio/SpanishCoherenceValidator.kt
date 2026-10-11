package com.fifo.voicepipeline.audio

import java.text.Normalizer
import java.util.Locale

/**
 * Validador de coherencia lingüística y detector de transcripciones mal formuladas o ruido ininteligible.
 *
 * En ambientes reales con eco, ruido de fondo o murmullos, los motores STT (como Whisper) pueden generar
 * combinaciones de palabras extrañas, consonantes imposibles en español (ej: "Kyrkyn", "hvantres")
 * o frases sin sentido lógico.
 *
 * En lugar de responder disparates o alucinar, Fifo detecta si la frase carece de coherencia
 * y le repregunta amablemente al usuario qué quería decir, manteniendo el micrófono activo.
 */
object SpanishCoherenceValidator {

    data class ValidationResult(
        val isCoherent: Boolean,
        val score: Float,
        val reason: String,
        val clarificationMessage: String
    )

    private val CLARIFICATION_RESPONSES = listOf(
        "Disculpe, no le alcancé a entender con claridad. ¿Podría repetirme qué necesitaba?",
        "No le escuché bien esa frase, se oyó un poco entrecortada. ¿Me la podría decir de nuevo, por favor?",
        "Disculpe, no alcancé a comprender bien la pregunta. ¿Qué me quería consultar?",
        "Perdón, no alcancé a captar bien sus palabras. ¿Me podría repetir, por favor?",
        "Disculpe, se escuchó un poco confuso. ¿Podría decirme de nuevo qué buscaba?"
    )

    // Palabras breves válidas de comando, respuesta o saludo que por sí solas son 100% coherentes
    private val SHORT_VALID_COMMANDS = setOf(
        "si", "no", "ya", "ok", "bien", "mal", "ayuda", "gracias", "para", "callate", "silencio",
        "hola", "fifo", "adios", "chao", "que", "como", "donde", "cuando", "quien", "cuanto",
        "cual", "cuales", "vamos", "guiame", "ubicame", "hora", "clima", "tiempo", "vale", "listo",
        "porfa", "oye", "dime", "escuchas", "estas", "aqui", "aca"
    )

    // Secuencias de caracteres y clusters consonánticos que NO existen en el español (ruido fonético de Whisper)
    private val ILLEGAL_PATTERNS = listOf(
        Regex("(?i)kyrk"), Regex("(?i)hvan"), Regex("(?i)rkt"), Regex("(?i)tzs"),
        Regex("(?i)xst"), Regex("(?i)bkm"), Regex("(?i)pft"), Regex("(?i)qwr"),
        Regex("(?i)lkj"), Regex("(?i)mnb"), Regex("(?i)dsf"), Regex("(?i)wxy"),
        Regex("(?i)zxc"), Regex("(?i)ghj"), Regex("(?i)jkl"), Regex("(?i)xcv"),
        Regex("(?i)vbn"), Regex("(?i)bnm"), Regex("(?i)\\b(hv|ky|zr|mr|pk|tk|gd|bd|zb|zk|xk|wk)")
    )

    // Diccionario esencial del español (artículos, pronombres, verbos comunes, preposiciones,
    // interrogativos, términos urbanos y cotidianos) para validar coherencia léxica
    private val SPANISH_CORE_LEXICON: Set<String> by lazy {
        val words = setOf(
            // Artículos y determinantes
            "el", "la", "los", "las", "un", "una", "unos", "unas", "lo", "al", "del",
            // Pronombres
            "yo", "tu", "vos", "usted", "el", "ella", "ello", "nosotros", "nosotras", "ustedes",
            "ellos", "ellas", "me", "te", "se", "nos", "le", "les", "mi", "mis", "tu", "tus",
            "su", "sus", "mio", "mia", "tuyo", "tuya", "suyo", "suya", "nuestro", "nuestra",
            // Demostrativos
            "este", "esta", "estos", "estas", "esto", "ese", "esa", "esos", "esas", "eso",
            "aquel", "aquella", "aquellos", "aquellas", "aquello",
            // Interrogativos / Exclamativos
            "que", "cual", "cuales", "quien", "quienes", "como", "donde", "adonde", "cuando",
            "cuanto", "cuanta", "cuantos", "cuantas", "por",
            // Preposiciones y conjunciones
            "a", "ante", "bajo", "con", "contra", "de", "desde", "durante", "en", "entre",
            "hacia", "hasta", "mediante", "para", "por", "segun", "sin", "sobre", "tras",
            "y", "e", "ni", "o", "u", "pero", "sino", "aunque", "porque", "pues", "ya",
            // Adverbios y partículas
            "si", "no", "tambien", "tampoco", "mas", "menos", "muy", "mucho", "mucha", "muchos",
            "muchas", "poco", "poca", "pocos", "pocas", "tan", "tanto", "cerca", "lejos",
            "aqui", "aca", "ahi", "alli", "alla", "arriba", "abajo", "dentro", "fuera",
            "temprano", "tarde", "pronto", "siempre", "nunca", "jamas", "quizas", "tal", "vez",
            // Verbos fundamentales (formas conjugadas frecuentes)
            "es", "son", "esta", "estan", "estoy", "estamos", "era", "fue", "hay", "haber", "ser",
            "estar", "tener", "tiene", "tienen", "tengo", "tenemos", "tenia", "hacer", "hace",
            "hacen", "hago", "hacemos", "ir", "va", "van", "voy", "vamos", "fui", "fuimos",
            "decir", "dice", "dicen", "digo", "dime", "digame", "ver", "ve", "ven", "veo",
            "mira", "mire", "dar", "da", "dan", "dame", "saber", "sabe", "saben", "se",
            "querer", "quiero", "quiere", "quieren", "queremos", "poder", "puedo", "puede",
            "pueden", "podemos", "podria", "podrias", "llegar", "llega", "llegan", "pasar",
            "pasa", "pasan", "poner", "pon", "deber", "debe", "deben", "quedar", "queda",
            "quedan", "hablar", "habla", "hablan", "hablas", "escuchar", "escucha", "escuchas",
            "escuchan", "escucho", "buscar", "busca", "buscan", "busco", "buscas", "busque",
            "encontrar", "encuentra", "encuentras", "encuentran", "encuentro", "guiar", "guia",
            "guie", "guiame", "guiarme", "caminar", "camina", "caminas", "salir", "sale",
            "entrar", "entra", "seguir", "sigue", "siguen", "ayudar", "ayuda", "ayudame",
            "llamar", "llama", "llamas", "llaman", "llamame", "contestar", "contesta", "colgar",
            "cuelga", "abrir", "abre", "abren", "cerrar", "cierra", "comprar", "compra", "compran",
            "comer", "come", "comen", "dormir", "duerme", "descansar", "descansa", "despertar",
            "despierta", "parar", "para", "callar", "callate", "apagar", "apagate", "entender",
            "entiendo", "entiendes", "comprender", "saber", "ayudar", "necesitar", "necesito",
            "necesitas", "necesita", "revisar", "revisa", "consultar", "consulta", "mostrar", "muestra",
            // Sustantivos comunes, lugares y entorno
            "fifo", "amigo", "amiga", "robot", "celular", "telefono", "tiempo", "hora", "dia",
            "fecha", "noche", "tarde", "manana", "semana", "mes", "ano", "hoy", "ayer", "casa",
            "hogar", "calle", "avenida", "pasaje", "esquina", "comuna", "ciudad", "mapa", "ubicacion",
            "ruta", "camino", "direccion", "distancia", "metros", "kilometros", "cuadras", "pasos",
            "minutos", "lugar", "lugares", "local", "locales", "tienda", "tiendas", "negocio",
            "negocios", "comercio", "comercios", "mercado", "mercados", "supermercado", "supermercados",
            "super", "minimarket", "minimarkets", "almacen", "almacenes", "feria", "ferias",
            "farmacia", "farmacias", "panaderia", "panaderias", "botilleria", "botillerias",
            "cafeteria", "cafeterias", "restaurante", "restaurantes", "comida", "almuerzo", "cena",
            "desayuno", "once", "pan", "leche", "agua", "bebida", "cafe", "te", "doctor",
            "doctora", "medico", "hospital", "hospitales", "clinica", "clinicas", "cesfam",
            "consultorio", "posta", "urgencia", "banco", "bancos", "parque", "parques", "plaza",
            "plazas", "contacto", "contactos", "llamada", "llamadas", "mensaje", "persona", "personas",
            "musica", "noticia", "noticias", "clima", "temperatura", "lluvia", "sol", "frio", "calor",
            // Nombres propios y marcas locales de Chile
            "vitacura", "santiago", "condes", "providencia", "chile", "oxxo", "jumbo", "lider",
            "unimarc", "santa", "isabel", "ahumada", "cruz", "verde", "salcobrand", "pasteur",
            "manquehue", "apoquindo", "vespucio", "kennedy", "bello"
        )
        words
    }

    /**
     * Normaliza un texto removiendo acentos, signos de puntuación y espacios extras.
     */
    fun normalize(text: String): String {
        val noAccents = Normalizer.normalize(text.lowercase(Locale("es", "CL")).trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return noAccents
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Evalúa si una palabra individual tiene estructura fonotáctica admisible en español.
     */
    private fun isValidSpanishWordStructure(word: String): Boolean {
        if (word.length <= 1) return word in setOf("a", "e", "o", "u", "y")
        // Verificar si contiene patrones fonéticos ilegales
        for (pattern in ILLEGAL_PATTERNS) {
            if (pattern.containsMatchIn(word)) return false
        }
        // Debe contener al menos una vocal (a, e, i, o, u, y)
        val vowelsCount = word.count { it in "aeiouy" }
        if (vowelsCount == 0) return false
        // No puede tener 4 o más consonantes consecutivas
        if (Regex("[bcdfghjklmnpqrstvwxz]{4,}").containsMatchIn(word)) return false
        return true
    }

    /**
     * Valida la coherencia de una frase en español.
     * Retorna [ValidationResult] con el score de validez y mensaje de repregunta si no es coherente.
     */
    fun validate(rawText: String): ValidationResult {
        val cleaned = normalize(rawText)
        if (cleaned.isBlank()) {
            return ValidationResult(
                isCoherent = false,
                score = 0.0f,
                reason = "Texto vacío",
                clarificationMessage = CLARIFICATION_RESPONSES.first()
            )
        }

        val tokens = cleaned.split(" ").filter { it.isNotBlank() }
        if (tokens.isEmpty()) {
            return ValidationResult(
                isCoherent = false,
                score = 0.0f,
                reason = "Sin palabras identificables",
                clarificationMessage = CLARIFICATION_RESPONSES.first()
            )
        }

        // Caso 1: Palabra única
        if (tokens.size == 1) {
            val single = tokens[0]
            if (SHORT_VALID_COMMANDS.contains(single) || SPANISH_CORE_LEXICON.contains(single)) {
                return ValidationResult(isCoherent = true, score = 1.0f, reason = "Comando breve válido", clarificationMessage = "")
            }
            // Si es un número (ej: "cinco") o dígitos
            if (single.all { it.isDigit() }) {
                return ValidationResult(isCoherent = true, score = 1.0f, reason = "Número válido", clarificationMessage = "")
            }
            // Si tiene estructura fonética imposible o es una sola palabra ininteligible
            if (!isValidSpanishWordStructure(single)) {
                return ValidationResult(
                    isCoherent = false,
                    score = 0.0f,
                    reason = "Palabra única con estructura fonotáctica inválida: '$single'",
                    clarificationMessage = CLARIFICATION_RESPONSES.random()
                )
            }
            // Palabra desconocida de 1 o 2 letras
            if (single.length <= 2) {
                return ValidationResult(
                    isCoherent = false,
                    score = 0.2f,
                    reason = "Monosílabo ininteligible: '$single'",
                    clarificationMessage = CLARIFICATION_RESPONSES.random()
                )
            }
            return ValidationResult(isCoherent = true, score = 0.8f, reason = "Palabra única fonéticamente admisible", clarificationMessage = "")
        }

        // Caso 2: Frase de 2 o más palabras
        var recognizedCount = 0
        var severeGibberishCount = 0
        val problematicWords = mutableListOf<String>()

        for (token in tokens) {
            // Ignorar números
            if (token.all { it.isDigit() }) {
                recognizedCount++
                continue
            }

            // ¿Está en el léxico principal?
            if (SPANISH_CORE_LEXICON.contains(token) || SHORT_VALID_COMMANDS.contains(token)) {
                recognizedCount++
                continue
            }

            // ¿Tiene sufijos o terminaciones habituales del español?
            val hasSpanishSuffix = token.endsWith("mente") || token.endsWith("ando") ||
                    token.endsWith("iendo") || token.endsWith("cion") || token.endsWith("sion") ||
                    token.endsWith("ado") || token.endsWith("ido") || token.endsWith("oso") ||
                    token.endsWith("osa") || token.endsWith("ito") || token.endsWith("ita") ||
                    token.endsWith("ero") || token.endsWith("era") || token.endsWith("dor") ||
                    token.endsWith("dora") || token.endsWith("able") || token.endsWith("ible")

            if (hasSpanishSuffix && isValidSpanishWordStructure(token)) {
                recognizedCount++
                continue
            }

            // ¿Falla estructura fonotáctica?
            if (!isValidSpanishWordStructure(token)) {
                severeGibberishCount++
                problematicWords.add(token)
            } else {
                // Palabra no reconocida pero fonéticamente posible (posible nombre propio o término técnico)
                // Se cuenta con medio peso
            }
        }

        val total = tokens.size.toFloat()
        val score = (recognizedCount.toFloat() + (total - recognizedCount - severeGibberishCount) * 0.4f) / total

        // Si contiene palabras con fonotáctica severamente rota (ej: "Kyrkyn", "hvantres")
        if (severeGibberishCount >= 1) {
            return ValidationResult(
                isCoherent = false,
                score = score,
                reason = "Palabras con fonotáctica no española detectadas: $problematicWords",
                clarificationMessage = CLARIFICATION_RESPONSES.random()
            )
        }

        // Umbral de coherencia léxica: al menos 45% de la frase debe estar formulada con vocabulario comprensible
        if (score < 0.45f) {
            return ValidationResult(
                isCoherent = false,
                score = score,
                reason = "Score de coherencia bajo (${String.format(Locale.US, "%.2f", score)} < 0.45)",
                clarificationMessage = CLARIFICATION_RESPONSES.random()
            )
        }

        return ValidationResult(
            isCoherent = true,
            score = score,
            reason = "Frase coherente en español",
            clarificationMessage = ""
        )
    }
}
