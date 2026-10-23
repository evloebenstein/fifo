package com.fifo.voicepipeline.pipeline.session

import com.fifo.voicepipeline.data.memory.JournalEntry
import com.fifo.voicepipeline.pipeline.PipelineState
import com.fifo.voicepipeline.skills.FifoSkillRegistry
import com.fifo.voicepipeline.telephony.CallSnapshot
import kotlinx.coroutines.CancellationException
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
    private val speechSegmenter: SpeechSegmenter = SpeechSegmenter(),
    private val speechOutbound: SpeechOutboundEngine = SpeechOutboundEngine(outboundAdapter),
    private val turnOrchestrator: TurnOrchestrator = TurnOrchestrator(scope, speechOutbound),
    private val speechCognition: SpeechCognitionAdapter = InMemorySpeechCognitionAdapter(),
    private val skillRegistry: FifoSkillRegistry? = null,
    private val callState: StateFlow<CallSnapshot>? = null,
    private val onCallAction: ((CallAction) -> Boolean)? = null,
    initialMicMuted: Boolean = false,
    private val memory: SessionMemory? = null,
    private val nowProvider: () -> Long = System::currentTimeMillis
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

    private val _isMicMuted = MutableStateFlow(initialMicMuted)
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()

    /** Evita transcribir dos segmentos en paralelo y abrir dos turnos duplicados. */
    @Volatile
    private var isTranscribing = false

    /**
     * Procesa una intención de alto nivel enviada por la UI o el sistema (telefonía, pantalla).
     */
    fun dispatchIntent(intent: VoiceSessionIntent) {
        when (intent) {
            is VoiceSessionIntent.IncomingCall -> {
                speechOutbound.stopAndCaptureInterrupted()
                turnOrchestrator.wakeUpExplicitly()
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
                turnOrchestrator.wakeUpExplicitly()
                val query = if (turnOrchestrator.isWakeWord(intent.query)) {
                    intent.query
                } else {
                    "Fifo ${intent.query}"
                }
                processUserUtterance(query)
            }
        }
    }

    /**
     * Alimenta una trama de audio crudo entrante (desde Bluetooth LE o Micrófono del celular).
     *
     * El segmentador hace VAD y pre-roll. La energía (RMS) solo alimenta la boca/OLED:
     * no corta a Fifo. El corte se decide más abajo, con la transcripción ya lista.
     */
    fun onAudioFrameReceived(pcmData: ByteArray): SpeechSegment {
        if (_isMicMuted.value) {
            _rmsLevel.value = 0.0
            return SpeechSegment.Silence(0.0)
        }

        // Hay una transcripción en vuelo: no acumular tramas para un segundo turno.
        if (isTranscribing) {
            speechSegmenter.reset()
            _rmsLevel.value = 0.0
            return SpeechSegment.Silence(0.0)
        }

        // Mientras el LLM piensa no se abre otro turno. La locución sí se puede interrumpir
        // porque su audio también pasa por el segmentador y se evalúa por contenido.
        if (turnOrchestrator.state.value == PipelineState.PROCESSING && !speechOutbound.isSpeaking) {
            speechSegmenter.reset()
            _rmsLevel.value = 0.0
            return SpeechSegment.Silence(0.0)
        }

        val segment = speechSegmenter.processFrame(
            pcmData = pcmData,
            isSpeakingNow = speechOutbound.isSpeaking
        )
        _rmsLevel.value = segment.rms

        when (segment) {
            is SpeechSegment.Started -> {
                if (!speechOutbound.isSpeaking && turnOrchestrator.isAwake.value) {
                    _statusMessage.value = "Escuchando..."
                }
            }
            is SpeechSegment.Ended -> {
                _statusMessage.value = "Transcribiendo..."
                processUserAudio(segment.wavData)
            }
            is SpeechSegment.Continuing, is SpeechSegment.Silence -> Unit
        }

        return segment
    }

    /**
     * Procesa audio completo de usuario (ej. delimitado por VAD).
     */
    fun processUserAudio(wavData: ByteArray) {
        if (isTranscribing) return
        isTranscribing = true
        scope.launch {
            try {
                // Capturar antes de transcribir: la ventana de follow-up aún debe permitir
                // respuestas cortas ("sí", "no", "ok"). No cambiamos a PROCESSING hasta que
                // la transcripción demuestre que hay un turno real; así un ruido ambiental
                // no deja a Fifo atascado pensando.
                val allowShortResponses = turnOrchestrator.shouldAllowShortResponses()
                _statusMessage.value = "Transcribiendo..."

                val text = speechCognition.transcribe(
                    wavData,
                    allowShortResponses = allowShortResponses
                ).trim()
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

                    _statusMessage.value = if (turnOrchestrator.isAwake.value) {
                        "Fifo listo"
                    } else {
                        "Fifo durmiendo · Diga 'Fifo'"
                    }
                    turnOrchestrator.resetAutoSleepTimer()
                    return@launch
                }

                processUserUtterance(text)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _statusMessage.value = "No pude entender el audio"
            } finally {
                isTranscribing = false
            }
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
            val text = rawText.trim()
            if (text.isBlank()) return@launch

            val now = nowProvider()
            val isSpeakingNow = turnOrchestrator.state.value == PipelineState.SPEAKING ||
                speechOutbound.isSpeaking
            val isEcho = turnOrchestrator.isEchoOfLastSpokenSentence(text, isSpeakingNow)

            val isFollowUp = turnOrchestrator.expectFollowUp.value
            val isBargeIn = turnOrchestrator.isWaitingForBargeInResolution
            val isWakeWord = turnOrchestrator.isWakeWord(text)
            val previousTurn = try {
                memory?.recentTurns(1)?.firstOrNull()
            } catch (_: Exception) {
                null
            }
            val isCorrection = previousTurn != null &&
                ConversationRepairPolicy.isCorrection(text, previousTurn)

            // Silencio gana al escudo de eco: el usuario puede repetir las mismas
            // palabras que acaba de decir Fifo para detenerlo.
            if (turnOrchestrator.isSilenceCommand(text, whileSpeaking = isSpeakingNow || isBargeIn)) {
                interruptAndQuiet()
                return@launch
            }

            // Eco de la propia voz de Fifo: no abre turno y, sobre todo, no corta TTS.
            // Esto también protege el anuncio de timbrado, que contiene las propias
            // frases "Fifo contesta" / "Fifo cuelga": un eco nunca debe contestar una llamada.
            if (isEcho) {
                if (isSpeakingNow) _statusMessage.value = "Eco ignorado"
                return@launch
            }

            // Control de llamada determinista y con palabra de activación completa.
            // Se evalúa después del escudo de eco para no auto-contestar con el propio TTS.
            val callSnapshot = callState?.value
            val callAction = if (callSnapshot?.hasCall == true && isExplicitCallCommand(text)) {
                turnOrchestrator.classifyCallAction(text, callSnapshot.isRinging)
            } else {
                null
            }
            if (callAction != null && onCallAction != null && onCallAction.invoke(callAction)) {
                if (callAction == CallAction.ANSWER || callAction == CallAction.HANGUP) {
                    speechOutbound.stopAndCaptureInterrupted()
                }
                _statusMessage.value = when (callAction) {
                    CallAction.ANSWER -> "Contestando..."
                    CallAction.HANGUP -> "Colgando..."
                    CallAction.CALLER_STATUS -> "Consultando llamada..."
                }
                return@launch
            }

            // Si no dijo "Fifo" y no estamos en follow-up, barge-in ni reparación,
            // descartar audio ambiental. La corrección se acepta sin wake word solo
            // cuando existe un turno anterior y la política la reconoce.
            if (!isWakeWord && !isFollowUp && !isBargeIn && !isCorrection) {
                _statusMessage.value = "Audio ambiental descartado"
                return@launch
            }

            if (!turnOrchestrator.shouldProcessUtterance(text)) {
                return@launch
            }
            turnOrchestrator.markUtteranceProcessed(text)

            // Una nueva consulta con "Fifo" mientras habla es un barge-in confirmado
            // por el contenido de la transcripción, nunca por el RMS.
            if (isSpeakingNow && isWakeWord && !isBargeIn) {
                turnOrchestrator.onBargeInTriggered()
            }
            val effectiveBargeIn = isBargeIn || (isSpeakingNow && isWakeWord)

            // Evaluar si es orden de reposo ("duérmete", "a dormir")
            val isSleepCommand = turnOrchestrator.isSleepCommand(text)
            val cleanedQuery = turnOrchestrator.extractQuery(text)

            // Reparación ±1: el turno anterior y esta corrección son el contexto.
            // Se corrige el turno anterior y se registra la corrección como un
            // turno nuevo para que la memoria conserve ambos lados del cambio.
            if (isCorrection && previousTurn != null) {
                handleCorrection(previousTurn, text, cleanedQuery, now, isSpeakingNow)
                return@launch
            }

            // Si fue una interrupción pero solo dijo "Fifo" y nada más, reanudar de forma natural con LLM
            if (effectiveBargeIn && (cleanedQuery.isBlank() || cleanedQuery.equals("fifo", ignoreCase = true))) {
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

            // El turno se escribe en disco antes de que empiece la locución. Si
            // la respuesta es un error operativo, queda marcada para reparación.
            recordTurnSafely(
                userText = text,
                assistantText = response,
                needsRepair = ConversationRepairPolicy.isAgentError(response)
            )

            // Si era una orden de descanso/dormir, habla la despedida empática del LLM y luego va a dormir
            if (isSleepCommand) {
                speakResponse(response) {
                    turnOrchestrator.goToSleep()
                    memory?.requestConsolidation()
                }
            } else {
                speakResponse(response)
            }
        }
    }

    /**
     * Repara el turno anterior con el contexto de la corrección actual.
     *
     * Si el turno original todavía está dentro de la ventana de ~10 s, la
     * corrección se locuta. Si no, se guarda en memoria y se muestra en OLED
     * para no interrumpir al usuario con una corrección tardía.
     */
    private suspend fun handleCorrection(
        previousTurn: JournalEntry,
        rawText: String,
        cleanedQuery: String,
        now: Long,
        isSpeakingNow: Boolean
    ) {
        val shouldSpeak = ConversationRepairPolicy.canSpeakCorrection(previousTurn, now)
        turnOrchestrator.clearBargeInResolution()

        // Una corrección vigente puede llegar mientras Fifo todavía habla: la
        // nueva respuesta reemplaza a la anterior y no se encola sobre ella.
        // Una corrección tardía no interrumpe la locución en curso.
        if (shouldSpeak) {
            speechOutbound.stopAndCaptureInterrupted()
            turnOrchestrator.onProcessingStarted()
        }
        _statusMessage.value = "Corrigiendo..."

        val repair = try {
            speechCognition.generateRepairTurn(
                correctionText = cleanedQuery.ifBlank { rawText },
                previousUserText = previousTurn.userText,
                previousAssistantText = previousTurn.repairText ?: previousTurn.assistantText
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

        if (repair.isNullOrBlank()) {
            _statusMessage.value = "No pude corregir eso ahora"
            if (shouldSpeak || !isSpeakingNow) turnOrchestrator.wakeUpExplicitly()
            return
        }

        val repairedAt = nowProvider()
        try {
            memory?.markRepaired(previousTurn.id, repair, repairedAt)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // La corrección hablada no depende de que el diario esté disponible.
        }
        recordTurnSafely(
            userText = rawText,
            assistantText = repair,
            needsRepair = false
        )

        if (shouldSpeak) {
            speakResponse(repair)
        } else {
            _aiResponse.value = repair
            _statusMessage.value = "Corrección guardada"
            if (!isSpeakingNow) turnOrchestrator.wakeUpExplicitly()
        }
    }

    /** La conversación nunca se cae porque el diario no pueda escribir. */
    private fun recordTurnSafely(userText: String, assistantText: String, needsRepair: Boolean) {
        try {
            memory?.recordTurn(userText, assistantText, needsRepair)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // La locución continúa; el turno se reintentará en la próxima sesión
            // solo si el diario alcanzó a persistirlo.
        }
    }

    /**
     * Evita que un eco largo del anuncio ("Diga 'Fifo contesta' o 'Fifo cuelga'")
     * se confunda con una orden real. La orden debe ser corta y no contener verbos
     * de habla del propio anuncio.
     */
    private fun isExplicitCallCommand(rawText: String): Boolean {
        val query = turnOrchestrator.extractQuery(rawText).lowercase().trim()
        if (query.isBlank() || query.length > 40) return false
        if (query.contains("diga") || query.contains("decir") || query.contains("dice")) return false
        // El anuncio completo ("Fifo contesta o Fifo cuelga") no es una orden.
        if (query.contains(" o ")) return false
        return query.split(Regex("\\s+")).count { it.isNotBlank() } <= 4
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
        setMicMuted(!_isMicMuted.value)
    }

    /**
     * Fija el silenciamiento del micrófono de forma idempotente.
     */
    fun setMicMuted(muted: Boolean) {
        if (_isMicMuted.value == muted) return
        _isMicMuted.value = muted
        if (muted) {
            interruptAndQuiet()
            speechSegmenter.reset()
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
        memory?.requestConsolidation()
    }

    /** Actualiza el texto de estado desde el coordinador de telefonía. */
    fun setCallStatus(message: String) {
        _statusMessage.value = message
    }

    fun resetSession() {
        interruptAndQuiet()
        speechSegmenter.reset()
        _transcription.value = ""
        _aiResponse.value = ""
        _statusMessage.value = "Fifo listo · Diga 'Fifo'"
        memory?.requestConsolidation()
    }
}
