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
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*

sealed class ProvisioningState {
    object Idle : ProvisioningState()
    object Scanning : ProvisioningState()
    object Connecting : ProvisioningState()
    object Sending : ProvisioningState()
    object Success : ProvisioningState()
    data class Error(val message: String) : ProvisioningState()
}

/**
 * Gestor de aprovisionamiento de Wi-Fi por Bluetooth Low Energy (BLE) en 1 Clic.
 */
class BleProvisioner {

    companion object {
        private const val TAG = "BleProvisioner"
        val SERVICE_UUID: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
        private const val DEVICE_NAME = "FIFO-ESP32"
        private const val SCAN_TIMEOUT_MS = 10000L
    }

    private val _state = MutableStateFlow<ProvisioningState>(ProvisioningState.Idle)
    val state: StateFlow<ProvisioningState> = _state.asStateFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isScanning = false
    private var currentScanCallback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    fun startProvisioning(context: Context, ssid: String, pass: String) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            _state.value = ProvisioningState.Error("Bluetooth desactivado en el celular")
            return
        }

        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            _state.value = ProvisioningState.Error("No se pudo iniciar el escáner BLE")
            return
        }

        _state.value = ProvisioningState.Scanning
        Log.i(TAG, "Iniciando escaneo BLE buscando '$DEVICE_NAME'...")

        val timeoutRunnable = Runnable {
            if (isScanning) {
                currentScanCallback?.let { cb ->
                    try {
                        scanner.stopScan(cb)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error deteniendo escaneo: ${e.message}")
                    }
                }
                isScanning = false
                currentScanCallback = null
                if (_state.value is ProvisioningState.Scanning) {
                    _state.value = ProvisioningState.Error("No se encontró el ESP32. Verifica que esté encendido")
                }
            }
        }
        handler.postDelayed(timeoutRunnable, SCAN_TIMEOUT_MS)

        val scanFilter = ScanFilter.Builder()
            .setDeviceName(DEVICE_NAME)
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val device = result?.device ?: return
                val name = device.name ?: result.scanRecord?.deviceName ?: ""

                if (name.contains(DEVICE_NAME, ignoreCase = true)) {
                    Log.i(TAG, "ESP32 encontrado: ${device.address}")
                    handler.removeCallbacks(timeoutRunnable)
                    try {
                        scanner.stopScan(this)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error stopScan: ${e.message}")
                    }
                    isScanning = false
                    currentScanCallback = null

                    connectAndSend(context, device, ssid, pass)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                isScanning = false
                currentScanCallback = null
                handler.removeCallbacks(timeoutRunnable)
                _state.value = ProvisioningState.Error("Fallo al escanear BLE (código $errorCode)")
            }
        }

        currentScanCallback = callback
        isScanning = true
        try {
            scanner.startScan(listOf(scanFilter), scanSettings, callback)
        } catch (e: Exception) {
            isScanning = false
            currentScanCallback = null
            handler.removeCallbacks(timeoutRunnable)
            _state.value = ProvisioningState.Error("Error al iniciar escaneo BLE: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectAndSend(context: Context, device: BluetoothDevice, ssid: String, pass: String) {
        _state.value = ProvisioningState.Connecting
        Log.i(TAG, "Conectando a GATT en ${device.address}...")

        bluetoothGatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "Conectado a GATT. Descubriendo servicios...")
                    gatt?.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "Desconectado de GATT")
                    cleanup()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                    val service = gatt.getService(SERVICE_UUID)
                    if (service == null) {
                        _state.value = ProvisioningState.Error("Servicio FIFO no encontrado en ESP32")
                        cleanup()
                        return
                    }

                    val char = service.getCharacteristic(CHARACTERISTIC_UUID)
                    if (char == null) {
                        _state.value = ProvisioningState.Error("Característica de configuración no encontrada")
                        cleanup()
                        return
                    }

                    _state.value = ProvisioningState.Sending
                    val payload = """{"ssid":"$ssid","pass":"$pass"}"""
                    val bytes = payload.toByteArray(Charsets.UTF_8)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(char, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    } else {
                        @Suppress("DEPRECATION")
                        char.value = bytes
                        @Suppress("DEPRECATION")
                        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        @Suppress("DEPRECATION")
                        gatt.writeCharacteristic(char)
                    }
                } else {
                    _state.value = ProvisioningState.Error("Error descubriendo servicios BLE ($status)")
                    cleanup()
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Credenciales escritas exitosamente en el ESP32")
                    _state.value = ProvisioningState.Success
                } else {
                    _state.value = ProvisioningState.Error("Error escribiendo credenciales ($status)")
                }
                cleanup()
            }
        }, BluetoothDevice.TRANSPORT_LE)
    }

    fun reset() {
        _state.value = ProvisioningState.Idle
    }

    @SuppressLint("MissingPermission")
    private fun cleanup() {
        try {
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error cerrando GATT: ${e.message}")
        }
        bluetoothGatt = null
    }
}
