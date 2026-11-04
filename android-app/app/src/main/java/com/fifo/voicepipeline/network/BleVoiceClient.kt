package com.fifo.voicepipeline.network

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import com.fifo.voicepipeline.data.model.RobotGpsFix

sealed class BleConnectionState {
    object Disconnected : BleConnectionState()
    object Scanning : BleConnectionState()
    object Connecting : BleConnectionState()
    object Connected : BleConnectionState()
    data class Error(val message: String) : BleConnectionState()
}

/**
 * Gestos del pulsador físico del robot (característica ff14).
 *
 * El firmware solo clasifica el gesto; qué hacer con él lo decide el celular.
 */
enum class FifoButtonEvent {
    SHORT_PRESS,
    LONG_PRESS;

    companion object {
        fun fromCode(code: Int): FifoButtonEvent? = when (code) {
            1 -> SHORT_PRESS
            2 -> LONG_PRESS
            else -> null
        }
    }
}

/**
 * Cliente BLE para conectar el celular Android con la XIAO ESP32-S3 de FIFO.
 * - Recibe paquetes de audio PCM 16kHz del micrófono INMP441 en tiempo real vía NOTIFY.
 * - Envía estados y texto a la pantalla OLED 0.96" I2C del ESP32 vía WRITE.
 * - Envía la voz del asistente (PCM 8kHz) al MAX98357A vía WRITE.
 * - Recibe los gestos del pulsador y la posición del GNSS vía NOTIFY.
 * - Soporta reconexión automática y negociación de MTU 512.
 */
