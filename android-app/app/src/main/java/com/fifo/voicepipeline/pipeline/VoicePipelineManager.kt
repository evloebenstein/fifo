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
    initialAnthropicApiKey: String,
    initialOpenAiApiKey: String = ""
) {
    companion object {
        private const val TAG = "VoicePipeline"
    }

    var anthropicApiKey: String = initialAnthropicApiKey
        private set

    var openAiApiKey: String = initialOpenAiApiKey
        private set

    // ── Estado observable (para la UI) ──────────────
    private val _state = MutableStateFlow(PipelineState.DISCONNECTED)
    val state: StateFlow<PipelineState> = _state.asStateFlow()

    private val _isAwake = MutableStateFlow(false)
    val isAwake: StateFlow<Boolean> = _isAwake.asStateFlow()

    private val _micSource = MutableStateFlow(MicSource.PHONE)
    val micSource: StateFlow<MicSource> = _micSource.asStateFlow()

    private val _transcription = MutableStateFlow("")
    val transcription: StateFlow<String> = _transcription.asStateFlow()

    private val _aiResponse = MutableStateFlow("")
    val aiResponse: StateFlow<String> = _aiResponse.asStateFlow()

    private val _statusMessage = MutableStateFlow("Iniciando Bluetooth...")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _rmsLevel = MutableStateFlow(0.0)
    val rmsLevel: StateFlow<Double> = _rmsLevel.asStateFlow()

    // Temporizador de auto-sueño para Fifo (25 segundos de inactividad)
    private var autoSleepJob: Job? = null

    // Reconocedor de voz nativo de Google (100% GRATIS, no requiere claves de API)
    private var nativeRecognizer: NativeSpeechRecognizer? = null

    // ── Componentes internos de audio y BLE ──────────
    private val bleClient = BleVoiceClient(
        onAudioReceived = { pcmData ->
            handleIncomingAudio(pcmData, MicSource.ESP32)
        },
        onConnectionChanged = { connected ->
            if (connected) {
                Log.i(TAG, "ESP32-S3 conectado por BLE!")
                if (_state.value == PipelineState.DISCONNECTED) {
                    _state.value = if (_isAwake.value) PipelineState.IDLE else PipelineState.SLEEPING
                }
                _statusMessage.value = if (_isAwake.value) "Fifo despierto — te escucho" else "Fifo durmiendo · Di 'Fifo' para despertar"
                vad.reset()
                pcmBuffer.clear()
                updateEspDisplay(state = if (_isAwake.value) "LISTO" else "DURMIENDO")
            } else {
                Log.i(TAG, "ESP32-S3 desconectado de BLE")
                _statusMessage.value = "Fifo desconectado — reconectando por Bluetooth..."
                vad.reset()
                pcmBuffer.clear()
            }
        }
    )

    val bleConnectionState: StateFlow<BleConnectionState> = bleClient.connectionState

    private val vad = VadProcessor()
    private val pcmBuffer = PcmBuffer()
    private lateinit var cloudClient: CloudApiClient

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
        Log.i(TAG, "Iniciando pipeline de voz FIFO (100% BLE + Claude)...")

        cloudClient = CloudApiClient(
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
                    _state.value = if (_isAwake.value) PipelineState.IDLE else PipelineState.SLEEPING
                    _statusMessage.value = if (_isAwake.value) "Listo — habla cuando quieras" else "Fifo durmiendo · Di 'Fifo' para despertar"
                    updateEspDisplay(state = if (_isAwake.value) "LISTO" else "DURMIENDO")
                    vad.reset()

                    // Reanudar la escucha de voz nativa tras terminar de hablar
                    nativeRecognizer?.startContinuousListening()
                }
            },
            onError = { err ->
                Log.e(TAG, "Error TTS nativo: $err")
                nativeRecognizer?.startContinuousListening()
            }
        )

        // Iniciar reconocedor de voz nativo de Google (100% gratuito, sin claves)
        nativeRecognizer = NativeSpeechRecognizer(
            context = context,
            onReady = {
                if (_state.value != PipelineState.SPEAKING && _state.value != PipelineState.PROCESSING) {
                    _statusMessage.value = if (_isAwake.value) "Escuchando... Di tu pregunta" else "Fifo durmiendo · Di 'Fifo' para despertar"
                }
            },
            onRmsChanged = { level ->
                _rmsLevel.value = level.toDouble()
                if (_state.value == PipelineState.LISTENING) {
                    updateEspDisplay(state = "ESCUCHANDO", level = level)
                }
            },
            onPartialResult = { partial ->
                _transcription.value = partial
                if (_isAwake.value && _state.value != PipelineState.SPEAKING && _state.value != PipelineState.PROCESSING) {
                    _state.value = PipelineState.LISTENING
                    updateEspDisplay(state = "ESCUCHANDO")
                }
            },
            onResult = { text ->
                processUserText(text)
            },
            onError = { code, msg ->
                Log.d(TAG, "SpeechRecognizer: $msg ($code)")
            }
        )

        // Iniciar escucha continua de voz
        nativeRecognizer?.startContinuousListening()

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
        _isAwake.value = true
        _state.value = PipelineState.IDLE
        _statusMessage.value = "Fifo despierto — te escucho"
        updateEspDisplay(state = "ESCUCHANDO")
        resetAutoSleepTimer()
    }

    /**
     * Pone a Fifo a dormir en modo espera.
     */
    fun goToSleep() {
        autoSleepJob?.cancel()
        _isAwake.value = false
        _state.value = PipelineState.SLEEPING
        _statusMessage.value = "Fifo durmiendo · Di 'Fifo' para despertar"
        updateEspDisplay(state = "DURMIENDO")
    }

    /**
     * Reinicia el temporizador de auto-sueño tras 25 segundos sin actividad.
     */
    private fun resetAutoSleepTimer() {
        autoSleepJob?.cancel()
        autoSleepJob = scope.launch {
            delay(25000L)
            if (_isAwake.value && _state.value != PipelineState.SPEAKING && _state.value != PipelineState.PROCESSING) {
                Log.i(TAG, "25 segundos de inactividad: Fifo vuelve a dormir")
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
            "fifo", "fifa", "fito", "feefo", "fido", "vivo", "filo", "fijo",
            "pipo", "kiko", "sifo", "fio", "chifo", "tito", "hipo"
        )
        return wakeWords.any { word ->
            lower.contains(Regex("\\b$word\\b")) ||
            (lower.length <= 6 && lower.contains(word)) ||
            lower.startsWith(word) || lower.endsWith(word)
        }
    }

    /**
     * Extrae la consulta eliminando prefijos de activación como "Fifo", "Hola Fifo", "Fifa", etc.
     */
    private fun extractQuery(text: String): String {
        return text.replace(Regex("(?i)\\b(hola|oye|hey|che|ok|bueno|dime|saludos)\\s+(fifo|fifa|fito|feefo|fido|vivo|filo|fijo|pipo|kiko|sifo|fio|tito|hipo)\\b"), "")
            .replace(Regex("(?i)\\b(fifo|fifa|fito|feefo|fido|vivo|filo|fijo|pipo|kiko|sifo|fio|tito|hipo)\\b"), "")
            .trim()
            .trimStart(',', '.', ':', ';', '!', '?', ' ')
            .trim()
    }

    /**
     * Permite actualizar dinámicamente la clave para Claude (Anthropic).
     */
    fun setAnthropicApiKey(key: String) {
        val trimmed = key.trim()
        anthropicApiKey = trimmed
        cloudClient.anthropicApiKey = trimmed
        Log.i(TAG, "Nueva API key para Claude configurada: ${trimmed.take(12)}...")
    }

    /**
     * Prueba la conexión con Claude con la clave actual o una clave específica.
     */
    suspend fun testClaudeConnection(overrideKey: String? = null): Pair<Boolean, String> {
        return cloudClient.testAnthropicConnection(overrideKey)
    }

    /**
     * Permite actualizar dinámicamente la clave para STT (OpenAI o Groq) opcional.
     */
    fun setOpenAiApiKey(key: String) {
        cloudClient.openAiApiKey = key.trim()
        Log.i(TAG, "Nueva API key para STT opcional configurada: ${key.take(8)}...")
    }

    /**
     * Procesa texto obtenido por el reconocimiento de voz nativo de Google (100% gratis, sin claves).
     */
    private fun processUserText(rawText: String) {
        if (rawText.isBlank()) return
        Log.i(TAG, "Texto reconocido (Google Voice): $rawText")
        _transcription.value = rawText

        val textLower = rawText.lowercase().trim()
        val hasWakeWord = isWakeWord(textLower)

        scope.launch {
            if (!_isAwake.value) {
                // Fifo está durmiendo: SOLO despierta si dijeron FIFO (o variante acústica con música de fondo)
                if (!hasWakeWord) {
                    Log.d(TAG, "Audio ignorado: Fifo durmiendo y no se oyó 'Fifo' (o variante). Oído: $rawText")
                    _state.value = PipelineState.SLEEPING
                    updateEspDisplay(state = "DURMIENDO")
                    return@launch
                }

                // Despertar a Fifo
                _isAwake.value = true
                resetAutoSleepTimer()
                _statusMessage.value = "¡Fifo despierto!"
                updateEspDisplay(state = "ESCUCHANDO")

                val query = extractQuery(rawText)
                if (query.isBlank() || query.length < 3) {
                    speakResponseChunk("¡Hola! Qué alegría saludarle. ¿Cómo se encuentra hoy?")
                    return@launch
                }

                consultClaudeAndRespond(query)
            } else {
                // Fifo ya estaba despierto
                resetAutoSleepTimer()
                val isSleepCmd = textLower.contains("duérmete") || textLower.contains("a dormir") ||
                        textLower.contains("buenas noches") || textLower.contains("descansa") ||
                        textLower.contains("adiós") || textLower.contains("hasta luego")

                if (isSleepCmd) {
                    speakResponseChunk("Hasta luego, que tenga un excelente descanso.")
                    goToSleep()
                    return@launch
                }

                val query = extractQuery(rawText).ifBlank { rawText }
                consultClaudeAndRespond(query)
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

                _transcription.value = transcript
                val textLower = transcript.lowercase().trim()
                val hasWakeWord = isWakeWord(textLower)

                if (!_isAwake.value) {
                    // Fifo estaba durmiendo: SOLO despierta si dijeron FIFO (o variante acústica con música)
                    if (!hasWakeWord) {
                        Log.d(TAG, "Audio ignorado: Fifo está durmiendo y no se detectó 'Fifo'. Oído: $transcript")
                        _state.value = PipelineState.SLEEPING
                        updateEspDisplay(state = "DURMIENDO")
                        return@launch
                    }

                    // ¡Despertar a Fifo!
                    _isAwake.value = true
                    resetAutoSleepTimer()
                    _statusMessage.value = "¡Fifo despierto!"
                    updateEspDisplay(state = "ESCUCHANDO")

                    val query = extractQuery(transcript)
                    if (query.isBlank() || query.length < 3) {
                        speakResponseChunk("¡Hola! Qué alegría saludarle. ¿Cómo se encuentra hoy?")
                        return@launch
                    }

                    // Pregunta formulada en la misma frase ("Fifo, ¿qué hora es?")
                    consultClaudeAndRespond(query)
                } else {
                    // Fifo ya estaba despierto
                    resetAutoSleepTimer()
                    val isSleepCmd = textLower.contains("duérmete") || textLower.contains("a dormir") ||
                            textLower.contains("buenas noches") || textLower.contains("descansa") ||
                            textLower.contains("adiós") || textLower.contains("hasta luego")

                    if (isSleepCmd) {
                        speakResponseChunk("Hasta luego, que tenga un excelente descanso.")
                        goToSleep()
                        return@launch
                    }

                    val query = extractQuery(transcript).ifBlank { transcript }
                    consultClaudeAndRespond(query)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error en pipeline: ${e.message}", e)
                _state.value = if (_isAwake.value) PipelineState.IDLE else PipelineState.SLEEPING
                _statusMessage.value = "Disculpa, ocurrió un error. Intenta de nuevo."
                updateEspDisplay(state = if (_isAwake.value) "LISTO" else "DURMIENDO")
            }
        }
    }

    /**
     * Consulta a Claude y reproduce la respuesta mediante voz.
     */
    private suspend fun consultClaudeAndRespond(query: String) {
        _state.value = PipelineState.PROCESSING
        _statusMessage.value = "Consultando a Claude..."
        updateEspDisplay(state = "PENSANDO", transcript = query)

        val reply = withTimeoutOrNull(15000L) {
            cloudClient.chat(query)
        } ?: "Disculpa, la respuesta de Claude tardó demasiado tiempo. Intenta de nuevo."

        _aiResponse.value = reply
        _statusMessage.value = "Respondiendo..."
        speakResponseChunk(reply)
        resetAutoSleepTimer()
    }

    /**
     * Reproduce un fragmento de texto por el parlante del celular.
     */
    private fun speakResponseChunk(text: String) {
        if (text.isBlank()) return

        // Pausar escucha continua para evitar que Fifo escuche su propia voz (eco)
        nativeRecognizer?.stop()

        scope.launch {
            _state.value = PipelineState.SPEAKING
            _statusMessage.value = "Hablando..."
            updateEspDisplay(state = "HABLANDO", response = text)

            val openAiKey = cloudClient.openAiApiKey
            if (openAiKey.isNotEmpty() && !openAiKey.startsWith("gsk_")) {
                val pcmAudio = withTimeoutOrNull(8000L) {
                    cloudClient.textToSpeech(text)
                }
                if (pcmAudio != null) {
                    audioPlayer.write(pcmAudio)
                    if (_state.value == PipelineState.SPEAKING) {
                        _state.value = if (_isAwake.value) PipelineState.IDLE else PipelineState.SLEEPING
                        _statusMessage.value = if (_isAwake.value) "Listo — habla cuando quieras" else "Fifo durmiendo · Di 'Fifo'"
                        updateEspDisplay(state = if (_isAwake.value) "LISTO" else "DURMIENDO")
                        vad.reset()
                        nativeRecognizer?.startContinuousListening()
                    }
                } else {
                    androidTtsSpeaker?.speak(text)
                }
            } else {
                androidTtsSpeaker?.speak(text)
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
