package com.fifo.voicepipeline.pipeline.session

import com.fifo.voicepipeline.pipeline.PipelineState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.Normalizer

/**
 * Evento emitido por el TurnOrchestrator hacia el llamador/UI.
 */
sealed class TurnEvent {
    data object WokenUp : TurnEvent()
    data class BargeInInterrupted(val interruptedSentence: String) : TurnEvent()
    data class UserUtteranceReady(val text: String, val isBargeIn: Boolean, val priorSentence: String?) : TurnEvent()
    data object SilenceCommandReceived : TurnEvent()
    data object WentToSleep : TurnEvent()
}

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
 * Orquestador conversacional profundo (TurnOrchestrator):
 * - Gestiona la máquina de estados reactiva (SLEEPING -> IDLE -> LISTENING -> PROCESSING -> SPEAKING).
 * - Identifica la palabra de activación ("Fifo") de forma puramente léxica, tanto aislada como
 *   en medio de una frase. No confunde "estar en follow-up" con "haber dicho Fifo": el control
 *   de llamada necesita distinguir ambos casos.
 * - Habilita la ventana de seguimiento (follow-up de 14s tras preguntas de Fifo) para responder
 *   sin decir "Fifo".
 * - Implementa el Barge-in con resolución contextual dual (Fifo estaba diciendo vs Usuario interrumpió)
 *   y una ventana de 5s de espera silenciosa antes de reanudar.
 * - Detecta órdenes de silencio inmediatas ("cállate", "silencio", "para", "basta").
 * - Detecta órdenes de sueño ("duérmete", "a dormir", "descansa").
 * - Temporizador de auto-sueño tras inactividad (25s; 120s en escucha continua).
 *
 * Es un módulo puro de JVM: no toca audio, telefonía, Android ni el repositorio de datos.
 */
