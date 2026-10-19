package com.fifo.voicepipeline.pipeline.session

import com.fifo.voicepipeline.pipeline.PipelineState
import com.fifo.voicepipeline.skills.FifoSkillRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Intenciones de ciclo de vida e interacción para VoiceSession.
 */
sealed class VoiceSessionIntent {
    data object IncomingCall : VoiceSessionIntent()
    data object CallEnded : VoiceSessionIntent()
    data object PushToTalk : VoiceSessionIntent()
    data object ToggleMicMute : VoiceSessionIntent()
    data object GoToSleep : VoiceSessionIntent()
    data object WakeUpManually : VoiceSessionIntent()
    data class ProcessTextQuery(val query: String) : VoiceSessionIntent()
}

/**
 * Módulo profundo de Sesión de Voz (VoiceSession).
 *
 * Oculta la complejidad de:
 * - Búfer circular de pre-roll (480ms)
 * - Filtrado y blindaje contra eco del altavoz
 * - Máquina de estados conversacionales (TurnOrchestrator)
 * - Barge-in con resolución contextual dual y reanudación natural vía LLM
 * - Eliminación de respuestas automáticas genéricas/hardcodeadas (todo el diálogo fluye por el LLM)
 * - Sanitización de texto para locución (SpeechOutboundEngine)
 * - Transcripción y diálogo cognitivo desacoplados (SpeechCognitionAdapter)
 * - Temporizadores de seguimiento y auto-sueño
 *
 * El llamador interactúa mediante intenciones de alto nivel y observa flujos de estado inmutables.
 */
