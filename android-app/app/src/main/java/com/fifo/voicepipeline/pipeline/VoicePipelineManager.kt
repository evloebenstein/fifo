package com.fifo.voicepipeline.pipeline

import android.content.Context
import android.util.Log
import com.fifo.voicepipeline.audio.*
import com.fifo.voicepipeline.network.BleConnectionState
import com.fifo.voicepipeline.network.BleVoiceClient
import com.fifo.voicepipeline.network.CloudApiClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Orquestador principal del pipeline de voz FIFO — 100% Bluetooth (BLE).
 *
 * Flujo:
 * 1. Captura de audio:
 *    - Opción A: Micrófono analógico UCC en ESP32-S3 vía Bluetooth LE (BleVoiceClient)
 *    - Opción B: Micrófono integrado del celular (PhoneMicRecorder)
 * 2. Detección de voz (VAD) y fin de frase
 * 3. STT (Whisper OpenAI o Groq)
 * 4. LLM (Claude Anthropic) con respuestas en streaming
 * 5. Salida de audio (Parlante del celular):
 *    - TextToSpeech nativo de Android (gratuito, sin costo)
 *    - O OpenAI TTS vía AudioPlayer si se configura API key
 * 6. Actualización en tiempo real de la pantalla OLED 1.3" I2C del ESP32 vía Bluetooth LE
 */
