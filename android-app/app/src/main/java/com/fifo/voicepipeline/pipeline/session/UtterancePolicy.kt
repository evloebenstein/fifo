package com.fifo.voicepipeline.pipeline.session

import java.text.Normalizer

/**
 * Acción de control de llamada reconocida de forma determinista (sin pasar por el LLM).
 *
 * El control de llamada SIEMPRE exige la palabra de activación: "Fifo contesta",
 * "Fifo cuelga". Un "contesta" aislado no es válido.
 */
enum class CallAction {
    ANSWER,
    HANGUP,
    CALLER_STATUS
}

/**
 * Única política léxica de Fifo (ADR-0042): invocación, extracción de la
 * consulta, saludo, silencio, sueño, eco, pregunta y control de llamada.
 *
 * Es pura: no conoce Android, el estado del pipeline, el TTS ni el repositorio.
 * El estado conversacional entra como parámetro (frase hablada, follow-up
 * abierto), de modo que el camino `SESSION` y el legacy comparten exactamente
 * las mismas reglas y se pueden probar en JVM sin emulador.
 *
 * Esta política solo **clasifica**: no redacta respuestas. La locución la
 * decide el pipeline con el LLM (regla de respuestas dinámicas).
 */
object UtterancePolicy {

    /**
     * Normaliza texto para comparaciones robustas: minúsculas, sin
     * diacríticos, sin puntuación y con espacios colapsados.
     */
    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase().trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Detecta la invocación "Fifo" de forma puramente léxica.
     *
     * Importante: NO devuelve true solo porque haya un follow-up abierto. Quien
     * llama combina esta señal con su ventana de seguimiento para decidir si
     * acepta una respuesta directa. Separarlas permite que "contesta" aislado
     * siga siendo inválido durante un follow-up.
     *
     * Se excluyen aliases riesgosos que producen falsos positivos en
     * conversación normal ("tito", "dijo", "kiko", "vivo", "pico", "hijo",
     * "tipo"): despertar a Fifo por una palabra ajena es peor que pedirle al
     * usuario que repita la invocación.
     */
    fun isWakeWord(text: String): Boolean {
        if (text.isBlank()) return false
        val cleaned = normalize(text)
        if (cleaned.isBlank()) return false
        val noSpaces = cleaned.replace(" ", "")

        // 1. Coincidencia directa o comprimida ("fi fo", "fiiifo", "fifo", "pifo", "fijo").
        if (noSpaces.contains("fifo") || Regex("f+i+f+o+").containsMatchIn(noSpaces) ||
            noSpaces.contains("pifo") || Regex("p+i+f+o+").containsMatchIn(noSpaces) ||
            noSpaces.contains("fijo") || Regex("f+i+j+o+").containsMatchIn(noSpaces)
        ) {
            return true
        }

        // 2. Variantes fonéticas frecuentes de Whisper con ruido o voz suave.
        val wakeWords = setOf(
            "fifo", "fifi", "feefo", "fefo", "fito", "fido", "fifa", "fibo", "fipo",
            "pifo", "phifo", "fijo", "filo", "figo", "fife", "fifu", "cifo"
        )
        val tokens = cleaned.split(" ").filter { it.isNotBlank() }
        if (tokens.any { it in wakeWords }) return true

        // 3. Expresiones de dos palabras tipo "fi fo".
        return cleaned.contains("fi fo") || cleaned.contains("fe fo")
    }

    /**
     * Extrae la consulta eliminando la invocación y los saludos iniciales.
     * Funciona con "Fifo" en cualquier posición, no solo al inicio.
     */
    fun extractQuery(text: String): String {
        val wakeAlias = "(?:fifo|fifi|pifo|feefo|fefo|fito|fido|fifa|fibo|fipo|phifo|fi\\s+fo|fijo|filo|figo|cifo|fifu|fife)"
        val greeting = "(?:hola|oye|hey|che|ok|bueno|dime|saludos)"

        var clean = text
        // "Hola Fifo ..." -> se elimina el saludo junto con la invocación.
        clean = clean.replace(Regex("(?i)\\b$greeting\\s+$wakeAlias\\b[\\s,;.!?-]*"), " ")
        // Cualquier otra aparición de la invocación, consumiendo solo la puntuación
        // inmediatamente adyacente (no signos de interrogación de la consulta real).
        clean = clean.replace(Regex("(?i)[,;.!?¿¡\\s-]*\\b$wakeAlias\\b[,;.!?\\s-]*"), " ")

        return clean.trim()
    }

