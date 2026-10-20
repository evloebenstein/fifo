package com.fifo.voicepipeline.pipeline.session

import com.fifo.voicepipeline.pipeline.PipelineState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * Orquestador conversacional profundo (TurnOrchestrator):
 * - Gestiona la máquina de estados reactiva (SLEEPING -> IDLE -> LISTENING -> PROCESSING -> SPEAKING).
 * - Identifica la palabra de activación ("Fifo") tanto aislada como en medio de una frase.
 * - Habilita la ventana de seguimiento (follow-up de 14s tras preguntas de Fifo) para responder sin decir "Fifo".
 * - Implementa el Barge-in con resolución contextual dual (Fifo estaba diciendo vs Usuario interrumpió).
 * - Detecta órdenes de silencio inmediatas ("cállate", "silencio", "para", "basta").
 * - Detecta órdenes de sueño ("duérmete", "a dormir", "descansa").
 * - Temporizador de auto-sueño tras inactividad (25s).
 */
class TurnOrchestrator(
    private val scope: CoroutineScope,
    private val speechOutbound: SpeechOutboundEngine,
    val autoSleepTimeoutMs: Long = 25_000L,
    val followUpTimeoutMs: Long = 14_000L
) {
    private val _state = MutableStateFlow(PipelineState.SLEEPING)
    val state: StateFlow<PipelineState> = _state.asStateFlow()

    private val _isAwake = MutableStateFlow(false)
    val isAwake: StateFlow<Boolean> = _isAwake.asStateFlow()

    private val _expectFollowUp = MutableStateFlow(false)
    val expectFollowUp: StateFlow<Boolean> = _expectFollowUp.asStateFlow()

    private var autoSleepJob: Job? = null
    private var followUpJob: Job? = null

    var interruptedSpokenSentence: String? = null
        private set
    var isWaitingForBargeInResolution: Boolean = false
        private set

    /**
     * Evalúa si el texto contiene la invocación requerida ("Fifo") o si estamos dentro de
     * una ventana de seguimiento (follow-up) activa tras una pregunta de Fifo.
     */
    fun isWakeWord(text: String): Boolean {
        // En ventana de seguimiento, cualquier respuesta es válida sin repetir la palabra clave
        if (_expectFollowUp.value) return true

        val lower = text.lowercase().trim()
        val normalized = lower.replace(Regex("[¡!¿?,.:;]"), " ")
        val tokens = normalized.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false

        val wakeWords = setOf("fifo", "fifi", "fipo", "fibo", "figo")
        if (tokens.any { wakeWords.contains(it) }) return true

        val greetingTriggers = listOf(
            "oye fifo", "hola fifo", "hey fifo", "buenos días fifo", "buenas tardes fifo", "buenas noches fifo"
        )
        return greetingTriggers.any { normalized.contains(it) }
    }

    /**
     * Extrae el contenido de la consulta del usuario limpiando el saludo o prefijo "Fifo".
     */
    fun extractQuery(text: String): String {
        var clean = text.trim()
        val prefixes = listOf(
            Regex("(?i)^[¡¿]?(?:oye|hola|hey|buenos días|buenas tardes|buenas noches)?\\s*fifo[\\s,.:;!?-]*"),
            Regex("(?i)^[¡¿]?fifo[\\s,.:;!?-]*")
        )
        for (pattern in prefixes) {
            clean = clean.replace(pattern, "").trim()
        }
        return clean
    }

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
     */
    fun onBargeInTriggered(): String {
        val interrupted = speechOutbound.stopAndCaptureInterrupted()
        interruptedSpokenSentence = interrupted
        isWaitingForBargeInResolution = true
        _state.value = PipelineState.LISTENING
        resetAutoSleepTimer()
        return interrupted
    }

    /**
     * Evalúa si una frase dicha por el usuario es una orden explícita de silencio o pausa.
     */
    fun isSilenceCommand(text: String): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase().trim()

        // Palabras de silencio que son inequívocas incluso en frases largas
        val unambiguousKeywords = listOf(
            "cállate", "callate", "silencio", "basta", "detente",
            "no hables", "shh", "shhh", "apágate", "apagate",
            "fifo para", "para fifo", "para ya", "fifo cállate", "fifo silencio"
        )
        if (unambiguousKeywords.any { lower.contains(it) }) return true

        // "para" y "espera" solo son órdenes de silencio cuando son comandos
        // cortos aislados (≤ 2 tokens) — NO cuando son preposiciones en frases largas
        // como "necesito ayuda para ir al médico" o "busca una ruta para el aeropuerto".
        val tokens = lower.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (tokens.size <= 3) {
            val shortStopWords = listOf("para", "parate", "espera", "detente", "alto")
            if (tokens.any { it in shortStopWords }) return true
        }

        return false
    }

    /**
     * Evalúa si la frase es una orden para poner a Fifo a dormir.
     */
    fun isSleepCommand(text: String): Boolean {
        val lower = text.lowercase().trim()
        val sleepWords = listOf(
            "duérmete", "duermete", "a dormir", "descansa", "apágate", "apagate",
            "adiós fifo", "adios fifo", "chao fifo", "hasta luego fifo"
        )
        return sleepWords.any { lower.contains(it) }
    }

    /**
     * Registra el inicio del procesamiento del LLM.
     */
    fun onProcessingStarted() {
        _state.value = PipelineState.PROCESSING
        cancelFollowUpTimer()
    }

    /**
     * Registra el inicio de la locución de la respuesta de Fifo.
     */
    fun onSpeakingStarted(responseSentence: String) {
        _state.value = PipelineState.SPEAKING
        isWaitingForBargeInResolution = false
        resetAutoSleepTimer()
    }

    /**
     * Registra la finalización natural de la locución de Fifo.
     * Si la frase incluía una pregunta, abre la ventana de seguimiento (follow-up).
     */
    fun onSpeakingFinished(lastSentence: String) {
        interruptedSpokenSentence = null
        isWaitingForBargeInResolution = false

        val isQuestion = lastSentence.contains("?") || lastSentence.contains("¿")
        if (isQuestion) {
            startFollowUpWindow()
        } else {
            _state.value = PipelineState.IDLE
            resetAutoSleepTimer()
        }
    }

    /**
     * Abre la ventana de seguimiento donde el usuario puede responder sin decir "Fifo".
     */
    fun startFollowUpWindow() {
        _expectFollowUp.value = true
        _state.value = PipelineState.LISTENING
        followUpJob?.cancel()
        followUpJob = scope.launch {
            delay(followUpTimeoutMs)
            _expectFollowUp.value = false
            if (_state.value == PipelineState.LISTENING) {
                _state.value = PipelineState.IDLE
                resetAutoSleepTimer()
            }
        }
    }

    fun cancelFollowUpTimer() {
        followUpJob?.cancel()
        _expectFollowUp.value = false
    }

    /**
     * Reinicia el temporizador de auto-sueño (25 segundos de inactividad).
     */
    fun resetAutoSleepTimer() {
        autoSleepJob?.cancel()
        autoSleepJob = scope.launch {
            delay(autoSleepTimeoutMs)
            goToSleep()
        }
    }

    /**
     * Pone a Fifo en modo reposo/sueño.
     */
    fun goToSleep() {
        autoSleepJob?.cancel()
        cancelFollowUpTimer()
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
        isWaitingForBargeInResolution = false
        interruptedSpokenSentence = null
    }
}
