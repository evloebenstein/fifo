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

sealed class BleConnectionState {
    object Disconnected : BleConnectionState()
    object Scanning : BleConnectionState()
    object Connecting : BleConnectionState()
    object Connected : BleConnectionState()
    data class Error(val message: String) : BleConnectionState()
}

/**
 * Cliente BLE para conectar el celular Android con el ESP32-S3 de FIFO.
 * - Recibe paquetes de audio PCM 16kHz del micrófono UCC en tiempo real vía NOTIFY.
 * - Envía estados y texto a la pantalla OLED 1.3" I2C del ESP32 vía WRITE.
 * - Soporta reconexión automática y negociación de MTU 512.
 */
class BleVoiceClient(
    private val onAudioReceived: (ByteArray) -> Unit,
    private val onConnectionChanged: (Boolean) -> Unit
) {
    companion object {
        private const val TAG = "BleVoiceClient"

        val FIFO_SERVICE_UUID: UUID = UUID.fromString("0000ff10-0000-1000-8000-00805f9b34fb")
        val AUDIO_CHAR_UUID: UUID   = UUID.fromString("0000ff11-0000-1000-8000-00805f9b34fb")
        val DISPLAY_CHAR_UUID: UUID = UUID.fromString("0000ff12-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID         = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val DEVICE_NAME = "FIFO-S3"
        private const val SCAN_TIMEOUT_MS = 15000L
    }

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    val isConnected: Boolean
        get() = _connectionState.value is BleConnectionState.Connected

    private var bluetoothGatt: BluetoothGatt? = null
    private var displayChar: BluetoothGattCharacteristic? = null
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

            _connectionState.value = BleConnectionState.Connected
            onConnectionChanged(true)
            Log.i(TAG, "¡ESP32-S3 completamente enlazado por BLE! Audio y pantalla listos.")
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            if (characteristic?.uuid == AUDIO_CHAR_UUID) {
                @Suppress("DEPRECATION")
                val data = characteristic.value ?: return
                onAudioReceived(data)
            }
        }

        // Android 13+ (API 33)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == AUDIO_CHAR_UUID) {
                onAudioReceived(value)
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
        _connectionState.value = BleConnectionState.Disconnected
        onConnectionChanged(false)
    }
}