    /**
     * Verdadero si, tras quitar la invocación, solo queda un saludo (o nada).
     * Permite que la sesión delegue el saludo al LLM sin respuestas canónicas
     * hardcodeadas.
     */
    fun isGreetingOnly(text: String): Boolean {
        val query = normalize(extractQuery(text))
        if (query.isBlank() || query.length < 2) return true
        return query in setOf(
            "hola", "buenas", "buenos dias", "buenas tardes", "buenas noches",
            "saludos", "hey", "oye", "que tal"
        )
    }

    /**
     * Detecta órdenes de silencio / callarse ("Fifo silencio", "Fifo cállate",
     * "Fifo para", "cállate", "silencio", etc.).
     *
     * Si [whileSpeaking] es true (Fifo está hablando o se capturó audio durante
     * su locución), acepta palabras cortas de detención sin exigir "Fifo" para
     * máxima receptividad.
     */
    fun isSilenceCommand(text: String, whileSpeaking: Boolean = false): Boolean {
        if (text.isBlank()) return false
        val normalized = normalize(text)
        if (normalized.isBlank()) return false

        val tokens = normalized.split(" ").filter { it.isNotBlank() }

        // Frases explícitas e inequívocas de detención / silencio.
        val explicitStopPhrases = listOf(
            "silencio", "callate", "basta", "detente", "stop",
            "shh", "shhh", "no hables", "deja de hablar", "para ya",
            "para de hablar", "corta ya", "cortala", "mute",
            "no te estoy hablando", "callate fifo", "silencio fifo",
            "fifo silencio", "fifo callate", "fifo para", "para fifo",
            "fifo basta", "basta fifo", "fifo detente", "detente fifo", "fifo stop",
            "fifo calma", "calma fifo"
        )

        val matchesExplicitPhrase = explicitStopPhrases.any { phrase ->
            normalized == phrase ||
                normalized.contains(Regex("\\b${Regex.escape(phrase)}\\b"))
        }

        if (matchesExplicitPhrase) {
            // Evitar falsos positivos cuando la palabra aparece dentro de una instrucción real:
            // "busca el botón de silencio", "quiero que me guíes para...", etc.
            val isTaskInstruction = normalized.contains("guiarme") ||
                normalized.contains("ubica") ||
                normalized.contains("donde") ||
                normalized.contains("como ") ||
                normalized.contains("quiero") ||
                normalized.contains("busca")
            if (!isTaskInstruction) return true
        }

        // "para" en español casi siempre es preposición. Solo es orden cuando la frase
        // completa es un comando corto e inequívoco.
        val exactStopCommands = setOf(
            "para", "parate", "para ya", "fifo para", "para fifo",
            "para un momento", "espera un momento", "espera", "alto", "un momento"
        )
        if (normalized in exactStopCommands) return true

        // Durante el habla (barge-in), palabras aisladas de detención en frases cortas.
        if (whileSpeaking) {
            val quickBargeInWords = listOf(
                "callate", "silencio", "basta", "stop", "shh", "shhh",
                "detente", "para", "parar", "espera", "corta"
            )
            if (tokens.size <= 4 && tokens.any { it in quickBargeInWords }) return true
        }

        return false
    }

    /**
     * Identifica órdenes explícitas de despedida o descanso.
     *
     * "apágate" es una orden de sueño, no de silencio: el usuario pide que Fifo
     * deje de escuchar, no que se calle un momento.
     */
    fun isSleepCommand(text: String): Boolean {
        if (text.isBlank()) return false
        val normalized = normalize(text)

        // "buenas noches" solo es comando de dormir si va acompañado de una despedida explícita.
        val isNightSleep = (normalized.contains("buenas noches") &&
            (normalized.contains("descansa") || normalized.contains("a dormir") ||
                normalized.contains("hasta manana") || normalized.contains("que descanses"))) ||
            normalized.contains("hasta manana") ||
            normalized.contains("a dormir") ||
            normalized.contains("a descansar") ||
            normalized.contains("me voy a dormir")

        val isDirectSleep = normalized.contains("duermete") ||
            normalized.contains("descansa") ||
            normalized.contains("adios") ||
            normalized.contains("hasta luego") ||
            normalized.contains("deja de escuchar") ||
            normalized.contains("ya no escuches") ||
            normalized.contains("apagate") ||
            normalized.contains("no necesito nada") ||
            normalized.contains("no quiero nada") ||
            normalized.contains("eso es todo") ||
            normalized.contains("eso seria todo") ||
            normalized.contains("chao fifo") ||
            normalized.contains("callate")

        return isNightSleep || isDirectSleep
    }

