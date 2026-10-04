package com.fifo.voicepipeline

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.fifo.voicepipeline.network.BleConnectionState
import com.fifo.voicepipeline.pipeline.MicSource
import com.fifo.voicepipeline.pipeline.VoicePipelineManager
import com.fifo.voicepipeline.ui.MainScreen

import com.fifo.voicepipeline.service.FifoVoiceService

/**
 * Activity principal de FIFO Voice Pipeline (100% BLE).
 *
 * Características:
 * - Ejecución continua 24/7 en segundo plano mediante Foreground Service y WakeLock.
 * - Modo reposo (DURMIENDO): Fifo descansa y solo se despierta al oír la palabra clave "FIFO".
 * - Conexión directa y automática por Bluetooth Low Energy (BLE) con ESP32-S3-N16R8.
 * - Streaming de audio analógico UCC en tiempo real vía notificaciones BLE.
 * - Pantalla OLED SH1106 1.3" I2C sincronizada en tiempo real con rostro animado.
 * - Salida de voz y respuestas de Claude por el parlante del celular.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val DEFAULT_ANTHROPIC_KEY = ""
        private const val DEFAULT_GEMINI_KEY = ""
    }

    private lateinit var pipeline: VoicePipelineManager

    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pipeline.talkFromPhone()
            Toast.makeText(this, "Escuchando por el celular...", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this,
                "Se requiere permiso de audio para hablar desde el celular",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Toast.makeText(this, "Buscando robot FIFO por Bluetooth...", Toast.LENGTH_SHORT).show()
            pipeline.connectBle()
        } else {
            Toast.makeText(
                this,
                "Se requieren permisos de Bluetooth para conectar con FIFO",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── Iniciar Foreground Service para ejecución continua 24/7 de forma segura ──
        try {
            FifoVoiceService.start(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo iniciar FifoVoiceService: ${e.message}")
        }

        // ── Solicitar ejecución sin restricciones de batería para escucha continua ──
        checkBatteryOptimizations()

        // ── Obtener API keys desde SharedPreferences o BuildConfig ────
        val prefs = getSharedPreferences("fifo_prefs", MODE_PRIVATE)
        val savedClaudeKey = prefs.getString("anthropic_api_key", "") ?: ""
        val anthropicKey = savedClaudeKey.ifEmpty {
            BuildConfig.ANTHROPIC_API_KEY.ifEmpty { DEFAULT_ANTHROPIC_KEY }
        }

        val savedGeminiKey = prefs.getString("gemini_api_key", "") ?: ""
        val geminiKey = savedGeminiKey.ifEmpty {
            BuildConfig.GEMINI_API_KEY.ifEmpty { DEFAULT_GEMINI_KEY }
        }

        val savedSttKey = prefs.getString("stt_api_key", "") ?: ""
        val openAiKey = savedSttKey.ifEmpty { BuildConfig.OPENAI_API_KEY }

        // ── Inicializar pipeline ────────────────────
        pipeline = VoicePipelineManager(
            context = applicationContext,
            initialAnthropicApiKey = anthropicKey,
            initialOpenAiApiKey = openAiKey,
            initialGeminiApiKey = geminiKey
        )

        // ── UI ──────────────────────────────────────
        setContent {
            val state by pipeline.state.collectAsState()
            val isAwake by pipeline.isAwake.collectAsState()
            val micSource by pipeline.micSource.collectAsState()
            val status by pipeline.statusMessage.collectAsState()
            val transcription by pipeline.transcription.collectAsState()
            val aiResponse by pipeline.aiResponse.collectAsState()
            val rmsLevel by pipeline.rmsLevel.collectAsState()

            val bleState by pipeline.bleConnectionState.collectAsState()
            val isBleConnected = bleState is BleConnectionState.Connected
            val isBleConnecting = bleState is BleConnectionState.Connecting || bleState is BleConnectionState.Scanning
            val isMicMuted by pipeline.isMicMuted.collectAsState()

            var currentClaudeKey by remember { mutableStateOf(anthropicKey) }
            var currentGeminiKey by remember { mutableStateOf(geminiKey) }

            MainScreen(
                state = state,
                micSource = micSource,
                statusMessage = status,
                transcription = transcription,
                aiResponse = aiResponse,
                rmsLevel = rmsLevel,
                isBleConnected = isBleConnected,
                isBleConnecting = isBleConnecting,
                isAwake = isAwake,
                isMicMuted = isMicMuted,
                onToggleMicMute = { pipeline.toggleMicMute() },
                onWakeUp = { pipeline.wakeUpManually() },
                onSleep = { pipeline.goToSleep() },
                currentClaudeKey = currentClaudeKey,
                onSaveClaudeKey = { newKey ->
                    val trimmed = newKey.trim()
                    if (trimmed.startsWith("AIza") || trimmed.startsWith("AQ.")) {
                        prefs.edit().putString("gemini_api_key", trimmed).apply()
                        currentGeminiKey = trimmed
                        pipeline.setGeminiApiKey(trimmed)
                        Toast.makeText(this, "Clave de Google Gemini guardada y activada", Toast.LENGTH_SHORT).show()
                    } else {
                        prefs.edit().putString("anthropic_api_key", trimmed).apply()
                        currentClaudeKey = trimmed
                        pipeline.setAnthropicApiKey(trimmed)
                        Toast.makeText(this, "Clave de Claude guardada y activada", Toast.LENGTH_SHORT).show()
                    }
                },
                onTestClaudeKey = { keyToTest ->
                    pipeline.testClaudeConnection(keyToTest)
                },
                currentGeminiKey = currentGeminiKey,
                onSaveGeminiKey = { newKey ->
                    val trimmed = newKey.trim()
                    prefs.edit().putString("gemini_api_key", trimmed).apply()
                    currentGeminiKey = trimmed
                    pipeline.setGeminiApiKey(trimmed)
                    Toast.makeText(this, "Clave de Google Gemini guardada y activada", Toast.LENGTH_SHORT).show()
                },
                onTestGeminiKey = { keyToTest ->
                    pipeline.testGeminiConnection(keyToTest)
                },
                onConnectBle = {
                    checkAndRequestBlePermissions()
                },
                onTalkFromPhone = {
                    talkFromPhone()
                },
                onClearConversation = { pipeline.clearConversation() }
            )
        }

        // Solicitar permisos de Bluetooth y conectar al iniciar la app
        checkAndRequestBlePermissions()
    }

    private fun checkAndRequestBlePermissions() {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        // Permiso GPS para "¿Dónde estamos?", navegación Maps/Waze y rastreo de última ubicación de Fifo
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        // Permisos de telefonía y contactos para gestión de llamadas tipo Alexa por voz
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_PHONE_STATE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_CONTACTS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ANSWER_PHONE_CALLS)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isEmpty()) {
            pipeline.connectBle()
        } else {
            blePermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun talkFromPhone() {
        val permission = Manifest.permission.RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            pipeline.talkFromPhone()
        } else {
            recordAudioPermissionLauncher.launch(permission)
        }
    }

    private fun checkBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val pm = getSystemService(POWER_SERVICE) as? android.os.PowerManager
                if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    ).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo solicitar ejecución sin restricciones de batería: ${e.message}")
            }
        }
    }

    override fun onStart() {
        super.onStart()
        pipeline.start()
    }

    override fun onStop() {
        super.onStop()
        // NO detener el pipeline: Fifo se mantiene ejecutándose en segundo plano (24/7)
        // gracias a FifoVoiceService con WakeLock parcial y BLE activo para escuchar "FIFO".
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            pipeline.stop()
            FifoVoiceService.stop(applicationContext)
        }
    }
}
