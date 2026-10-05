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

    private val _isMicMuted = MutableStateFlow(false)
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()

    // Temporizador de auto-sueño para Fifo (25 segundos de inactividad)
    private var autoSleepJob: Job? = null

    // Control estricto de activación por palabra clave ("Fifo")
    private var expectFollowUpQuestion = false
    private var oneShotPushedToTalk = false

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
    private val phoneMicRecorder = PhoneMicRecorder()

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
        Log.i(TAG, "Iniciando pipeline de voz FIFO (Groq LPU / GPT-OSS 120B)...")

        cloudClient = CloudApiClient(
            groqApiKey = groqApiKey,
            anthropicApiKey = anthropicApiKey,
            openAiApiKey = openAiApiKey
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
                if (_state.value == PipelineState.SPEAKING) {
                    // Si se usó temporalmente el micrófono del teléfono, volver al micrófono de Fifo
                    if (_micSource.value == MicSource.PHONE && bleClient.isConnected) {
                        phoneMicRecorder.stop()
                        _micSource.value = MicSource.ESP32
                    }

                    if (!bleClient.isConnected && _micSource.value == MicSource.ESP32) {
                        _state.value = PipelineState.DISCONNECTED
                        _statusMessage.value = "Fifo desconectado · Conecte por Bluetooth"
                    } else if (com.fifo.voicepipeline.data.FifoDataRepository.isContinuousListening.value) {
                        _state.value = PipelineState.LISTENING
                        _statusMessage.value = "Escucha continua activa — hable cuando guste"
                    } else if (expectFollowUpQuestion) {
                        _isAwake.value = true
                        _state.value = PipelineState.LISTENING
                        _statusMessage.value = "Le escucho... ¿En qué le puedo ayudar?"
                    } else {
                        _isAwake.value = false
                        _state.value = PipelineState.SLEEPING
                        _statusMessage.value = if (_isMicMuted.value) {
                            "Micrófono silenciado (Mute)"
                        } else {
                            "Fifo en reposo · Diga 'Fifo' para hablar"
                        }
                    }
                    updateEspDisplay(
                        state = if (_isMicMuted.value) "MUTED"
                        else if (com.fifo.voicepipeline.data.FifoDataRepository.isContinuousListening.value || expectFollowUpQuestion) "ESCUCHANDO"
                        else "DURMIENDO"
                    )
                    vad.reset()

                    // Reanudar la escucha de voz nativa tras terminar de hablar solo si no está muteado
                    if (!_isMicMuted.value) {
                        nativeRecognizer?.startContinuousListening()
                    }
                }
            },
            onError = { err ->
                Log.e(TAG, "Error TTS nativo: $err")
                if (!_isMicMuted.value) {
                    nativeRecognizer?.startContinuousListening()
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
                val isContinuous = com.fifo.voicepipeline.data.FifoDataRepository.isContinuousListening.value
                val hasWakeWord = isWakeWord(partial)
                if (isContinuous || hasWakeWord || expectFollowUpQuestion || oneShotPushedToTalk) {
                    _transcription.value = partial
                    if (_state.value != PipelineState.SPEAKING && _state.value != PipelineState.PROCESSING) {
                        _state.value = PipelineState.LISTENING
                        updateEspDisplay(state = "ESCUCHANDO")
                    }
                }
            },
            onResult = { text ->
                if (_isMicMuted.value) return@NativeSpeechRecognizer
                processUserText(text)
            },
            onError = { code, msg ->
                Log.d(TAG, "SpeechRecognizer: $msg ($code)")
            }
        )

        // Iniciar escucha continua de voz si no está silenciado
        if (!_isMicMuted.value) {
            nativeRecognizer?.startContinuousListening()
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

        // Inicializar gestor de telefonía inteligente tipo Alexa
        callManager = com.fifo.voicepipeline.telephony.FifoCallManager(
            context = context,
            onAnnounceCall = { callerName, _ ->
                scope.launch {
                    _isAwake.value = true
                    _isMicMuted.value = false
                    _state.value = PipelineState.LISTENING
                    _statusMessage.value = "Llamada de $callerName"
                    updateEspDisplay(state = "LLAMADA", transcript = "Llamada entrante", response = callerName)
                    speakResponseChunk("¡Lucía! Le está llamando $callerName. ¿Desea que conteste o que cuelgue la llamada?")
                }
            }
        )
        callManager?.startListening()

        // Escuchar también llamadas entrantes detectadas por el BroadcastReceiver
        com.fifo.voicepipeline.telephony.FifoPhoneCallReceiver.onIncomingCallDetected = { callerName, _ ->
            scope.launch {
                _isAwake.value = true
                _isMicMuted.value = false
                _state.value = PipelineState.LISTENING
                _statusMessage.value = "Llamada de $callerName"
                updateEspDisplay(state = "LLAMADA", transcript = "Llamada entrante", response = callerName)
                speakResponseChunk("¡Lucía! Le está llamando $callerName. ¿Desea que conteste o que cuelgue la llamada?")
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
                startPhoneMic()
                _statusMessage.value = "Usando micrófono integrado del celular"
                Log.i(TAG, "Cambiado a micrófono del celular (Fallback activo)")
            }
        }
    }

    private fun startPhoneMic() {
        val started = phoneMicRecorder.start { chunk ->
            handleIncomingAudio(chunk, MicSource.PHONE)
        }
        if (!started) {
            _statusMessage.value = "Permiso de micrófono no concedido o error"
        }
    }

    /**
     * Activa el modo de interacción a través del micrófono del celular.
     * En este modo Fifo NO se activa directamente a escuchar; inicia en reposo (SLEEPING)
     * y requiere estrictamente que el usuario pronuncie la palabra de activación ("Fifo").
     */
    fun talkFromPhone() {
        if (_isMicMuted.value) {
            _isMicMuted.value = false
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
        
        // Iniciar captura de audio para VAD + Groq Whisper
        startPhoneMic()
        // Iniciar también reconocedor nativo
        nativeRecognizer?.startContinuousListening()
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
            _isAwake.value = true
            _state.value = PipelineState.LISTENING
            vad.reset()
            pcmBuffer.clear()
            resetAutoSleepTimer()

            // Detener AudioRecord por si estaba activo para que SpeechRecognizer tenga acceso limpio al mic
            phoneMicRecorder.stop()
            nativeRecognizer?.startContinuousListening()

            _statusMessage.value = "Fifo despierto — te escucho"
            updateEspDisplay(state = "ESCUCHANDO", transcript = "", response = "")
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

    // ═════════════════════════════════════════════════
    //  Procesamiento de Audio Entrante
    // ═════════════════════════════════════════════════

    /**
     * Procesa cada chunk PCM entrante (del ESP32 o del celular).
     */
    private fun handleIncomingAudio(pcmData: ByteArray, source: MicSource) {
        // Si el micrófono está silenciado, ignorar cualquier audio entrante
        if (_isMicMuted.value) return

        // Ignorar audio si no coincide con la fuente activa
        if (source != _micSource.value) return

        // Supresión de eco: si el celular está hablando o procesando, silenciar micrófono
        if (_state.value == PipelineState.SPEAKING) return
        if (_state.value == PipelineState.PROCESSING) return

        val result = vad.processChunk(pcmData)
        _rmsLevel.value = result.rms

        when (result) {
            is VadResult.SpeechStart -> {
                Log.d(TAG, "Voz detectada en $source (RMS: ${result.rms})")
                _state.value = PipelineState.LISTENING
                _statusMessage.value = "Escuchando..."
                updateEspDisplay(state = "ESCUCHANDO")
                pcmBuffer.clear()
                pcmBuffer.append(pcmData)
            }

            is VadResult.SpeechContinue -> {
                pcmBuffer.append(pcmData)
                // Enviar nivel de audio a la pantalla OLED para la barra animada
                updateEspDisplay(state = "ESCUCHANDO", level = result.rms.toFloat())
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
        _isMicMuted.value = false
        _isAwake.value = true
        _state.value = PipelineState.IDLE
        _statusMessage.value = "Fifo despierto — te escucho"
        nativeRecognizer?.startContinuousListening()
        updateEspDisplay(state = "ESCUCHANDO", transcript = "", response = "")
        resetAutoSleepTimer()
    }

    /**
     * Pone a Fifo a dormir en modo espera.
     */
    fun goToSleep() {
        autoSleepJob?.cancel()
        expectFollowUpQuestion = false
        oneShotPushedToTalk = false
        _isAwake.value = false
        _state.value = PipelineState.SLEEPING
        _statusMessage.value = if (_isMicMuted.value) "Micrófono silenciado (Mute)" else "Fifo en reposo · Diga 'Fifo' para hablar"
        updateEspDisplay(state = if (_isMicMuted.value) "MUTED" else "DURMIENDO", transcript = "", response = "")
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
     * Lista de palabras clave y aproximaciones fonéticas cuando hay música o ruido de fondo.
     */
    private fun isWakeWord(text: String): Boolean {
        val lower = text.lowercase().trim()
        val wakeWords = listOf(
            "fifo", "feefo", "fito", "fifa", "fido", "fio",
            "fee for", "fit for", "people", "free for", "feed for", "bebo", "feefa", "fefa", "phifo", "vibo", "vivo", "filo", "fijo", "pipo", "kiko"
        )
        return wakeWords.any { word ->
            lower.contains(Regex("\\b$word\\b")) ||
            lower.startsWith("$word ") ||
            lower.endsWith(" $word") ||
            lower == word
        }
    }

    /**
     * Extrae la consulta eliminando prefijos de activación como "Fifo", "Hola Fifo", "Fifa", etc.
     */
    private fun extractQuery(text: String): String {
        return text.replace(Regex("(?i)\\b(hola|oye|hey|che|ok|bueno|dime|saludos)\\s+(fifo|feefo|fito|fifa|fido|fio|fee\\s+for|fit\\s+for|people|free\\s+for|feed\\s+for|bebo|feefa|fefa|phifo|vibo|vivo|filo|fijo|pipo|kiko)\\b"), "")
            .replace(Regex("(?i)\\b(fifo|feefo|fito|fifa|fido|fio|fee\\s+for|fit\\s+for|people|free\\s+for|feed\\s+for|bebo|feefa|fefa|phifo|vibo|vivo|filo|fijo|pipo|kiko)\\b"), "")
            .trim()
            .trimStart(',', '.', ':', ';', '!', '?', ' ')
            .trim()
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
        _transcription.value = query
        scope.launch {
            _isMicMuted.value = false
            _isAwake.value = true
            val queryClean = extractQuery(query).ifBlank { query }
            consultClaudeAndRespond(queryClean)
        }
    }

    /**
     * Procesa texto obtenido por el reconocimiento de voz nativo de Google (100% gratis, sin claves).
     */
    private fun processUserText(rawText: String) {
        if (rawText.isBlank()) return
        Log.i(TAG, "Texto reconocido (Google Voice): $rawText")

        val textLower = rawText.lowercase().trim()
        val hasWakeWord = isWakeWord(textLower)
        val isContinuous = com.fifo.voicepipeline.data.FifoDataRepository.isContinuousListening.value
        val wasPushed = oneShotPushedToTalk
        oneShotPushedToTalk = false

        // Si no está en modo continuo, no se dijo "Fifo", no se esperaba pregunta de seguimiento y no se presionó el botón:
        if (!isContinuous && !hasWakeWord && !expectFollowUpQuestion && !wasPushed) {
            Log.d(TAG, "Audio ignorado: Fifo en reposo y no se dijo 'Fifo'. Oído: $rawText")
            // No actualizar _transcription ni hablar, mantener a Fifo en reposo
            return
        }

        // Si estábamos esperando la pregunta de seguimiento, consumirla
        expectFollowUpQuestion = false

        // Si se dijo "Fifo" o está en modo de escucha continua / seguimiento:
        _transcription.value = rawText

        scope.launch {
            // Verificar comandos de dormir o parar la escucha continua
            val isSleepCmd = textLower.contains("duérmete") || textLower.contains("a dormir") ||
                    textLower.contains("buenas noches") || textLower.contains("descansa") ||
                    textLower.contains("adiós") || textLower.contains("hasta luego") ||
                    textLower.contains("deja de escuchar") || textLower.contains("ya no escuches")

            if (isSleepCmd) {
                com.fifo.voicepipeline.data.FifoDataRepository.setContinuousListening(false)
                speakResponseChunk("Hasta luego, que tenga un excelente descanso.")
                goToSleep()
                return@launch
            }

            // Despertar a Fifo para atender esta consulta
            _isMicMuted.value = false
            _isAwake.value = true
            resetAutoSleepTimer()
            _statusMessage.value = "¡Fifo despierto!"
            updateEspDisplay(state = "ESCUCHANDO", transcript = "", response = "")

            val query = extractQuery(rawText)
            if (query.isBlank() || query.length < 2) {
                // El usuario solo dijo "Fifo" o "Hola Fifo", saludamos y esperamos su pregunta
                expectFollowUpQuestion = true
                speakResponseChunk("¡Hola! Qué alegría saludarle. ¿En qué le puedo ayudar?")
                return@launch
            }

            consultClaudeAndRespond(query)
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
            _state.value = PipelineState.PROCESSING
            _statusMessage.value = "Pensando..."
            updateEspDisplay(state = "PENSANDO")

            try {
                // Si no hay clave de Whisper/Groq, no insistir con voz de error
                if (cloudClient.openAiApiKey.isBlank()) {
                    Log.d(TAG, "Audio de ESP32 omitido: no hay clave Whisper configurada. Se usa reconocimiento de voz nativo de Google.")
                    _state.value = if (_isAwake.value) PipelineState.IDLE else PipelineState.SLEEPING
                    updateEspDisplay(state = if (_isAwake.value) "LISTO" else "DURMIENDO")
                    return@launch
                }

                // 1. STT Whisper con timeout estricto de 10s
                val transcript = withTimeoutOrNull(10000L) {
                    cloudClient.transcribe(wavAudio)
                } ?: "[TIMEOUT_STT]"

                if (transcript == "[KEY_STT_FALTANTE]") {
                    Log.d(TAG, "Clave STT no presente para ESP32.")
                    _state.value = if (_isAwake.value) PipelineState.IDLE else PipelineState.SLEEPING
                    updateEspDisplay(state = if (_isAwake.value) "LISTO" else "DURMIENDO")
                    return@launch
                }

                if (transcript == "[TIMEOUT_STT]" || transcript.isBlank() || transcript.startsWith("[")) {
                    Log.w(TAG, "Audio no comprendido o error: $transcript")
                    _state.value = if (_isAwake.value) PipelineState.IDLE else PipelineState.SLEEPING
                    _statusMessage.value = if (_isAwake.value) "Listo — habla cuando quieras" else "Fifo durmiendo · Di 'Fifo'"
                    updateEspDisplay(state = if (_isAwake.value) "LISTO" else "DURMIENDO")
                    return@launch
                }

                val textLower = transcript.lowercase().trim()
                val hasWakeWord = isWakeWord(textLower)
                val isContinuous = com.fifo.voicepipeline.data.FifoDataRepository.isContinuousListening.value

                if (!isContinuous && !hasWakeWord && !expectFollowUpQuestion) {
                    Log.d(TAG, "Audio ignorado (ESP32): Fifo en reposo y no se detectó 'Fifo'. Oído: $transcript")
                    _state.value = PipelineState.SLEEPING
                    updateEspDisplay(state = "DURMIENDO")
                    return@launch
                }

                expectFollowUpQuestion = false
                _transcription.value = transcript

                val isSleepCmd = textLower.contains("duérmete") || textLower.contains("a dormir") ||
                        textLower.contains("buenas noches") || textLower.contains("descansa") ||
                        textLower.contains("adiós") || textLower.contains("hasta luego") ||
                        textLower.contains("deja de escuchar") || textLower.contains("ya no escuches")

                if (isSleepCmd) {
                    com.fifo.voicepipeline.data.FifoDataRepository.setContinuousListening(false)
                    speakResponseChunk("Hasta luego, que tenga un excelente descanso.")
                    goToSleep()
                    return@launch
                }

                _isMicMuted.value = false
                _isAwake.value = true
                resetAutoSleepTimer()
                _statusMessage.value = "¡Fifo despierto!"
                updateEspDisplay(state = "ESCUCHANDO")

                val query = extractQuery(transcript)
                if (query.isBlank() || query.length < 2) {
                    expectFollowUpQuestion = true
                    speakResponseChunk("¡Hola! Qué alegría saludarle. ¿En qué le puedo ayudar?")
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
            val directFeedback = TextSanitizer.cleanForSpeech(directResult.spokenFeedback)
            _aiResponse.value = directFeedback
            _statusMessage.value = "Respondiendo..."
            speakResponseChunk(directFeedback)
            resetAutoSleepTimer()
            return
        }

        // 2. Si no es un comando directo, consultar al cerebro de IA (Groq LPU / Claude)
        _state.value = PipelineState.PROCESSING
        val brainName = if (cloudClient.isGroqActive) "Groq (GPT-OSS 120B)" else "Claude"
        _statusMessage.value = "Consultando a $brainName..."
        updateEspDisplay(state = "PENSANDO", transcript = query)

        val reply = withTimeoutOrNull(15000L) {
            cloudClient.chat(query, skillRegistry)
        } ?: "Disculpa, la respuesta de $brainName tardó demasiado tiempo. Intenta de nuevo."

        val cleanReply = TextSanitizer.cleanForSpeech(reply)
        _aiResponse.value = cleanReply
        _statusMessage.value = "Respondiendo..."
        speakResponseChunk(cleanReply)
        resetAutoSleepTimer()
    }

    /**
     * Reproduce un fragmento de texto por el parlante del celular.
     */
    private fun speakResponseChunk(text: String) {
        val cleanText = TextSanitizer.cleanForSpeech(text)
        if (cleanText.isBlank()) return

        // Pausar escucha continua para evitar que Fifo escuche su propia voz (eco)
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
                    if (_state.value == PipelineState.SPEAKING) {
                        val isContinuous = com.fifo.voicepipeline.data.FifoDataRepository.isContinuousListening.value
                        if (isContinuous) {
                            _state.value = PipelineState.LISTENING
                            _statusMessage.value = "Escucha continua activa — hable cuando guste"
                        } else if (expectFollowUpQuestion) {
                            _isAwake.value = true
                            _state.value = PipelineState.LISTENING
                            _statusMessage.value = "Le escucho... ¿En qué le puedo ayudar?"
                        } else {
                            _isAwake.value = false
                            _state.value = PipelineState.SLEEPING
                            _statusMessage.value = if (_isMicMuted.value) {
                                "Micrófono silenciado (Mute)"
                            } else {
                                "Fifo en reposo · Diga 'Fifo' para hablar"
                            }
                        }
                        updateEspDisplay(
                            state = if (_isMicMuted.value) "MUTED"
                            else if (isContinuous || expectFollowUpQuestion) "ESCUCHANDO"
                            else "DURMIENDO"
                        )
                        vad.reset()
                        if (!_isMicMuted.value) {
                            nativeRecognizer?.startContinuousListening()
                        }
                    }
                } else {
                    androidTtsSpeaker?.speak(cleanText)
                }
            } else {
                androidTtsSpeaker?.speak(cleanText)
            }
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