    /**
     * Determina si el texto transcrito es un eco acústico de la propia voz de Fifo.
     *
     * [spokenSentence] es la última locución; [awaitingAnswer] indica que Fifo
     * terminó de hablar y espera respuesta (follow-up o barge-in). En ese caso
     * nunca se descarta audio: respuestas como "Sí", "Boleros" o "A las ocho"
     * pueden coincidir con palabras de la pregunta.
     */
    fun isEcho(
        spokenSentence: String,
        text: String,
        isSpeakingNow: Boolean,
        awaitingAnswer: Boolean
    ): Boolean {
        if (spokenSentence.isBlank() || text.isBlank()) return false
        if (!isSpeakingNow && awaitingAnswer) return false

        val normSpoken = normalize(spokenSentence)
        val normText = normalize(text)
        if (normSpoken.isBlank() || normText.isBlank()) return false

        if (isSpeakingNow) {
            if (normSpoken.contains(normText) || normText.contains(normSpoken)) return true
            val wordsText = normText.split(" ").filter { it.length >= 3 }
            if (wordsText.isNotEmpty()) {
                val matching = wordsText.count { normSpoken.contains(it) }
                if (matching.toFloat() / wordsText.size >= 0.5f) return true
            }
        } else {
            // Fuera de locución activa: solo descartar repeticiones prácticamente completas.
            if (normText.length >= 20 && (normSpoken == normText || normSpoken.contains(normText))) {
                return true
            }
        }
        return false
    }

    /**
     * Determina si la frase emitida por Fifo formula una pregunta directa al
     * usuario (signos de interrogación, invitaciones finales o inicio de la
     * última oración).
     */
    fun isAskingQuestion(text: String): Boolean {
        if (text.isBlank()) return false
        val trimmed = text.trim()
        if (trimmed.contains("?") || trimmed.contains("¿")) return true

        val lower = normalize(trimmed)
        val endPrompts = listOf(
            "cuentame", "cuenteme", "dime", "digame",
            "que opinas", "que piensa", "que piensas", "que le parece", "te parece",
            "cual prefieres", "cual prefiere", "como te sientes", "como se siente"
        )
        if (endPrompts.any { lower.endsWith(it) }) return true

        val lastSentence = lower.split(Regex("[.!?]"))
            .filter { it.isNotBlank() }
            .lastOrNull()
            ?.trim()
            ?: lower
        val startPrompts = listOf("dime ", "digame ", "cuentame ", "cuenteme ")
        return startPrompts.any { lastSentence.startsWith(it) }
    }

    /**
     * Clasifica un comando determinista de control de llamada.
     *
     * Regla estricta (AGENTS.md): la invocación "Fifo" es obligatoria en todos
     * los casos. "contesta" o "cuelga" aislados NO son comandos válidos.
     */
    fun classifyCallAction(text: String, isRinging: Boolean): CallAction? {
        if (!isWakeWord(text)) return null
        val normalized = normalize(text)

        val statusWords = listOf("quien llama", "quien es", "quien esta llamando")
        if (statusWords.any { normalized.contains(it) }) return CallAction.CALLER_STATUS

        val hangupWords = listOf(
            "cuelga", "colgar", "rechaza", "rechazar", "corta", "cortar",
            "termina", "terminar", "finaliza", "finalizar", "deten", "detener",
            "cancela", "cancelar", "no contestes", "no contestar"
        )

        if (isRinging) {
            val answerWords = listOf(
                "contesta", "contestar", "contestame", "atiende", "atender", "atiendeme",
                "acepta", "aceptar", "responder", "responde", "toma la llamada", "tomar la llamada"
            )
            if (answerWords.any { normalized.contains(it) }) return CallAction.ANSWER
        }

        if (hangupWords.any { normalized.contains(it) }) return CallAction.HANGUP
        return null
    }
}