class VoicePipelineManager(
    private val context: Context,
    initialGroqApiKey: String = "",
    initialAnthropicApiKey: String = "",
    initialOpenAiApiKey: String = ""
) {
    companion object {
        private const val TAG = "VoicePipeline"
    }

    var groqApiKey: String = initialGroqApiKey
        private set

    var anthropicApiKey: String = initialAnthropicApiKey
        private set

    var openAiApiKey: String = initialOpenAiApiKey
        private set

    val hasCloudStt: Boolean
        get() = (if (::cloudClient.isInitialized) (cloudClient.isGroqActive || cloudClient.openAiApiKey.isNotBlank()) else false) ||
                groqApiKey.isNotBlank() || openAiApiKey.isNotBlank()

    // ── Estado observable (para la UI) ──────────────
    private val _state = MutableStateFlow(PipelineState.DISCONNECTED)
    val state: StateFlow<PipelineState> = _state.asStateFlow()

    private val _isAwake = MutableStateFlow(false)
    val isAwake: StateFlow<Boolean> = _isAwake.asStateFlow()

    private val _micSource = MutableStateFlow(MicSource.ESP32)
    val micSource: StateFlow<MicSource> = _micSource.asStateFlow()

    private val _transcription = MutableStateFlow("")
    val transcription: StateFlow<String> = _transcription.asStateFlow()

    private val _aiResponse = MutableStateFlow("")
    val aiResponse: StateFlow<String> = _aiResponse.asStateFlow()

    private val _statusMessage = MutableStateFlow("Fifo desconectado · Conecte por Bluetooth")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _rmsLevel = MutableStateFlow(0.0)
    val rmsLevel: StateFlow<Double> = _rmsLevel.asStateFlow()

    private val prefs = context.getSharedPreferences("fifo_prefs", Context.MODE_PRIVATE)
    private val _isMicMuted = MutableStateFlow(prefs.getBoolean("is_mic_muted", false))
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()

    // Temporizador de auto-sueño para Fifo (25 segundos de inactividad)
    private var autoSleepJob: Job? = null

    // Control estricto de activación por palabra clave ("Fifo") y seguimiento conversacional
    private var expectFollowUpQuestion = false
    private var pendingNavigationRequest = false
    private var oneShotPushedToTalk = false
    private var lastSpokenSentence = ""

    // Control de concurrencia y prevención de respuestas duplicadas
    @Volatile private var isCurrentlyProcessing = false
    private var lastProcessedText = ""
    private var lastProcessedTimestamp = 0L

    // Turnos de la sesión activa para consolidación en SQLite al dormir
    private val activeSessionTurns = mutableListOf<Pair<String, String>>()

    // Reconocedor de voz nativo de Google (100% GRATIS, no requiere claves de API)
    private var nativeRecognizer: NativeSpeechRecognizer? = null

    // Gestor de telefonía para detectar llamadas entrantes tipo Alexa
    private var callManager: com.fifo.voicepipeline.telephony.FifoCallManager? = null

    // ── Componentes internos de audio y BLE ──────────
    private val bleClient = BleVoiceClient(
        onAudioReceived = { pcmData ->
            handleIncomingAudio(pcmData, MicSource.ESP32)
        },
        onConnectionChanged = { connected ->
            if (connected) {
                Log.i(TAG, "ESP32-S3 conectado por BLE!")
                _state.value = if (_isAwake.value) PipelineState.IDLE else PipelineState.SLEEPING
                _statusMessage.value = if (_isAwake.value) "Fifo despierto — le escucho" else "Fifo en reposo · Diga 'Fifo' para despertar"
                vad.reset()
                pcmBuffer.clear()
                updateEspDisplay(state = if (_isAwake.value) "LISTO" else "DURMIENDO")
                ensureListeningActive()

                // Registrar ubicación GPS del celular al conectar
                try {
                    val loc = com.fifo.voicepipeline.location.FifoLocationHelper.getCurrentLocation(context)
                    com.fifo.voicepipeline.data.FifoDataRepository.updateDeviceConnectionStatus(
                        connected = true,
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        address = loc.address,
                        roomHint = "En esta habitación",
                        rssi = -60
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Error registrando ubicación al conectar: ${e.message}")
                }
            } else {
                Log.i(TAG, "ESP32-S3 desconectado de BLE")
                _state.value = PipelineState.DISCONNECTED
                _statusMessage.value = "Fifo desconectado · Conecte por Bluetooth"
                vad.reset()
                pcmBuffer.clear()
                ensureListeningActive()

                // Registrar última ubicación GPS conocida del celular al desconectar
                try {
                    val loc = com.fifo.voicepipeline.location.FifoLocationHelper.getCurrentLocation(context)
                    com.fifo.voicepipeline.data.FifoDataRepository.updateDeviceConnectionStatus(
                        connected = false,
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        address = loc.address,
                        roomHint = "Cerca del lugar de desconexión"
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Error registrando ubicación al desconectar: ${e.message}")
                }
            }
        }
    )

    val bleConnectionState: StateFlow<BleConnectionState> = bleClient.connectionState

    private val vad = VadProcessor()
    private val pcmBuffer = PcmBuffer()
    private lateinit var cloudClient: CloudApiClient

    // Registro de habilidades nativas del teléfono y mutaciones de BD
    val skillRegistry = com.fifo.voicepipeline.skills.FifoSkillRegistry(context)

    // Parlante del celular
    private val audioPlayer = AudioPlayer()
    private var androidTtsSpeaker: AndroidTtsSpeaker? = null

    // Micrófono del celular (fallback)
    private val phoneMicRecorder = PhoneMicRecorder(context = context)

    // ── Coroutine scope ─────────────────────────────
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Inicia el pipeline completo:
     * - Conexión Bluetooth automática con el ESP32-S3
     * - Sistema de salida por parlante del celular
     * - Cliente de APIs de nube (Claude Haiku 4.5)
     * - Reconocedor de voz nativo de Google (0 claves requeridas)
     */
    fun start() {
        Log.i(TAG, "Iniciando pipeline de voz FIFO (Groq LPU / Qwen 27B)...")

        val effectiveOpenAiKey = if (openAiApiKey.isNotBlank()) openAiApiKey else if (groqApiKey.startsWith("gsk_")) groqApiKey else ""
        cloudClient = CloudApiClient(
            groqApiKey = groqApiKey,
            anthropicApiKey = anthropicApiKey,
            openAiApiKey = effectiveOpenAiKey
        )

        // Inicializar reproductor de audio del celular
        audioPlayer.initialize()

        // Inicializar TTS nativo de Android para hablar por el parlante
        androidTtsSpeaker = AndroidTtsSpeaker(
            context = context,
            onStart = {
                _state.value = PipelineState.SPEAKING
                _statusMessage.value = "Hablando..."
                updateEspDisplay(state = "HABLANDO")
            },
            onDone = {
                handleTtsFinished(lastSpokenSentence)
            },
            onError = { err ->
                Log.e(TAG, "Error TTS nativo: $err")
                if (!_isMicMuted.value) {
                    ensureListeningActive()
                }
            }
        )

        // Iniciar reconocedor de voz nativo de Google (100% gratuito, sin claves)
        nativeRecognizer = NativeSpeechRecognizer(
            context = context,
            onReady = {
                if (!_isMicMuted.value && _state.value != PipelineState.SPEAKING && _state.value != PipelineState.PROCESSING) {
                    _statusMessage.value = if (_isAwake.value) "Escuchando... Di tu pregunta" else "Fifo durmiendo · Di 'Fifo' para despertar"
                }
            },
            onRmsChanged = { level ->
                if (_isMicMuted.value) {
                    _rmsLevel.value = 0.0
                    return@NativeSpeechRecognizer
                }
                _rmsLevel.value = level.toDouble()
                if (_state.value == PipelineState.LISTENING) {
                    updateEspDisplay(state = "ESCUCHANDO", level = level)
                }
            },
            onPartialResult = { partial ->
                if (_isMicMuted.value) return@NativeSpeechRecognizer

                // Interrupción instantánea y fluida (Barge-in): "Fifo silencio", "Fifo cállate", "Fifo para", "cállate", etc.
                if (_state.value == PipelineState.SPEAKING || androidTtsSpeaker?.isSpeaking == true || audioPlayer.isPlaying) {
                    if (isSilenceCommand(partial, whileSpeaking = true)) {
                        stopSpeakingSilently()
                    }
                    return@NativeSpeechRecognizer
                }

                val hasWakeWord = isWakeWord(partial)
                if (hasWakeWord || oneShotPushedToTalk) {
                    _transcription.value = partial
                    if (_state.value != PipelineState.SPEAKING && _state.value != PipelineState.PROCESSING) {
                        _isAwake.value = true
                        _state.value = PipelineState.LISTENING
                        updateEspDisplay(state = "ESCUCHANDO")
                    }
                }
            },
            onResult = { text ->
                if (_isMicMuted.value) return@NativeSpeechRecognizer

                if (_state.value == PipelineState.SPEAKING || androidTtsSpeaker?.isSpeaking == true || audioPlayer.isPlaying) {
                    if (isSilenceCommand(text, whileSpeaking = true)) {
                        stopSpeakingSilently()
                    }
                    return@NativeSpeechRecognizer
                }

                processUserText(text)
            },
            onError = { code, msg ->
                Log.d(TAG, "SpeechRecognizer: $msg ($code)")
            },
            onRepeatedFailure = {
                if (!_isMicMuted.value) {
                    Log.i(TAG, "Google SpeechRecognizer en pausa/error. Asegurando PhoneMicRecorder activo.")
                    ensureListeningActive()
                }
            }
        )

        // Inicializar gestor de telefonía inteligente tipo Alexa
        callManager = com.fifo.voicepipeline.telephony.FifoCallManager(
            context = context,
            onAnnounceCall = { callerName, _ ->
                scope.launch {
                    _isAwake.value = true
                    _state.value = PipelineState.LISTENING
                    _statusMessage.value = "Llamada de $callerName · Diga 'Fifo contesta' o 'Fifo cuelga'"
                    updateEspDisplay(state = "LLAMADA", transcript = "Llamada entrante", response = callerName)
                    if (!_isMicMuted.value) {
                        ensureCallListeningActive()
                    }
                    speakResponseChunk("¡Lucía! Le está llamando $callerName. Diga 'Fifo contesta' o 'Fifo cuelga'.")
                }
            },
            onCommunicationModeChanged = { inCommMode ->
                Log.i(TAG, "Cambio de modo de comunicación detectado: inCommMode=$inCommMode")
                if (!_isMicMuted.value) {
                    if (inCommMode) {
                        ensureCallListeningActive()
                    } else {
                        onCallEnded()
                    }
                }
            }
        )
        callManager?.startListening()

        // Iniciar captura según disponibilidad
        if (!_isMicMuted.value) {
            ensureListeningActive()
        } else {
            phoneMicRecorder.stop()
            nativeRecognizer?.stop()
            _statusMessage.value = "Micrófono silenciado (Mute)"
            updateEspDisplay(state = "MUTED", transcript = "", response = "")
        }

        // Observar solicitud de beep / alarma sonora para encontrar a Fifo
        scope.launch {
            com.fifo.voicepipeline.data.FifoDataRepository.deviceLocation.collect { devLoc ->
                if (devLoc.isBeeping) {
                    if (bleClient.isConnected) {
                        updateEspDisplay(state = "FIND_ME", transcript = "¡AQUÍ ESTOY!", response = "BEEP", level = 1.0f)
                    }
                    androidTtsSpeaker?.speak("¡Aquí estoy, Lucía! Siga el sonido de mi voz.")
                    kotlinx.coroutines.delay(3500)
                    com.fifo.voicepipeline.data.FifoDataRepository.triggerDeviceBeep(false)
                }
            }
        }

        // Escuchar también llamadas entrantes detectadas por el BroadcastReceiver
        com.fifo.voicepipeline.telephony.FifoPhoneCallReceiver.onIncomingCallDetected = { callerName, _ ->
            scope.launch {
                _isAwake.value = true
                _state.value = PipelineState.LISTENING
                _statusMessage.value = "Llamada de $callerName · Diga 'Fifo contesta' o 'Fifo cuelga'"
                updateEspDisplay(state = "LLAMADA", transcript = "Llamada entrante", response = callerName)
                if (!_isMicMuted.value) {
                    ensureCallListeningActive()
                }
                speakResponseChunk("¡Lucía! Le está llamando $callerName. Diga 'Fifo contesta' o 'Fifo cuelga'.")
            }
        }

        // Observar cambios en llamadas entrantes o en curso para mantener captura con VOICE_COMMUNICATION
        scope.launch {
            com.fifo.voicepipeline.data.FifoDataRepository.incomingCall.collect { callInfo ->
                if (callInfo != null) {
                    if (callInfo.isRinging) {
                        ensureCallListeningActive()
                    } else {
                        _statusMessage.value = "Llamada activa · Di 'Fifo cuelga' para terminar"
                        ensureCallListeningActive()
                    }
                } else {
                    onCallEnded()
                }
            }
        }

        // Iniciar escaneo y conexión BLE con el ESP32-S3
        connectBle()
    }

    /**
     * Intenta escanear y conectar con el ESP32 por BLE.
     */
    fun connectBle() {
        _statusMessage.value = "Buscando Fifo por Bluetooth..."
        bleClient.startScanAndConnect(context)
    }

    /**
     * Cambia la fuente de micrófono entre ESP32 y el celular.
     */
    fun setMicSource(source: MicSource) {
        if (_micSource.value == source) return

        _micSource.value = source
        vad.reset()
        pcmBuffer.clear()

        when (source) {
            MicSource.ESP32 -> {
                phoneMicRecorder.stop()
                _statusMessage.value = if (bleClient.isConnected) {
                    "Usando micrófono de Fifo (ESP32)"
                } else {
                    "Buscando micrófono de Fifo por Bluetooth..."
                }
                Log.i(TAG, "Cambiado a micrófono de ESP32")
            }
            MicSource.PHONE -> {
                ensureListeningActive()
                _statusMessage.value = "Usando micrófono integrado del celular"
                Log.i(TAG, "Cambiado a micrófono del celular")
            }
        }
    }

    private fun startPhoneMic(forceCallSource: Boolean? = null) {
        // Detener nativeRecognizer para que no compitan por el hardware del micrófono
        nativeRecognizer?.stop()
        val started = phoneMicRecorder.start(forceCallSource = forceCallSource) { chunk ->
            handleIncomingAudio(chunk, MicSource.PHONE)
        }
        if (!started) {
            _statusMessage.value = "Permiso de micrófono no concedido o error"
        }
    }

    /**
     * Activa el modo de interacción a través del micrófono del celular.
     * En este modo Fifo NO se activa directamente a escuchar; inicia en reposo (SLEEPING)
     * y requiere que el usuario pronuncie la palabra de activación ("Fifo").
     */
    fun talkFromPhone() {
        if (_isMicMuted.value) {
            setMicMuted(false)
        }
        _isAwake.value = false
        oneShotPushedToTalk = false
        expectFollowUpQuestion = false
        _micSource.value = MicSource.PHONE
        _state.value = PipelineState.SLEEPING
        _statusMessage.value = "Modo celular activo · Di 'Fifo' para despertar"
        autoSleepJob?.cancel()
        vad.reset()
        pcmBuffer.clear()

        ensureListeningActive()
        updateEspDisplay(state = "DURMIENDO", transcript = "", response = "")
    }

    /**
     * Alterna el estado de silencio (mute) del micrófono.
     */
    fun toggleMicMute() {
        setMicMuted(!_isMicMuted.value)
    }

    /**
     * Silencia o activa el micrófono en tiempo real.
     * Cuando está silenciado, desactiva la captura de audio en el celular y en el ESP32,
     * detiene el reconocedor de voz de Google y actualiza la pantalla del robot a MUTED.
     */
    fun setMicMuted(muted: Boolean) {
        _isMicMuted.value = muted
        prefs.edit().putBoolean("is_mic_muted", muted).apply()
        if (muted) {
            Log.i(TAG, "Micrófono SILENCIADO por el usuario")
            // Detener cualquier habla o audio activo inmediatamente
            try {
                androidTtsSpeaker?.stop()
                audioPlayer.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error deteniendo audio al silenciar: ${e.message}")
            }
            nativeRecognizer?.stop()
            phoneMicRecorder.stop()
            pcmBuffer.clear()
            vad.reset()
            _rmsLevel.value = 0.0
            _state.value = PipelineState.SLEEPING
            _statusMessage.value = "Micrófono silenciado (Mute)"
            updateEspDisplay(state = "MUTED", transcript = "", response = "")
        } else {
            Log.i(TAG, "Micrófono REACTIVADO por el usuario")
            _isAwake.value = false
            _state.value = PipelineState.SLEEPING
            vad.reset()
            pcmBuffer.clear()
            autoSleepJob?.cancel()

            ensureListeningActive()
            _statusMessage.value = "Fifo en reposo · Diga 'Fifo' para hablar"
            updateEspDisplay(state = "DURMIENDO", transcript = "", response = "")
        }
    }

    /**
     * Detiene todo el pipeline y libera hardware y recursos.
     */
    fun stop() {
        Log.i(TAG, "Deteniendo pipeline...")
        scope.cancel()
        nativeRecognizer?.release()
        nativeRecognizer = null
        phoneMicRecorder.stop()
        phoneMicRecorder.release()
        audioPlayer.release()
        androidTtsSpeaker?.release()
        androidTtsSpeaker = null
        callManager?.stopListening()
        callManager = null
        bleClient.disconnect()
        _state.value = PipelineState.DISCONNECTED
        _statusMessage.value = "Pipeline detenido"
    }

    /**
     * Garantiza que la escucha esté activa según el estado del sistema:
     * 1. Si el micrófono está silenciado por el usuario (Mute): detiene todo.
     * 2. Si hay llamada telefónica celular o VoIP activa: entra en modo llamada con PhoneMicRecorder.
     * 3. En condiciones normales:
     *    - Si el ESP32 no está conectado, o la fuente es el celular, o tenemos STT de nube (Groq Whisper):
     *      Se activa PhoneMicRecorder para capturar el micrófono del celular y audífonos Bluetooth continuamente.
     *      El VAD filtra el silencio y al detectar voz ("Fifo..."), Whisper lo transcribe y despierta a Fifo.
     *    - De lo contrario, si el ESP32 está conectado vía BLE, el audio proviene directamente del robot.
     */
    fun ensureListeningActive() {
        if (_isMicMuted.value) {
            phoneMicRecorder.stop()
            nativeRecognizer?.stop()
            _rmsLevel.value = 0.0
            return
        }

        val isCallActive = callManager?.isCommunicationModeActive() == true ||
                com.fifo.voicepipeline.data.FifoDataRepository.incomingCall.value != null

        if (isCallActive) {
            ensureCallListeningActive()
            return
        }

        if (hasCloudStt || _micSource.value == MicSource.PHONE || !bleClient.isConnected) {
            nativeRecognizer?.stop()
            if (!phoneMicRecorder.isRecording) {
                startPhoneMic()
            }
        } else {
            phoneMicRecorder.stop()
            if (nativeRecognizer?.isAvailable() == true) {
                nativeRecognizer?.startContinuousListening()
            }
        }
    }

    /**
     * Asegura que la captura de audio esté activa durante llamadas o videollamadas,
     * priorizando VOICE_COMMUNICATION en el micrófono del celular para cancelación de eco.
     * En llamadas, NUNCA usa SpeechRecognizer de Google porque Android lo bloquea con ERROR_AUDIO.
     */
    fun ensureCallListeningActive() {
        if (_isMicMuted.value) return
        if (!_isAwake.value) {
            _state.value = PipelineState.SLEEPING
            updateEspDisplay(state = "DURMIENDO", transcript = "", response = "")
        }
        nativeRecognizer?.stop()
        phoneMicRecorder.switchToCallMode(inCall = true) { chunk ->
            handleIncomingAudio(chunk, MicSource.PHONE)
        }
    }

    /**
     * Se ejecuta al finalizar o colgar una llamada para restablecer el modo normal de captura.
     */
    private fun onCallEnded() {
        expectFollowUpQuestion = false
        val isCommActive = callManager?.isCommunicationModeActive() == true ||
                com.fifo.voicepipeline.data.FifoDataRepository.incomingCall.value != null
        if (isCommActive) {
            ensureCallListeningActive()
            return
        }
        if (!_isMicMuted.value) {
            ensureListeningActive()
        }
        _isAwake.value = false
        _state.value = PipelineState.SLEEPING
        _statusMessage.value = "Fifo en reposo · Diga 'Fifo' para hablar"
        updateEspDisplay(state = "DURMIENDO", transcript = "", response = "")
    }

    /**
     * Determina si el texto reconocido corresponde a un comando de control de llamada
     * (contestar, colgar, rechazar, etc.).
     * Requiere OBLIGATORIAMENTE que el usuario haya dicho 'Fifo'.
     */
    private fun isCallActionCommand(text: String, isRinging: Boolean): Boolean {
        val normalized = java.text.Normalizer.normalize(text.lowercase().trim(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        val hasWakeWord = isWakeWord(normalized)
        // SI O SI se debe decir "Fifo"
        if (!hasWakeWord) return false

        if (isRinging) {
            val answerWords = listOf(
                "contesta", "contestar", "contestame", "atiende", "atender", "atiendeme",
                "acepta", "aceptar", "responder", "responde", "si contesta",
                "toma la llamada", "tomar la llamada"
            )
            val hangupWords = listOf(
                "cuelga", "colgar", "rechaza", "rechazar", "rechazale", "corta", "cortar",
                "no contestes", "no contestar", "deten la llamada", "detener llamada",
                "cancela la llamada", "termina la llamada", "finaliza la llamada"
            )
            val statusWords = listOf(
                "quien llama", "quien es", "quien esta llamando"
            )
            return answerWords.any { normalized.contains(it) } ||
                   hangupWords.any { normalized.contains(it) } ||
                   statusWords.any { normalized.contains(it) }
        } else {
            return normalized.contains("cuelga") || normalized.contains("colgar") ||
                   normalized.contains("corta") || normalized.contains("cortar") ||
                   normalized.contains("termina") || normalized.contains("finaliza") ||
                   normalized.contains("rechaza") || normalized.contains("deten")
        }
    }

    // ═════════════════════════════════════════════════
    //  Procesamiento de Audio Entrante
    // ═════════════════════════════════════════════════

    /**
     * Procesa cada chunk PCM entrante (del ESP32 o del celular).
     */
    private fun handleIncomingAudio(pcmData: ByteArray, source: MicSource) {
        // Si el micrófono está silenciado, ignorar cualquier audio entrante
        if (_isMicMuted.value) return

        val isCallActive = com.fifo.voicepipeline.data.FifoDataRepository.incomingCall.value != null ||
                (callManager?.isCommunicationModeActive() == true)
        // Si no hay llamada activa y el robot BLE está conectado, respetar la fuente configurada.
        // Si hay llamada o el robot NO está conectado, procesar el mic del celular como fallback.
        if (!isCallActive && source != _micSource.value && bleClient.isConnected) return

        val isSpeakingNow = _state.value == PipelineState.SPEAKING ||
            androidTtsSpeaker?.isSpeaking == true || audioPlayer.isPlaying

        // Si Fifo está procesando en la nube (Groq/Claude) y aún no habla, descartar chunks para no superponer turnos
        if (_state.value == PipelineState.PROCESSING && !isSpeakingNow) {
            pcmBuffer.clear()
            vad.reset()
            return
        }

        val result = vad.processChunk(pcmData)
        _rmsLevel.value = result.rms

        // Interrupción instantánea y fluida por voz (Barge-in):
        // Si Fifo está hablando y el usuario empieza a hablar, callar a Fifo de inmediato
        if (isSpeakingNow && (result is VadResult.SpeechStart || result is VadResult.SpeechContinue)) {
            Log.i(TAG, "Barge-in detectado: usuario interrumpió el habla de Fifo (RMS=${result.rms})")
            androidTtsSpeaker?.stop()
            audioPlayer.stop()
            _state.value = PipelineState.LISTENING
            _isAwake.value = true
            _statusMessage.value = "Te escucho..."
            updateEspDisplay(state = "ESCUCHANDO")
        }

        when (result) {
            is VadResult.SpeechStart -> {
                Log.d(TAG, "Voz detectada en $source (RMS: ${result.rms})")
                if (_isAwake.value || oneShotPushedToTalk) {
                    _state.value = PipelineState.LISTENING
                    _statusMessage.value = "Escuchando..."
                    updateEspDisplay(state = "ESCUCHANDO")
                }
                pcmBuffer.clear()
                pcmBuffer.append(pcmData)
            }

            is VadResult.SpeechContinue -> {
                pcmBuffer.append(pcmData)
                if (_isAwake.value || oneShotPushedToTalk) {
                    updateEspDisplay(state = "ESCUCHANDO", level = result.rms.toFloat())
                }
            }

            is VadResult.SpeechPause -> {
                pcmBuffer.append(pcmData)
            }

            is VadResult.SpeechEnd -> {
                Log.i(TAG, "Fin de frase detectado en $source — enviando a Whisper...")
                pcmBuffer.append(pcmData)
                val fullWav = pcmBuffer.toWav()
                pcmBuffer.clear()
                processUserAudio(fullWav)
            }

            is VadResult.Silence -> {
                // Silencio normal, sin acción
            }
        }
    }

    // ═════════════════════════════════════════════════
    //  Pipeline de IA (Whisper + Claude + TTS)
    // ═════════════════════════════════════════════════

    /**
     * Despierta a Fifo manualmente (por ejemplo, al tocar la pantalla).
     */
    fun wakeUpManually() {
        if (_isMicMuted.value) {
            setMicMuted(false)
        }
        _isAwake.value = true
        _state.value = PipelineState.IDLE
        _statusMessage.value = "Fifo despierto — te escucho"
        ensureListeningActive()
        updateEspDisplay(state = "ESCUCHANDO", transcript = "", response = "")
        resetAutoSleepTimer()
    }

    /**
     * Pone a Fifo a dormir en modo espera.
     */
    fun goToSleep() {
        autoSleepJob?.cancel()
        expectFollowUpQuestion = false
        pendingNavigationRequest = false
        oneShotPushedToTalk = false
        _isAwake.value = false
        _state.value = PipelineState.SLEEPING
        _statusMessage.value = if (_isMicMuted.value) "Micrófono silenciado (Mute)" else "Fifo en reposo · Diga 'Fifo' para hablar"
        updateEspDisplay(state = if (_isMicMuted.value) "MUTED" else "DURMIENDO", transcript = "", response = "")

        // Consolidar la información de la sesión en la base de datos relacional SQLite de memoria
        if (activeSessionTurns.isNotEmpty()) {
            val sessionSnapshot = activeSessionTurns.toList()
            activeSessionTurns.clear()
            consolidateSessionIntoMemory(sessionSnapshot)
        }
    }

    /**
     * Reinicia el temporizador de auto-sueño.
     * En modo normal: 25 segundos.
     * En modo de escucha continua ("Fifo, sigue escuchando"): 120 segundos de silencio.
     */
    private fun resetAutoSleepTimer() {
        autoSleepJob?.cancel()
        autoSleepJob = scope.launch {
            val isContinuous = com.fifo.voicepipeline.data.FifoDataRepository.isContinuousListening.value
            val timeoutMs = if (isContinuous) 120000L else 25000L
            delay(timeoutMs)
            if (_isAwake.value && _state.value != PipelineState.SPEAKING && _state.value != PipelineState.PROCESSING) {
                if (com.fifo.voicepipeline.data.FifoDataRepository.isContinuousListening.value) {
                    Log.i(TAG, "2 minutos de silencio en modo continuo: Fifo descansa")
                    com.fifo.voicepipeline.data.FifoDataRepository.setContinuousListening(false)
                }
                Log.i(TAG, "Inactividad: Fifo vuelve a dormir")
                goToSleep()
            }
        }
    }

    /**
     * Temporizador para ventana de respuesta conversacional (14 segundos).
     * Permite que el usuario responda directamente una pregunta de Fifo sin repetir la palabra clave.
     */
    private fun resetFollowUpSleepTimer(timeoutMs: Long = 14000L) {
        autoSleepJob?.cancel()
        autoSleepJob = scope.launch {
            delay(timeoutMs)
            if (_isAwake.value && _state.value != PipelineState.SPEAKING && _state.value != PipelineState.PROCESSING) {
                if (com.fifo.voicepipeline.data.FifoDataRepository.isContinuousListening.value) {
                    com.fifo.voicepipeline.data.FifoDataRepository.setContinuousListening(false)
                }
                Log.i(TAG, "Ventana de respuesta expirada: Fifo vuelve a reposo silencioso")
                goToSleep()
            }
        }
    }

    /**
     * Detecta órdenes de silencio / callarse ("Fifo silencio", "Fifo cállate", "Fifo para", "cállate", "silencio", etc.).
     * Si [whileSpeaking] es true (Fifo está hablando), interrumpe inmediatamente ante cualquier palabra de detención
     * sin exigir la palabra clave "Fifo" para máxima receptividad.
     */
    fun isSilenceCommand(text: String, whileSpeaking: Boolean = false): Boolean {
        if (text.isBlank()) return false
        val normalized = java.text.Normalizer.normalize(text.lowercase().trim(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val stopWords = listOf(
            "silencio", "callate", "cállate", "para", "parate", "basta", "detente",
            "alto", "stop", "shh", "shhh", "no hables", "pausa", "quieto", "parar",
            "corta", "cortala", "calmate", "ya", "apagate", "apágate", "mute"
        )

        val hasStopWord = stopWords.any { word ->
            normalized == word ||
            normalized.contains(Regex("\\b$word\\b")) ||
            normalized.endsWith(" $word") ||
            normalized.startsWith("$word ")
        }

        if (!hasStopWord) return false

        // Si Fifo está hablando o activo despierto, aceptar la interrupción de inmediato
        if (whileSpeaking || _isAwake.value) {
            return true
        }

        // Si está en reposo, verificar que incluya a 'Fifo' o variantes
        val noSpaces = normalized.replace(" ", "")
        val hasFifo = noSpaces.contains("fifo") ||
                Regex("f+i+f+o+").containsMatchIn(noSpaces) ||
                noSpaces.contains("pifo") ||
                Regex("p+i+f+o+").containsMatchIn(noSpaces) ||
                normalized.contains(Regex("\\b(fifo|pifo|feefo|fito|fifa|fibo|fipo|fi fo)\\b"))

        return hasFifo
    }

    /**
     * Detiene inmediatamente la locución de Fifo y lo pone en reposo silencioso.
     */
    fun stopSpeakingSilently() {
        Log.i(TAG, "Deteniendo habla inmediatamente por orden de silencio (Barge-in seamless)")
        androidTtsSpeaker?.stop()
        audioPlayer.stop()
        _state.value = PipelineState.SLEEPING
        _isAwake.value = false
        expectFollowUpQuestion = false
        _statusMessage.value = "Fifo en reposo · Diga 'Fifo' para hablar"
        updateEspDisplay(state = "DURMIENDO", transcript = "", response = "")
        vad.reset()
        pcmBuffer.clear()
        resetAutoSleepTimer()
    }

    /**
     * Lista de palabras clave y aproximaciones fonéticas cuando hay música o ruido de fondo.
     * Soporta 'Fifo', detectando repeticiones ("fi fo", "fifooo") y acentos para máxima sensibilidad.
     */
    private fun isWakeWord(text: String): Boolean {
        if (text.isBlank()) return false
        val normalized = java.text.Normalizer.normalize(text.lowercase().trim(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        val cleaned = normalized.replace(Regex("[^a-z0-9\\s]"), " ").replace(Regex("\\s+"), " ").trim()
        val noSpaces = cleaned.replace(" ", "")

        // 1. Coincidencia directa o comprimida ("fi fo", "fi-fo", "fiiifo", "fifo", "pifo")
        if (noSpaces.contains("fifo") || Regex("f+i+f+o+").containsMatchIn(noSpaces) ||
            noSpaces.contains("pifo") || Regex("p+i+f+o+").containsMatchIn(noSpaces)) {
            return true
        }

        // 2. Variantes fonéticas cuando hay ruido o reconocimiento imperfecto
        val wakeWords = listOf(
            "fifo", "feefo", "fito", "fifa", "fido", "fibo", "fipo", "fico", "fiko",
            "pifo", "pito", "pipo", "bifo", "fifi", "pipa",
            "fee for", "fit for", "people", "free for", "feed for", "bebo", "feefa", "fefa",
            "phifo", "vibo", "vivo", "filo", "fijo", "kiko", "fi fo", "fe fo", "sifo", "be cool"
        )
        return wakeWords.any { word ->
            cleaned.contains(Regex("\\b$word\\b")) ||
            cleaned.startsWith("$word ") ||
            cleaned.endsWith(" $word") ||
            cleaned == word
        }
    }

    /**
     * Extrae la consulta eliminando prefijos de activación como "Fifo", "Hola Fifo", etc.
     */
    private fun extractQuery(text: String): String {
        return text.replace(Regex("(?i)\\b(hola|oye|hey|che|ok|bueno|dime|saludos)\\s+(fifo|pifo|fífo|feefo|fito|pito|fifa|fido|fibo|fipo|fico|fiko|fee\\s+for|fit\\s+for|people|free\\s+for|feed\\s+for|bebo|feefa|fefa|phifo|vibo|vivo|filo|fijo|pipo|kiko|fi\\s+fo)\\b"), "")
            .replace(Regex("(?i)\\b(fifo|pifo|fífo|feefo|fito|pito|fifa|fido|fibo|fipo|fico|fiko|fee\\s+for|fit\\s+for|people|free\\s+for|feed\\s+for|bebo|feefa|fefa|phifo|vibo|vivo|filo|fijo|pipo|kiko|fi\\s+fo)\\b"), "")
            .trim()
            .trimStart(',', '.', ':', ';', '!', '?', ' ')
            .trim()
    }

    /**
     * Identifica órdenes explícitas de despedida o descanso.
     */
    private fun isSleepCommand(textLower: String): Boolean {
        return textLower.contains("duérmete") || textLower.contains("duermete") ||
                textLower.contains("a dormir") || textLower.contains("buenas noches") ||
                textLower.contains("descansa") || textLower.contains("adiós") ||
                textLower.contains("adios") || textLower.contains("hasta luego") ||
                textLower.contains("deja de escuchar") || textLower.contains("ya no escuches") ||
                textLower.contains("apágate") || textLower.contains("apagate") ||
                textLower.contains("no necesito nada") || textLower.contains("no quiero nada") ||
                textLower.contains("eso es todo") || textLower.contains("eso sería todo") ||
                textLower.contains("chao fifo") || textLower.contains("cállate") || textLower.contains("callate")
    }

    /**
     * Genera un saludo dinámico según la hora del día y la continuidad conversacional.
     */
    private fun getDynamicGreeting(): String {
        val now = java.util.Calendar.getInstance()
        val hour = now.get(java.util.Calendar.HOUR_OF_DAY)
        val hasSpokenRecently = activeSessionTurns.isNotEmpty() || cloudClient.hasRecentConversation()
        return when {
            hasSpokenRecently -> {
                listOf(
                    "¡Hola de nuevo, Lucía! Dígame, ¿en qué le puedo colaborar?",
                    "Aquí estoy escuchándole con atención, cuénteme.",
                    "Dígame, Lucía, le escucho.",
                    "Aquí estoy a su disposición, dígame."
                ).random()
            }
            hour in 6..11 -> "¡Buenos días, Lucía! Qué gusto saludarle hoy. ¿En qué le puedo colaborar?"
            hour in 12..19 -> "¡Buenas tardes, Lucía! Aquí estoy para acompañarle. ¿En qué le puedo ayudar?"
            else -> "¡Buenas noches, Lucía! Qué gusto acompañarle a esta hora. ¿En qué le puedo ayudar?"
        }
    }

    /**
     * Permite actualizar dinámicamente la clave para Groq LPU (Cerebro IA principal).
     */
    fun setGroqApiKey(key: String) {
        val trimmed = key.trim()
        groqApiKey = trimmed
        cloudClient.groqApiKey = trimmed
        // Si no había clave Whisper separada o era Groq, sincronizar también para STT
        if (cloudClient.openAiApiKey.isBlank() || cloudClient.openAiApiKey.startsWith("gsk_")) {
            cloudClient.openAiApiKey = trimmed
        }
        Log.i(TAG, "Nueva API key para Groq configurada: ${trimmed.take(12)}...")
    }

    /**
     * Permite actualizar dinámicamente la clave para Claude (Anthropic).
     */
    fun setAnthropicApiKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.startsWith("gsk_")) {
            // El usuario pegó una clave de Groq en el campo general
            setGroqApiKey(trimmed)
            return
        }
        anthropicApiKey = trimmed
        cloudClient.anthropicApiKey = trimmed
        Log.i(TAG, "Nueva API key para Claude configurada: ${trimmed.take(12)}...")
    }

    /**
     * Prueba la conexión con el cerebro de IA configurado (Groq o Claude).
     */
    suspend fun testConnection(overrideKey: String? = null): Pair<Boolean, String> {
        return cloudClient.testConnection(overrideKey)
    }

    /**
     * Prueba la conexión con Claude o Groq.
     */
    suspend fun testClaudeConnection(overrideKey: String? = null): Pair<Boolean, String> {
        return cloudClient.testConnection(overrideKey)
    }

    /**
     * Permite actualizar dinámicamente la clave para STT (OpenAI o Groq) opcional.
     */
    fun setOpenAiApiKey(key: String) {
        cloudClient.openAiApiKey = key.trim()
        Log.i(TAG, "Nueva API key para STT opcional configurada: ${key.take(8)}...")
    }

    /**
     * Permite inyectar texto directamente al pipeline (útil para pruebas en emulador y accesibilidad).
     */
    fun processTextQuery(query: String) {
        if (query.isBlank()) return
        oneShotPushedToTalk = true
        _isAwake.value = true
        processUserText(query)
    }

    /**
     * Procesa texto obtenido por el reconocimiento de voz nativo de Google (100% gratis, sin claves).
     */
    private fun processUserText(rawText: String) {
        if (rawText.isBlank()) return
        Log.i(TAG, "Texto reconocido (Google Voice): $rawText")

        val now = System.currentTimeMillis()
        val textLower = rawText.lowercase().trim()

        // 1. Debounce de 2.5s para evitar responder 2 veces a la misma consulta o callbacks duplicados de Google
        if (textLower == lastProcessedText.trim().lowercase() && (now - lastProcessedTimestamp) < 2500L) {
            Log.d(TAG, "Descartando consulta duplicada recibida en <2.5s: $rawText")
            return
        }

        // Si el usuario ordenó silencio, callar inmediatamente sin importar si se estaba procesando
        if (isSilenceCommand(rawText, whileSpeaking = true)) {
            Log.i(TAG, "Comando de silencio recibido: '$rawText'. Callando a Fifo...")
            stopSpeakingSilently()
            return
        }

        // 2. Candado de procesamiento concurrente: evitar lanzar dos consultas simultáneas
        if (isCurrentlyProcessing || _state.value == PipelineState.PROCESSING) {
            Log.d(TAG, "Descartando texto mientras Fifo procesa otra consulta: $rawText")
            return
        }

        val hasWakeWord = isWakeWord(textLower)
        val isWaitingFollowUp = (expectFollowUpQuestion || pendingNavigationRequest) && _isAwake.value
        val isContinuous = com.fifo.voicepipeline.data.FifoDataRepository.isContinuousListening.value
        val callInfo = com.fifo.voicepipeline.data.FifoDataRepository.incomingCall.value
        val isCallControl = callInfo != null && (hasWakeWord || isWaitingFollowUp) && isCallActionCommand(textLower, isRinging = callInfo.isRinging)
        val wasPushed = oneShotPushedToTalk
        oneShotPushedToTalk = false

        // Se debe decir "Fifo" a menos que estemos en ventana de respuesta activa, modo continuo o botón
        if (!hasWakeWord && !wasPushed && !isWaitingFollowUp && !isContinuous && !isCallControl) {
            Log.d(TAG, "Audio ignorado: Fifo en reposo y no se detectó 'Fifo'. Oído: $rawText")
            return
        }

        lastProcessedText = rawText
        lastProcessedTimestamp = now

        // Si estábamos esperando el destino para navegación:
        if (pendingNavigationRequest) {
            pendingNavigationRequest = false
            expectFollowUpQuestion = false
            val queryClean = extractQuery(rawText)
            val target = queryClean.replace(Regex("(?i)\\b(a|al|a la|a los|a las|hacia|para|ir a|ir al|ir a la)\\b"), "").trim()
                .ifBlank { queryClean }
            _transcription.value = rawText
            _isAwake.value = true
            scope.launch {
                isCurrentlyProcessing = true
                try {
                    _state.value = PipelineState.PROCESSING
                    _statusMessage.value = "Calculando ruta..."
                    val result = skillRegistry.executeSkill("open_navigation_directions", mapOf("destination" to target))
                    if (result.data?.get("needs_destination") == true) {
                        pendingNavigationRequest = true
                        expectFollowUpQuestion = true
                    }
                    val directFeedback = TextSanitizer.cleanForSpeech(result.spokenFeedback)
                    _aiResponse.value = directFeedback
                    _statusMessage.value = "Respondiendo..."
                    cloudClient.recordTurn(rawText, directFeedback)
                    activeSessionTurns.add(rawText to directFeedback)
                    speakResponseChunk(directFeedback)
                    resetAutoSleepTimer()
                } finally {
                    isCurrentlyProcessing = false
                }
            }
            return
        }

        // Si estábamos esperando la pregunta de seguimiento, consumirla
        expectFollowUpQuestion = false

        // Si se dijo "Fifo", o respondió en ventana activa, o se presionó el botón:
        _transcription.value = rawText

        // Si es un comando de control de llamada directo ("Fifo contesta", "Fifo cuelga", etc.):
        if (isCallControl) {
            _isAwake.value = true
            scope.launch {
                isCurrentlyProcessing = true
                try {
                    val directResult = skillRegistry.tryExecuteVoiceIntent(textLower)
                    if (directResult != null) {
                        val directFeedback = TextSanitizer.cleanForSpeech(directResult.spokenFeedback)
                        _aiResponse.value = directFeedback
                        _statusMessage.value = "Controlando llamada..."
                        cloudClient.recordTurn(rawText, directFeedback)
                        activeSessionTurns.add(rawText to directFeedback)
                        speakResponseChunk(directFeedback)
                        resetAutoSleepTimer()
                    }
                } finally {
                    isCurrentlyProcessing = false
                }
            }
            return
        }

        scope.launch {
            isCurrentlyProcessing = true
            try {
                // Verificar comandos de dormir o parar la escucha continua
                if (isSleepCommand(textLower)) {
                    com.fifo.voicepipeline.data.FifoDataRepository.setContinuousListening(false)
                    expectFollowUpQuestion = false
                    pendingNavigationRequest = false
                    speakResponseChunk("Entendido. Me quedo descansando. Si me necesita, solo diga Fifo.")
                    goToSleep()
                    return@launch
                }

                // Despertar a Fifo para atender esta consulta
                _isAwake.value = true
                resetAutoSleepTimer()
                _statusMessage.value = "¡Fifo despierto!"
                updateEspDisplay(state = "ESCUCHANDO", transcript = "", response = "")

                val query = extractQuery(rawText)
                if (query.isBlank() || query.length < 2) {
                    expectFollowUpQuestion = true
                    speakResponseChunk(getDynamicGreeting())
                    return@launch
                }

                consultClaudeAndRespond(query)
            } finally {
                isCurrentlyProcessing = false
            }
        }
    }

    // ═════════════════════════════════════════════════
    //  Pipeline de IA (Whisper + Claude + TTS)
    // ═════════════════════════════════════════════════

    /**
     * Transcribe el audio con Whisper y solicita respuesta a Claude.
     */
    private fun processUserAudio(wavAudio: ByteArray) {
        if (wavAudio.size < 44 + (AudioConfig.SAMPLE_RATE * AudioConfig.BYTES_PER_SAMPLE / 2)) {
            Log.d(TAG, "Audio demasiado corto (${wavAudio.size} bytes), ignorando")
            _state.value = if (_isAwake.value) PipelineState.IDLE else PipelineState.SLEEPING
            updateEspDisplay(state = if (_isAwake.value) "LISTO" else "DURMIENDO")
            return
        }

        scope.launch {
            val isContinuous = com.fifo.voicepipeline.data.FifoDataRepository.isContinuousListening.value
            val isCurrentlyAwake = _isAwake.value || oneShotPushedToTalk || isContinuous || expectFollowUpQuestion
            if (isCurrentlyAwake) {
                _state.value = PipelineState.PROCESSING
                _statusMessage.value = "Pensando..."
                updateEspDisplay(state = "PENSANDO")
            }

            try {
                // Si no hay servicio de STT en la nube (Groq Whisper o OpenAI), no podemos transcribir WAV
                if (!hasCloudStt) {
                    Log.d(TAG, "Audio omitido: no hay clave Whisper/Groq configurada para transcribir audio.")
                    _state.value = if (_isAwake.value) PipelineState.IDLE else PipelineState.SLEEPING
                    updateEspDisplay(state = if (_isAwake.value) "LISTO" else "DURMIENDO")
                    return@launch
                }

                // 1. STT Whisper (Groq LPU o OpenAI) con timeout estricto de 10s
                val transcript = withTimeoutOrNull(10000L) {
                    cloudClient.transcribe(wavAudio)
                } ?: "[TIMEOUT_STT]"

                if (transcript == "[KEY_STT_FALTANTE]") {
                    Log.d(TAG, "Clave STT no presente para Whisper/Groq.")
                    _state.value = if (_isAwake.value) PipelineState.IDLE else PipelineState.SLEEPING
                    updateEspDisplay(state = if (_isAwake.value) "LISTO" else "DURMIENDO")
                    return@launch
                }

                if (transcript == "[TIMEOUT_STT]" || transcript.isBlank() || transcript.startsWith("[") ||
                    CloudApiClient.isWhisperHallucination(transcript)) {
                    Log.d(TAG, "Audio ignorado: transcripción vacía o alucinación de silencio ($transcript)")
                    _state.value = if (_isAwake.value) PipelineState.IDLE else PipelineState.SLEEPING
                    _statusMessage.value = if (_isAwake.value) "Listo — habla cuando quieras" else "Fifo en reposo · Diga 'Fifo' para hablar"
                    updateEspDisplay(state = if (_isAwake.value) "LISTO" else "DURMIENDO")
                    return@launch
                }

                val textLower = transcript.lowercase().trim()
                val hasWakeWord = isWakeWord(textLower)
                val callInfo = com.fifo.voicepipeline.data.FifoDataRepository.incomingCall.value
                val isCommActive = callManager?.isCommunicationModeActive() == true
                val isCallControl = (callInfo != null || isCommActive) && isCallActionCommand(textLower, isRinging = callInfo?.isRinging == true)
                val wasPushed = oneShotPushedToTalk
                oneShotPushedToTalk = false
                val isContinuous = com.fifo.voicepipeline.data.FifoDataRepository.isContinuousListening.value

                val isWaitingFollowUp = (expectFollowUpQuestion || pendingNavigationRequest) && _isAwake.value

                // Si no se dijo "Fifo", verificar si estábamos en conversación activa, pregunta pendiente, o modo continuo
                if (!hasWakeWord && !wasPushed && !isWaitingFollowUp && !isContinuous && !isCallControl) {
                    Log.d(TAG, "Audio ignorado: Fifo en reposo y no se detectó 'Fifo'. Oído: $transcript")
                    _state.value = PipelineState.SLEEPING
                    updateEspDisplay(state = "DURMIENDO")
                    return@launch
                }

                if (pendingNavigationRequest) {
                    pendingNavigationRequest = false
                    expectFollowUpQuestion = false
                    val queryClean = extractQuery(transcript)
                    val target = queryClean.replace(Regex("(?i)\\b(a|al|a la|a los|a las|hacia|para|ir a|ir al|ir a la)\\b"), "").trim()
                        .ifBlank { queryClean }
                    _transcription.value = transcript
                    _isAwake.value = true
                    _state.value = PipelineState.PROCESSING
                    _statusMessage.value = "Calculando ruta..."
                    val result = skillRegistry.executeSkill("open_navigation_directions", mapOf("destination" to target))
                    if (result.data?.get("needs_destination") == true) {
                        pendingNavigationRequest = true
                        expectFollowUpQuestion = true
                    }
                    val directFeedback = TextSanitizer.cleanForSpeech(result.spokenFeedback)
                    _aiResponse.value = directFeedback
                    _statusMessage.value = "Respondiendo..."
                    speakResponseChunk(directFeedback)
                    resetAutoSleepTimer()
                    return@launch
                }

                expectFollowUpQuestion = false
                _transcription.value = transcript

                // Si el usuario ordenó silencio ("Fifo silencio", "Fifo cállate", "Fifo para", etc.), callar inmediatamente
                if (isSilenceCommand(transcript)) {
                    Log.i(TAG, "Comando de silencio recibido vía Whisper: '$transcript'. Callando a Fifo...")
                    stopSpeakingSilently()
                    return@launch
                }

                // Si es un comando de llamada telefónica activo:
                if (isCallControl) {
                    _isAwake.value = true
                    val directResult = skillRegistry.tryExecuteVoiceIntent(textLower)
                    if (directResult != null) {
                        val directFeedback = TextSanitizer.cleanForSpeech(directResult.spokenFeedback)
                        _aiResponse.value = directFeedback
                        _statusMessage.value = "Controlando llamada..."
                        speakResponseChunk(directFeedback)
                        resetAutoSleepTimer()
                        return@launch
                    }
                }

                if (isSleepCommand(textLower)) {
                    com.fifo.voicepipeline.data.FifoDataRepository.setContinuousListening(false)
                    expectFollowUpQuestion = false
                    pendingNavigationRequest = false
                    speakResponseChunk("Entendido. Me quedo descansando. Si me necesita, solo diga Fifo.")
                    goToSleep()
                    return@launch
                }

                _isAwake.value = true
                _state.value = PipelineState.PROCESSING
                resetAutoSleepTimer()
                _statusMessage.value = "Pensando..."
                updateEspDisplay(state = "PENSANDO")

                val query = extractQuery(transcript)
                if (query.isBlank() || query.length < 2) {
                    expectFollowUpQuestion = true
                    speakResponseChunk(getDynamicGreeting())
                    return@launch
                }

                consultClaudeAndRespond(query)

            } catch (e: Exception) {
                Log.e(TAG, "Error en pipeline: ${e.message}", e)
                _state.value = if (_isAwake.value) PipelineState.IDLE else PipelineState.SLEEPING
                _statusMessage.value = "Disculpa, ocurrió un error. Intenta de nuevo."
                updateEspDisplay(state = if (_isAwake.value) "LISTO" else "DURMIENDO")
            }
        }
    }

    /**
     * Consulta a Claude y reproduce la respuesta mediante voz,
     * ejecutando herramientas nativas del teléfono y mutaciones de BD mediante [skillRegistry].
     */
    private suspend fun consultClaudeAndRespond(query: String) {
        // 1. Verificación de intención directa por voz (respuesta ultra-rápida sin latencia)
        val directResult = skillRegistry.tryExecuteVoiceIntent(query)
        if (directResult != null) {
            Log.i(TAG, "Skill ejecutada directamente por intención de voz: ${directResult.spokenFeedback}")
            if (directResult.data?.get("needs_destination") == true) {
                pendingNavigationRequest = true
                expectFollowUpQuestion = true
            }
            val directFeedback = TextSanitizer.cleanForSpeech(directResult.spokenFeedback)
            _aiResponse.value = directFeedback
            _statusMessage.value = "Respondiendo..."
            cloudClient.recordTurn(query, directFeedback)
            activeSessionTurns.add(query to directFeedback)
            speakResponseChunk(directFeedback)
            resetAutoSleepTimer()
            return
        }

        // 2. Si no es un comando directo, consultar al cerebro de IA (Groq LPU / Claude)
        _state.value = PipelineState.PROCESSING
        val brainName = if (cloudClient.isGroqActive) "Groq (Qwen 27B)" else "Claude"
        _statusMessage.value = "Consultando a $brainName..."
        updateEspDisplay(state = "PENSANDO", transcript = query)

        val reply = withTimeoutOrNull(20000L) {
            cloudClient.chat(query, skillRegistry)
        } ?: "Disculpe, la respuesta de $brainName tardó demasiado tiempo. Por favor consulte de nuevo."

        val cleanReply = TextSanitizer.cleanForSpeech(reply)
        _aiResponse.value = cleanReply
        _statusMessage.value = "Respondiendo..."
        activeSessionTurns.add(query to cleanReply)
        speakResponseChunk(cleanReply)
        resetAutoSleepTimer()
    }

    /**
     * Reproduce un fragmento de texto por el parlante del celular.
     */
    private fun speakResponseChunk(text: String) {
        val cleanText = TextSanitizer.cleanForSpeech(text)
        if (cleanText.isBlank()) return
        lastSpokenSentence = cleanText

        // Detener nativeRecognizer mientras Fifo habla para evitar ruidos de reconocimiento erráticos
        nativeRecognizer?.stop()

        scope.launch {
            _state.value = PipelineState.SPEAKING
            _statusMessage.value = "Hablando..."
            updateEspDisplay(state = "HABLANDO", response = cleanText)

            val openAiKey = cloudClient.openAiApiKey
            if (openAiKey.isNotEmpty() && !openAiKey.startsWith("gsk_")) {
                val pcmAudio = withTimeoutOrNull(8000L) {
                    cloudClient.textToSpeech(cleanText)
                }
                if (pcmAudio != null) {
                    audioPlayer.write(pcmAudio)
                    handleTtsFinished(cleanText)
                } else {
                    androidTtsSpeaker?.speak(cleanText)
                }
            } else {
                androidTtsSpeaker?.speak(cleanText)
            }
        }
    }

    /**
     * Consolida los turnos de la sesión de conversación finalizada en SQLite (memoria persistente).
     */
    private fun consolidateSessionIntoMemory(turns: List<Pair<String, String>>) {
        if (turns.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "Iniciando consolidación de memoria de sesión (${turns.size} turnos)...")
                val consolidation = cloudClient.extractSessionMemory(turns) ?: return@launch

                val isoTimestamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.format(java.util.Date())

                // 1. Guardar fragmento compacto en SQLite (para contexto futuro en LLM)
                val fragment = com.fifo.voicepipeline.ui.model.ConversationFragment(
                    id = "frag_${System.currentTimeMillis()}",
                    serverConversationId = "srv_${System.currentTimeMillis()}",
                    keyTopics = consolidation.keyTopics.ifEmpty { listOf(consolidation.primaryTag) },
                    namedEntities = consolidation.namedEntities,
                    detectedMood = consolidation.detectedMood,
                    compactSummary = consolidation.compactSummary.ifBlank { "Charla sobre ${consolidation.primaryTag}" },
                    primaryTag = consolidation.primaryTag,
                    durationSeconds = (turns.size * 20).coerceAtLeast(30),
                    recordedAt = isoTimestamp
                )
                com.fifo.voicepipeline.data.FifoDataRepository.addConversationFragment(fragment)

                // 2. Guardar en past_conversations (Charlas y Recuerdos)
                val pastConv = com.fifo.voicepipeline.ui.model.PastConversationItem(
                    id = "conv_${System.currentTimeMillis()}",
                    title = consolidation.conversationTitle.ifBlank { "Charla sobre ${consolidation.primaryTag}" },
                    date = "Hoy",
                    duration = "${(turns.size * 20).coerceAtLeast(30)} seg",
                    summary = consolidation.compactSummary,
                    tag = consolidation.primaryTag,
                    iconName = when (consolidation.primaryTag.lowercase()) {
                        "deporte", "deportes", "ejercicio", "jiujitsu" -> "air"
                        "familia", "amigos" -> "heart"
                        "comida", "cocina" -> "restaurant"
                        "música", "musica" -> "music"
                        else -> "chat"
                    }
                )
                com.fifo.voicepipeline.data.FifoDataRepository.addConversation(pastConv)

                // 3. Guardar en memories si se detectó un recuerdo valioso
                if (!consolidation.memoryTitle.isNullOrBlank() && !consolidation.memoryDetail.isNullOrBlank()) {
                    com.fifo.voicepipeline.data.FifoDataRepository.addMemory(
                        emoji = consolidation.memoryEmoji ?: "⭐",
                        title = consolidation.memoryTitle,
                        detail = consolidation.memoryDetail
                    )
                }

                // 4. Guardar gusto si se descubrió un nuevo interés del usuario
                if (!consolidation.newTaste.isNullOrBlank()) {
                    com.fifo.voicepipeline.data.FifoDataRepository.addTaste(consolidation.newTaste.trim())
                }
                Log.i(TAG, "Consolidación de memoria completada y persistida en SQLite exitosamente!")
            } catch (e: Exception) {
                Log.e(TAG, "Error consolidando memoria de la sesión: ${e.message}", e)
            }
        }
    }

    /**
     * Maneja la transición de estado cuando termina de hablar por parlante (Android TTS u OpenAI).
     */
    private fun handleTtsFinished(spokenText: String) {
        if (_state.value != PipelineState.SPEAKING) return

        val incomingCall = com.fifo.voicepipeline.data.FifoDataRepository.incomingCall.value
        val isCommActive = callManager?.isCommunicationModeActive() == true
        if (incomingCall != null || isCommActive) {
            _isAwake.value = true
            _state.value = PipelineState.LISTENING
            _statusMessage.value = if (incomingCall?.isRinging == true) {
                "Llamada de ${incomingCall.callerName} · Diga 'contesta' o 'cuelga'"
            } else {
                "Llamada activa · Diga 'Fifo cuelga' para terminar"
            }
            updateEspDisplay(state = "LLAMADA")
            ensureCallListeningActive()
            return
        }

        // Si se usó temporalmente el micrófono del teléfono, volver al micrófono de Fifo solo si el ESP32 está conectado
        if (_micSource.value == MicSource.PHONE && bleClient.isConnected) {
            phoneMicRecorder.stop()
            _micSource.value = MicSource.ESP32
        }

        // Si el robot BLE no está conectado pero tenemos STT en la nube (Groq Whisper) o modo celular,
        // no cortar la escucha: continuar escuchando por el micrófono del celular sin apagar a Fifo.
        if (!bleClient.isConnected && !hasCloudStt && _micSource.value == MicSource.ESP32) {
            _state.value = PipelineState.DISCONNECTED
            _statusMessage.value = "Fifo desconectado · Conecte por Bluetooth"
            return
        }

        val isContinuous = com.fifo.voicepipeline.data.FifoDataRepository.isContinuousListening.value
        val endsWithQuestion = spokenText.trim().endsWith("?") || spokenText.contains("¿")
        val shouldStayAwake = isContinuous || expectFollowUpQuestion || pendingNavigationRequest || endsWithQuestion

        if (shouldStayAwake) {
            expectFollowUpQuestion = true
            _isAwake.value = true
            _state.value = PipelineState.LISTENING
            _statusMessage.value = if (isContinuous) {
                "Escucha continua activa — hable cuando guste"
            } else {
                "Le escucho... Puede responder directamente"
            }
            updateEspDisplay(state = "ESCUCHANDO")
            resetFollowUpSleepTimer(timeoutMs = if (isContinuous) 120000L else 14000L)
        } else {
            _isAwake.value = false
            _state.value = PipelineState.SLEEPING
            _statusMessage.value = if (_isMicMuted.value) {
                "Micrófono silenciado (Mute)"
            } else {
                "Fifo en reposo · Diga 'Fifo' para hablar"
            }
            updateEspDisplay(state = if (_isMicMuted.value) "MUTED" else "DURMIENDO")
        }

        pcmBuffer.clear()
        vad.reset()

        if (!_isMicMuted.value) {
            ensureListeningActive()
        }
    }

    /**
     * Envía actualizaciones a la pantalla OLED 1.3" del ESP32 vía Bluetooth LE.
     */
    private fun updateEspDisplay(
        state: String? = null,
        transcript: String? = null,
        response: String? = null,
        level: Float = 0f
    ) {
        if (!bleClient.isConnected) return

        try {
            bleClient.sendDisplayUpdate(
                state = state ?: "",
                userText = transcript?.take(60) ?: "",
                aiText = response?.take(100) ?: "",
                audioLevel = level
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error enviando datos a pantalla OLED del ESP32: ${e.message}")
        }
    }

    /** Limpia el historial de conversación del agente. */
    fun clearConversation() {
        cloudClient.clearHistory()
        _transcription.value = ""
        _aiResponse.value = ""
        _statusMessage.value = "Conversación limpiada"
        updateEspDisplay(state = if (_isAwake.value) "LISTO" else "DURMIENDO", transcript = "", response = "")
    }
}