class VoiceSession(
    private val scope: CoroutineScope,
    private val outboundAdapter: AudioOutboundAdapter,
    val inboundFilter: AudioInboundFilter = AudioInboundFilter(),
    val speechOutbound: SpeechOutboundEngine = SpeechOutboundEngine(outboundAdapter),
    val turnOrchestrator: TurnOrchestrator = TurnOrchestrator(scope, speechOutbound),
    val speechCognition: SpeechCognitionAdapter = InMemorySpeechCognitionAdapter(),
    var skillRegistry: FifoSkillRegistry? = null
) {
    // ── Estados observables para la UI / Servicios ──────────────
    val state: StateFlow<PipelineState> = turnOrchestrator.state
    val isAwake: StateFlow<Boolean> = turnOrchestrator.isAwake

    private val _rmsLevel = MutableStateFlow(0.0)
    val rmsLevel: StateFlow<Double> = _rmsLevel.asStateFlow()

    private val _transcription = MutableStateFlow("")
    val transcription: StateFlow<String> = _transcription.asStateFlow()

    private val _aiResponse = MutableStateFlow("")
    val aiResponse: StateFlow<String> = _aiResponse.asStateFlow()

    private val _statusMessage = MutableStateFlow("Fifo listo · Diga 'Fifo'")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _isMicMuted = MutableStateFlow(false)
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()

    /**
     * Procesa una intención de alto nivel enviada por la UI o el sistema (telefonía, pantalla).
     */
    fun dispatchIntent(intent: VoiceSessionIntent) {
        when (intent) {
            is VoiceSessionIntent.IncomingCall -> {
                speechOutbound.stopAndCaptureInterrupted()
                turnOrchestrator.goToSleep()
                _statusMessage.value = "Llamada telefónica en curso"
            }
            is VoiceSessionIntent.CallEnded -> {
                turnOrchestrator.wakeUpExplicitly()
                _statusMessage.value = "Fifo listo tras llamada"
            }
            is VoiceSessionIntent.PushToTalk -> {
                pushToTalk()
            }
            is VoiceSessionIntent.ToggleMicMute -> {
                toggleMicMute()
            }
            is VoiceSessionIntent.GoToSleep -> {
                sleep()
            }
            is VoiceSessionIntent.WakeUpManually -> {
                turnOrchestrator.wakeUpExplicitly()
                _statusMessage.value = "Te escucho..."
            }
            is VoiceSessionIntent.ProcessTextQuery -> {
                processUserUtterance(intent.query)
            }
        }
    }

    /**
     * Alimenta una trama de audio crudo entrante (desde Bluetooth LE o Micrófono del celular).
     */
    fun onAudioFrameReceived(pcmData: ByteArray): InboundFrameResult {
        if (_isMicMuted.value) {
            _rmsLevel.value = 0.0
            return InboundFrameResult.Silent(0.0)
        }

        val result = inboundFilter.processFrame(
            pcmData = pcmData,
            isSpeakingNow = speechOutbound.isSpeaking
        )

        when (result) {
            is InboundFrameResult.EchoSuppressed -> {
                // Silenciado por blindaje de altavoz
            }
            is InboundFrameResult.Silent -> {
                _rmsLevel.value = result.rms
            }
            is InboundFrameResult.Speech -> {
                _rmsLevel.value = result.rms
            }
            is InboundFrameResult.BargeInDetected -> {
                _rmsLevel.value = result.rms
                turnOrchestrator.onBargeInTriggered()
                _statusMessage.value = "Te escucho..."
            }
        }

        return result
    }

    /**
     * Procesa audio completo de usuario (ej. delimitado por VAD).
     */
    fun processUserAudio(wavData: ByteArray) {
        scope.launch {
            turnOrchestrator.onProcessingStarted()
            _statusMessage.value = "Transcribiendo..."

            val text = speechCognition.transcribe(wavData).trim()
            _transcription.value = text

            if (text.isBlank()) {
                // Si hubo un barge-in y el usuario se quedó en silencio, reanudar mediante LLM
                if (turnOrchestrator.isWaitingForBargeInResolution) {
                    val interrupted = turnOrchestrator.interruptedSpokenSentence
                    if (!interrupted.isNullOrBlank()) {
                        _statusMessage.value = "Reanudando..."
                        val resumption = speechCognition.generateNaturalResumption(interrupted)
                        turnOrchestrator.clearBargeInResolution()
                        speakResponse(resumption)
                        return@launch
                    }
                }

                _statusMessage.value = "Fifo listo"
                turnOrchestrator.resetAutoSleepTimer()
                return@launch
            }

            processUserUtterance(text)
        }
    }

    /**
     * Procesa texto emitido por el usuario (desde STT o campo de texto manual).
     *
     * 100% CERO RESPUESTAS AUTOMÁTICAS HARDCODEADAS:
     * - Si el usuario dice silencio ("cállate", "para"), Fifo calla en silencio absoluto (0 palabras).
     * - Si el usuario pide dormir o despedirse, el LLM genera una despedida empática personalizada y luego duerme.
     * - Si el usuario solo dice "Fifo", el LLM genera un saludo espontáneo y cálido adaptado al usuario.
     * - Toda consulta o charla es resuelta dinámicamente por el LLM con memoria episódica y afecto.
     */
    fun processUserUtterance(rawText: String) {
        scope.launch {
            val isFollowUp = turnOrchestrator.expectFollowUp.value
            val isBargeIn = turnOrchestrator.isWaitingForBargeInResolution
            val isWakeWord = turnOrchestrator.isWakeWord(rawText)

            // Si no dijo "Fifo" y no estamos en follow-up ni en barge-in, descartar audio ambiental
            if (!isWakeWord && !isFollowUp && !isBargeIn) {
                _statusMessage.value = "Audio ambiental descartado"
                return@launch
            }

            // Orden de silencio ("cállate", "para", "basta") -> Silencio absoluto sin decir ninguna palabra
            if (turnOrchestrator.isSilenceCommand(rawText)) {
                interruptAndQuiet()
                return@launch
            }

            // Evaluar si es orden de reposo ("duérmete", "a dormir")
            val isSleepCommand = turnOrchestrator.isSleepCommand(rawText)

            // Si fue una interrupción pero solo dijo "Fifo" y nada más, reanudar de forma natural con LLM
            val cleanedQuery = turnOrchestrator.extractQuery(rawText)
            if (isBargeIn && (cleanedQuery.isBlank() || cleanedQuery.equals("fifo", ignoreCase = true))) {
                val interrupted = turnOrchestrator.interruptedSpokenSentence
                if (!interrupted.isNullOrBlank()) {
                    _statusMessage.value = "Reanudando..."
                    val resumption = speechCognition.generateNaturalResumption(interrupted)
                    turnOrchestrator.clearBargeInResolution()
                    speakResponse(resumption)
                    return@launch
                }
            }

            turnOrchestrator.clearBargeInResolution()
            turnOrchestrator.onProcessingStarted()
            _statusMessage.value = "Pensando..."

            // Si el usuario únicamente pronunció la palabra de activación "Fifo" (o "Hola Fifo"),
            // enviamos una indicación al LLM para que salude de forma espontánea, cálida y no genérica.
            val queryForLlm = if (cleanedQuery.isBlank() || cleanedQuery.equals("fifo", ignoreCase = true)) {
                "El usuario acaba de decir 'Fifo' para iniciar una conversación."
            } else {
                cleanedQuery
            }

            val response = speechCognition.generateDialogTurn(queryForLlm, skillRegistry)

            // Si era una orden de descanso/dormir, habla la despedida empática del LLM y luego va a dormir
            if (isSleepCommand) {
                speakResponse(response) {
                    turnOrchestrator.goToSleep()
                }
            } else {
                speakResponse(response)
            }
        }
    }

    /**
     * Inicia la locución de la respuesta de Fifo, notificando estados.
     */
    fun speakResponse(
        response: String,
        onRmsUpdate: ((Double) -> Unit)? = null,
        onFinish: (() -> Unit)? = null
    ) {
        _aiResponse.value = response
        turnOrchestrator.onSpeakingStarted(response)
        _statusMessage.value = "Fifo hablando"

        speechOutbound.speak(
            rawText = response,
            onRmsUpdate = { rms ->
                _rmsLevel.value = rms
                onRmsUpdate?.invoke(rms)
            },
            onFinish = {
                _rmsLevel.value = 0.0
                turnOrchestrator.onSpeakingFinished(response)
                _statusMessage.value = if (turnOrchestrator.expectFollowUp.value) {
                    "Fifo esperando tu respuesta..."
                } else {
                    "Fifo listo"
                }
                onFinish?.invoke()
            }
        )
    }

    /**
     * Interrumpe inmediatamente cualquier locución en curso y pone a Fifo en reposo sin emitir palabra.
     */
    fun interruptAndQuiet() {
        speechOutbound.stopAndCaptureInterrupted()
        turnOrchestrator.goToSleep()
        _statusMessage.value = "Fifo en silencio"
        _rmsLevel.value = 0.0
    }

    /**
     * Activa explícitamente a Fifo al pulsar la pantalla (Push to talk).
     */
    fun pushToTalk() {
        turnOrchestrator.wakeUpExplicitly()
        _statusMessage.value = "Te escucho..."
    }

    /**
     * Alterna el silenciamiento del micrófono.
     */
    fun toggleMicMute() {
        _isMicMuted.value = !_isMicMuted.value
        if (_isMicMuted.value) {
            _rmsLevel.value = 0.0
            _statusMessage.value = "Micrófono silenciado"
        } else {
            _statusMessage.value = "Micrófono activo"
        }
    }

    fun sleep() {
        turnOrchestrator.goToSleep()
        _statusMessage.value = "Fifo durmiendo · Diga 'Fifo'"
        _rmsLevel.value = 0.0
    }

    fun resetSession() {
        interruptAndQuiet()
        _transcription.value = ""
        _aiResponse.value = ""
        _statusMessage.value = "Fifo listo · Diga 'Fifo'"
    }
}
