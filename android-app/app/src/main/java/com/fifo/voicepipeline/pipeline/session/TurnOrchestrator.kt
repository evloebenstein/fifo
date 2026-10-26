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
    //  Política léxica compartida (ADR-0042)
    // ─────────────────────────────────────────────────────────────────────
    // Las reglas de invocación, silencio, sueño, eco, pregunta y control de
    // llamada viven en UtterancePolicy. El orquestador solo aporta el estado
    // conversacional (frase hablada y ventanas abiertas) y delega el resto.

    fun isWakeWord(text: String): Boolean = UtterancePolicy.isWakeWord(text)

    fun extractQuery(text: String): String = UtterancePolicy.extractQuery(text)

    fun isGreetingOnly(text: String): Boolean = UtterancePolicy.isGreetingOnly(text)

    fun isSilenceCommand(text: String, whileSpeaking: Boolean = false): Boolean =
        UtterancePolicy.isSilenceCommand(text, whileSpeaking)

    fun isSleepCommand(text: String): Boolean = UtterancePolicy.isSleepCommand(text)

    /**
     * Determina si el texto transcrito es un eco de la propia voz de Fifo.
     * Nunca descarta audio cuando Fifo ya terminó y espera una respuesta
     * (follow-up o barge-in): "Sí", "Boleros" o "A las ocho" pueden coincidir
     * con palabras de la pregunta.
     */
    fun isEchoOfLastSpokenSentence(text: String, isSpeakingNow: Boolean): Boolean =
        UtterancePolicy.isEcho(
            spokenSentence = lastSpokenSentence,
            text = text,
            isSpeakingNow = isSpeakingNow,
            awaitingAnswer = _expectFollowUp.value || isWaitingForBargeInResolution
        )

    fun isAskingQuestion(text: String): Boolean = UtterancePolicy.isAskingQuestion(text)

    fun classifyCallAction(text: String, isRinging: Boolean): CallAction? =
        UtterancePolicy.classifyCallAction(text, isRinging)

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