class TurnOrchestrator(
    private val scope: CoroutineScope,
    private val speechOutbound: SpeechOutboundEngine,
    val autoSleepTimeoutMs: Long = 25_000L,
    val continuousListeningTimeoutMs: Long = 120_000L,
    val followUpTimeoutMs: Long = 14_000L,
    val bargeInResumeTimeoutMs: Long = 5_000L,
    val debounceWindowMs: Long = 2_500L,
    /**
     * Se invoca cuando expira la ventana de 5s tras un barge-in sin que el usuario diga nada.
     * La sesión decide cómo reanudar (siempre vía LLM); el orquestador solo gobierna el tiempo.
     */
    private val onBargeInResumeRequested: ((interruptedSentence: String) -> Unit)? = null
) {
    private val _state = MutableStateFlow(PipelineState.SLEEPING)
    val state: StateFlow<PipelineState> = _state.asStateFlow()

    private val _isAwake = MutableStateFlow(false)
    val isAwake: StateFlow<Boolean> = _isAwake.asStateFlow()

    private val _expectFollowUp = MutableStateFlow(false)
    val expectFollowUp: StateFlow<Boolean> = _expectFollowUp.asStateFlow()

    private var autoSleepJob: Job? = null
    private var followUpJob: Job? = null
    private var bargeInResumeJob: Job? = null

    /** Modo de escucha continua: mantiene la ventana abierta hasta 120s de silencio. */
    var continuousListeningEnabled: Boolean = false
        private set

    /** Evita responder dos veces al mismo callback duplicado de STT. */
    private var lastProcessedText: String = ""
    private var lastProcessedTimestamp: Long = 0L

    var interruptedSpokenSentence: String? = null
        private set
    var isWaitingForBargeInResolution: Boolean = false
        private set

    val lastSpokenSentence: String
        get() = speechOutbound.lastSpokenSentence

    // ─────────────────────────────────────────────────────────────────────
    //  Normalización y detección léxica
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Normaliza texto para comparaciones robustas: minúsculas, sin diacríticos,
     * sin puntuación y con espacios colapsados.
     */
    private fun normalize(text: String): String {
        return Normalizer.normalize(text.lowercase().trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Detecta la invocación "Fifo" de forma puramente léxica.
     *
     * Importante: NO devuelve true solo porque haya un follow-up abierto. La sesión combina
     * esta señal con [expectFollowUp] para decidir si acepta una respuesta directa. Separarlas
     * permite que "contesta" aislado siga siendo inválido durante un follow-up.
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
        // Se excluyen aliases riesgosos que producen falsos positivos en conversación normal
        // ("tito", "dijo", "kiko", "vivo", "pico", "hijo", "tipo").
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
        clean = clean.replace(Regex("(?i)[,;.!?¡¿\\s-]*\\b$wakeAlias\\b[,;.!?\\s-]*"), " ")

        return clean.trim()
    }

    /**
     * Verdadero si, tras quitar la invocación, solo queda un saludo (o nada).
     * Permite que la sesión delegue el saludo al LLM sin respuestas canónicas hardcodeadas.
     */
    fun isGreetingOnly(text: String): Boolean {
        val query = normalize(extractQuery(text))
        if (query.isBlank() || query.length < 2) return true
        return query in setOf(
            "hola", "buenas", "buenos dias", "buenas tardes", "buenas noches",
            "saludos", "hey", "oye", "que tal"
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Órdenes de silencio y sueño
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Detecta órdenes de silencio / callarse ("Fifo silencio", "Fifo cállate", "Fifo para",
     * "cállate", "silencio", etc.).
     *
     * Si [whileSpeaking] es true (Fifo está hablando o se capturó audio durante su locución),
     * acepta palabras cortas de detención sin exigir "Fifo" para máxima receptividad.
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
            "para de hablar", "corta ya", "cortala", "apagate",
            "mute", "no te estoy hablando", "callate fifo", "silencio fifo",
            "fifo silencio", "fifo callate", "fifo para", "para fifo",
            "fifo basta", "basta fifo", "fifo detente", "detente fifo", "fifo stop",
            "fifo ya", "fifo calma", "fifo callate ya", "fifo para ya", "calma fifo"
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

    // ─────────────────────────────────────────────────────────────────────
    //  Eco, preguntas y control de llamadas
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Determina si el texto transcrito es un eco acústico de la propia voz de Fifo.
     *
     * Nunca descarta audio cuando Fifo ya terminó y espera una respuesta (follow-up o barge-in):
     * respuestas como "Sí", "Boleros" o "A las ocho" pueden coincidir con palabras de la pregunta.
     */
    fun isEchoOfLastSpokenSentence(text: String, isSpeakingNow: Boolean): Boolean {
        val spoken = lastSpokenSentence
        if (spoken.isBlank() || text.isBlank()) return false
        if (!isSpeakingNow && (_expectFollowUp.value || isWaitingForBargeInResolution)) return false

        val normSpoken = normalize(spoken)
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
     * Determina si la frase emitida por Fifo formula una pregunta directa al usuario
     * (signos de interrogación, invitaciones finales o inicio de la última oración).
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
     * Regla estricta: la invocación "Fifo" es obligatoria en todos los casos.
     * "contesta" o "cuelga" aislados NO son comandos válidos.
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

    fun isCallAnswerCommand(text: String): Boolean =
        classifyCallAction(text, isRinging = true) == CallAction.ANSWER

    fun isCallHangupCommand(text: String): Boolean =
        classifyCallAction(text, isRinging = false) == CallAction.HANGUP

    /**
     * Compatibilidad con el camino legacy: true si hay una acción de llamada válida.
     */
    fun isCallActionCommand(text: String, isRinging: Boolean): Boolean =
        classifyCallAction(text, isRinging) != null

    // ─────────────────────────────────────────────────────────────────────
    //  Debounce de transcripciones duplicadas
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Indica si la transcripción debe procesarse. Descarta repeticiones idénticas dentro
     * de [debounceWindowMs] (callbacks duplicados de Google/Whisper).
     */
    fun shouldProcessUtterance(text: String, now: Long = System.currentTimeMillis()): Boolean {
        val current = text.trim().lowercase()
        return !(current == lastProcessedText.trim().lowercase() &&
            (now - lastProcessedTimestamp) < debounceWindowMs)
    }

    /** Registra una transcripción ya aceptada para el debounce. */
    fun markUtteranceProcessed(text: String, now: Long = System.currentTimeMillis()) {
        lastProcessedText = text
        lastProcessedTimestamp = now
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Ciclo de vida del turno
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Permite respuestas cortas ("sí", "no", "ok") cuando Fifo espera una respuesta.
     */
    fun shouldAllowShortResponses(): Boolean =
        _expectFollowUp.value || isWaitingForBargeInResolution

    /**
     * Procesa la detección de activación por voz ("Fifo" o frase inicial).
     */
    fun onWakeWordDetected(spokenText: String? = null) {
        resetAutoSleepTimer()
        _isAwake.value = true
        _state.value = PipelineState.LISTENING
    }

    /**
     * Se activa cuando el usuario interrumpe a Fifo mientras este habla (Barge-In).
     * Inicia una ventana de 5s: si el usuario no dice nada, se solicita reanudación natural.
     */
    fun onBargeInTriggered(): String {
        val interrupted = speechOutbound.stopAndCaptureInterrupted()
        interruptedSpokenSentence = interrupted
        isWaitingForBargeInResolution = true
        _isAwake.value = true
        _state.value = PipelineState.LISTENING
        resetAutoSleepTimer()
        startBargeInResumeTimer()
        return interrupted
    }

    private fun startBargeInResumeTimer() {
        bargeInResumeJob?.cancel()
        bargeInResumeJob = scope.launch {
            delay(bargeInResumeTimeoutMs)
            if (isWaitingForBargeInResolution &&
                _state.value != PipelineState.PROCESSING &&
                _state.value != PipelineState.SPEAKING
            ) {
                isWaitingForBargeInResolution = false
                val toResume = interruptedSpokenSentence
                interruptedSpokenSentence = null
                if (!toResume.isNullOrBlank()) {
                    onBargeInResumeRequested?.invoke(toResume)
                } else {
                    goToSleep()
                }
            }
        }
    }

    private fun cancelBargeInResumeTimer() {
        bargeInResumeJob?.cancel()
        bargeInResumeJob = null
    }

    /**
     * Registra el inicio del procesamiento del LLM.
     */
    fun onProcessingStarted() {
        _state.value = PipelineState.PROCESSING
        cancelFollowUpTimer()
        cancelBargeInResumeTimer()
    }

    /**
     * Registra el inicio de la locución de la respuesta de Fifo.
     * El flag de follow-up se conserva (lo decide [onSpeakingFinished]), pero se cancela
     * su temporizador para que no expire a mitad de una locución larga.
     */
    fun onSpeakingStarted(responseSentence: String) {
        _state.value = PipelineState.SPEAKING
        isWaitingForBargeInResolution = false
        cancelBargeInResumeTimer()
        followUpJob?.cancel()
        resetAutoSleepTimer()
    }

    /**
     * Registra la finalización natural de la locución de Fifo.
     *
     * Se mantiene despierto si: Fifo hizo una pregunta, el modo continuo está activo o ya
     * había un follow-up abierto antes de hablar (p. ej. el saludo inicial).
     */
    fun onSpeakingFinished(lastSentence: String) {
        interruptedSpokenSentence = null
        isWaitingForBargeInResolution = false
        cancelBargeInResumeTimer()

        val shouldStayAwake = continuousListeningEnabled ||
            isAskingQuestion(lastSentence) ||
            _expectFollowUp.value

        if (shouldStayAwake) {
            startFollowUpWindow()
        } else {
            _expectFollowUp.value = false
            _state.value = PipelineState.IDLE
            resetAutoSleepTimer()
        }
    }

    /**
     * Abre la ventana de seguimiento donde el usuario puede responder sin decir "Fifo".
     * En escucha continua la ventana se extiende a 120s.
     */
    fun startFollowUpWindow() {
        _expectFollowUp.value = true
        _isAwake.value = true
        _state.value = PipelineState.LISTENING
        autoSleepJob?.cancel()
        followUpJob?.cancel()

        val timeoutMs = if (continuousListeningEnabled) continuousListeningTimeoutMs else followUpTimeoutMs
        followUpJob = scope.launch {
            delay(timeoutMs)
            _expectFollowUp.value = false
            if (_isAwake.value &&
                _state.value != PipelineState.SPEAKING &&
                _state.value != PipelineState.PROCESSING
            ) {
                // Si la ventana era continua, al expirar se apaga el modo continuo.
                continuousListeningEnabled = false
                goToSleep()
            }
        }
    }

    fun cancelFollowUpTimer() {
        followUpJob?.cancel()
        _expectFollowUp.value = false
    }

    /**
     * Activa o desactiva el modo de escucha continua ("Fifo, sigue escuchando").
     */
    fun setContinuousListening(enabled: Boolean) {
        continuousListeningEnabled = enabled
        if (enabled) {
            _isAwake.value = true
            if (_state.value != PipelineState.SPEAKING && _state.value != PipelineState.PROCESSING) {
                _state.value = PipelineState.LISTENING
            }
            resetAutoSleepTimer()
        } else if (_isAwake.value) {
            resetAutoSleepTimer()
        }
    }

    /**
     * Reinicia el temporizador de auto-sueño (25s normal, 120s en escucha continua).
     * No duerme mientras Fifo está hablando o procesando.
     */
    fun resetAutoSleepTimer() {
        autoSleepJob?.cancel()
        val timeoutMs = if (continuousListeningEnabled) continuousListeningTimeoutMs else autoSleepTimeoutMs
        autoSleepJob = scope.launch {
            delay(timeoutMs)
            if (_isAwake.value &&
                _state.value != PipelineState.SPEAKING &&
                _state.value != PipelineState.PROCESSING
            ) {
                continuousListeningEnabled = false
                goToSleep()
            }
        }
    }

    /**
     * Pone a Fifo en modo reposo/sueño.
     */
    fun goToSleep() {
        autoSleepJob?.cancel()
        followUpJob?.cancel()
        cancelBargeInResumeTimer()
        _expectFollowUp.value = false
        continuousListeningEnabled = false
        interruptedSpokenSentence = null
        isWaitingForBargeInResolution = false
        speechOutbound.stopAndCaptureInterrupted()
        _isAwake.value = false
        _state.value = PipelineState.SLEEPING
    }

    /**
     * Despierta manualmente a Fifo (por ejemplo al tocar la pantalla o pulsar push-to-talk).
     */
    fun wakeUpExplicitly() {
        cancelFollowUpTimer()
        _isAwake.value = true
        _state.value = PipelineState.LISTENING
        resetAutoSleepTimer()
    }

    /**
     * Limpia el estado de resolución de barge-in tras consumirse.
     */
    fun clearBargeInResolution() {
        cancelBargeInResumeTimer()
        isWaitingForBargeInResolution = false
        interruptedSpokenSentence = null
    }
}