class BleVoiceClient(
    private val onAudioReceived: (ByteArray) -> Unit,
    private val onConnectionChanged: (Boolean) -> Unit,
    private val onButtonEvent: (FifoButtonEvent) -> Unit = {},
    private val onRobotFix: (RobotGpsFix) -> Unit = {}
) {
    companion object {
        private const val TAG = "BleVoiceClient"

        val FIFO_SERVICE_UUID: UUID = UUID.fromString("0000ff10-0000-1000-8000-00805f9b34fb")
        val AUDIO_CHAR_UUID: UUID   = UUID.fromString("0000ff11-0000-1000-8000-00805f9b34fb")
        val DISPLAY_CHAR_UUID: UUID = UUID.fromString("0000ff12-0000-1000-8000-00805f9b34fb")
        val SPEAKER_CHAR_UUID: UUID = UUID.fromString("0000ff13-0000-1000-8000-00805f9b34fb")
        val BUTTON_CHAR_UUID: UUID  = UUID.fromString("0000ff14-0000-1000-8000-00805f9b34fb")
        val GPS_CHAR_UUID: UUID     = UUID.fromString("0000ff15-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID         = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Opcodes del canal de parlante (ff13). */
        private const val SPEAKER_OP_FLUSH: Byte = 0x00
        private const val SPEAKER_OP_PCM: Byte = 0x01

        private const val DEVICE_NAME = "FIFO-S3"
        private const val SCAN_TIMEOUT_MS = 15000L
    }

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    val isConnected: Boolean
        get() = _connectionState.value is BleConnectionState.Connected

    private var bluetoothGatt: BluetoothGatt? = null
    private var displayChar: BluetoothGattCharacteristic? = null
    @Volatile
    private var speakerChar: BluetoothGattCharacteristic? = null
    private var isScanning = false
    private var scanCallback: ScanCallback? = null

    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("MissingPermission")
    fun startScanAndConnect(context: Context) {
        if (isConnected || isScanning) return

        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter

        if (adapter == null || !adapter.isEnabled) {
            _connectionState.value = BleConnectionState.Error("Bluetooth desactivado en el celular")
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            _connectionState.value = BleConnectionState.Error("Escáner BLE no disponible")
            return
        }

        _connectionState.value = BleConnectionState.Scanning
        isScanning = true
        Log.i(TAG, "Iniciando escaneo BLE buscando '$DEVICE_NAME' o ServiceUUID '$FIFO_SERVICE_UUID'...")

        val timeoutRunnable = Runnable {
            if (isScanning) {
                stopScan(scanner)
                if (_connectionState.value is BleConnectionState.Scanning) {
                    _connectionState.value = BleConnectionState.Error("No se encontró el ESP32. Verifica que esté encendido")
                }
            }
        }
        handler.postDelayed(timeoutRunnable, SCAN_TIMEOUT_MS)

        // Escaneamos con filtro de Service UUID + fallback por nombre
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(FIFO_SERVICE_UUID)).build()
        )

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val device = result?.device ?: return
                val name = device.name ?: result.scanRecord?.deviceName ?: ""

                val matchesUuid = result.scanRecord?.serviceUuids?.any { it.uuid == FIFO_SERVICE_UUID } == true
                val matchesName = name.contains("FIFO", ignoreCase = true)

                if (matchesUuid || matchesName) {
                    Log.i(TAG, "ESP32 encontrado! Dispositivo: ${device.address} (${name})")
                    handler.removeCallbacks(timeoutRunnable)
                    stopScan(scanner)
                    connectToDevice(context, device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Fallo al escanear BLE. Código de error: $errorCode")
                isScanning = false
                scanCallback = null
                handler.removeCallbacks(timeoutRunnable)
                _connectionState.value = BleConnectionState.Error("Error de escáner BLE ($errorCode)")
            }
        }

        scanCallback = callback
        try {
            // Intentamos escanear con filtro; si la plataforma no lo soporta, escaneamos sin filtro
            scanner.startScan(filters, settings, callback)
        } catch (e: Exception) {
            try {
                scanner.startScan(null, settings, callback)
            } catch (ex: Exception) {
                isScanning = false
                scanCallback = null
                handler.removeCallbacks(timeoutRunnable)
                _connectionState.value = BleConnectionState.Error("No se pudo iniciar escaneo: ${ex.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan(scanner: android.bluetooth.le.BluetoothLeScanner?) {
        isScanning = false
        scanCallback?.let {
            try {
                scanner?.stopScan(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopScan: ${e.message}")
            }
        }
        scanCallback = null
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(context: Context, device: BluetoothDevice) {
        _connectionState.value = BleConnectionState.Connecting
        Log.i(TAG, "Conectando a GATT de ${device.address}...")

        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "GATT Conectado! Negociando MTU y prioridad alta de conexión...")
                // Prioridad alta reduce la latencia a 11.25ms - 15ms
                gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                // MTU 517 para transferir bloques PCM completos sin fragmentación
                gatt?.requestMtu(517)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "GATT Desconectado")
                _connectionState.value = BleConnectionState.Disconnected
                displayChar = null
                speakerChar = null
                onConnectionChanged(false)
                try {
                    gatt?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error cerrando gatt: ${e.message}")
                }
                bluetoothGatt = null
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            Log.i(TAG, "MTU negociada con éxito: $mtu bytes (status: $status). Descubriendo servicios...")
            gatt?.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) {
                _connectionState.value = BleConnectionState.Error("Error descubriendo servicios GATT ($status)")
                return
            }

            val service = gatt.getService(FIFO_SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "Servicio FIFO no encontrado en el ESP32")
                _connectionState.value = BleConnectionState.Error("Servicio FIFO no encontrado en ESP32")
                return
            }

            val audioChar = service.getCharacteristic(AUDIO_CHAR_UUID)
            displayChar = service.getCharacteristic(DISPLAY_CHAR_UUID)
            speakerChar = service.getCharacteristic(SPEAKER_CHAR_UUID)
            val buttonChar = service.getCharacteristic(BUTTON_CHAR_UUID)

            if (audioChar == null) {
                Log.e(TAG, "Característica de audio no encontrada")
                _connectionState.value = BleConnectionState.Error("Característica de audio no encontrada")
                return
            }

            // Habilitar notificaciones locales en Android
            gatt.setCharacteristicNotification(audioChar, true)

            // Escribir en el descriptor CCCD del ESP32 para activar notificaciones por hardware
            val cccd = audioChar.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(cccd)
                }
                Log.i(TAG, "Descriptor CCCD configurado para recibir stream de audio")
            } else {
                Log.w(TAG, "Descriptor CCCD no encontrado, notificaciones pueden no emitirse")
            }

            // El pulsador es opcional: un firmware viejo no lo expone y Fifo sigue vivo.
            if (buttonChar != null) {
                gatt.setCharacteristicNotification(buttonChar, true)
                val buttonCccd = buttonChar.getDescriptor(CCCD_UUID)
                if (buttonCccd != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(buttonCccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        buttonCccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(buttonCccd)
                    }
                }
            } else {
                Log.w(TAG, "El firmware no expone la característica del pulsador")
            }

            if (speakerChar == null) {
                Log.w(TAG, "El firmware no expone la característica de parlante; la voz seguirá en el celular")
            }

            val gpsChar = service.getCharacteristic(GPS_CHAR_UUID)
            if (gpsChar != null) {
                gatt.setCharacteristicNotification(gpsChar, true)
                val gpsCcd = gpsChar.getDescriptor(CCCD_UUID)
                if (gpsCcd != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(gpsCcd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        gpsCcd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(gpsCcd)
                    }
                    Log.i(TAG, "Descriptor CCCD configurado para recibir datos de GPS")
                }
            } else {
                Log.w(TAG, "El firmware no expone la característica de GPS")
            }

            _connectionState.value = BleConnectionState.Connected
            onConnectionChanged(true)
            Log.i(TAG, "¡ESP32-S3 completamente enlazado por BLE! Audio y pantalla listos.")
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            when (characteristic?.uuid) {
                AUDIO_CHAR_UUID -> {
                    @Suppress("DEPRECATION")
                    val data = characteristic.value ?: return
                    onAudioReceived(data)
                }
                BUTTON_CHAR_UUID -> {
                    @Suppress("DEPRECATION")
                    val code = characteristic.value?.firstOrNull()?.toInt() ?: return
                    FifoButtonEvent.fromCode(code and 0xFF)?.let(onButtonEvent)
                }
                GPS_CHAR_UUID -> {
                    @Suppress("DEPRECATION")
                    val bytes = characteristic.value ?: return
                    RobotGpsParser.parse(
                        String(bytes, Charsets.UTF_8),
                        System.currentTimeMillis()
                    )?.let(onRobotFix)
                }
            }
        }

        // Android 13+ (API 33)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            when (characteristic.uuid) {
                AUDIO_CHAR_UUID -> onAudioReceived(value)
                BUTTON_CHAR_UUID -> {
                    val code = value.firstOrNull()?.toInt() ?: return
                    FifoButtonEvent.fromCode(code and 0xFF)?.let(onButtonEvent)
                }
                GPS_CHAR_UUID -> {
                    RobotGpsParser.parse(
                        String(value, Charsets.UTF_8),
                        System.currentTimeMillis()
                    )?.let(onRobotFix)
                }
            }
        }
    }

    /**
     * Envía actualización a la pantalla OLED del ESP32-S3 vía Bluetooth.
     * Formato: "STATE|USER_TEXT|AI_TEXT|LEVEL"
     */
    @SuppressLint("MissingPermission")
    fun sendDisplayUpdate(
        state: String,
        userText: String = "",
        aiText: String = "",
        audioLevel: Float = 0f
    ) {
        val gatt = bluetoothGatt ?: return
        val char = displayChar ?: return
        if (!isConnected) return

        val payload = "$state|$userText|$aiText|$audioLevel".toByteArray(Charsets.UTF_8)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    char,
                    payload,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                )
            } else {
                @Suppress("DEPRECATION")
                char.value = payload
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error escribiendo display update por BLE: ${e.message}")
        }
    }

    /**
     * Envía un bloque de PCM 16-bit mono a 8 kHz al parlante del robot.
     *
     * No encola: el llamador marca el ritmo. Devuelve `false` si el enlace no
     * está listo o el stack rechazó la escritura para que se reintente.
     */
    fun sendSpeakerPcm(pcm: ByteArray): Boolean {
        if (pcm.isEmpty()) return false
        val gatt = bluetoothGatt ?: return false
        val char = speakerChar ?: return false
        if (!isConnected) return false

        val payload = ByteArray(pcm.size + 1)
        payload[0] = 0x01
        System.arraycopy(pcm, 0, payload, 1, pcm.size)
        return writeSpeakerPayload(gatt, char, payload)
    }

    /**
     * Pide al robot que descarte el audio que le quede en la cola
     * (fin de locución, barge-in o desconexión).
     */
    fun flushSpeaker(): Boolean {
        val gatt = bluetoothGatt ?: return false
        val char = speakerChar ?: return false
        if (!isConnected) return false
        return writeSpeakerPayload(gatt, char, byteArrayOf(0x00))
    }

    @SuppressLint("MissingPermission")
    private fun writeSpeakerPayload(
        gatt: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        payload: ByteArray
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    char,
                    payload,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                char.value = payload
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error escribiendo audio al parlante por BLE: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnect: ${e.message}")
        }
        bluetoothGatt = null
        displayChar = null
        speakerChar = null
        _connectionState.value = BleConnectionState.Disconnected
        onConnectionChanged(false)
    }
}
