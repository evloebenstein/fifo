#pragma once

#include "config.h"
#include <Arduino.h>
#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

/**
 * Servicio BLE bidireccional para FIFO:
 * - Transmite audio de micrófono en tiempo real (PCM 16kHz) vía NOTIFY al celular.
 * - Recibe actualizaciones de pantalla OLED y estados del asistente vía WRITE desde el celular.
 */
class BleAudioService {
public:
    static BleAudioService& getInstance();

    /// Inicializa servidor BLE, características de audio y display, e inicia publicidad.
    void begin(DisplayData* displayData, SemaphoreHandle_t displayMutex);

    /// Envía un bloque de audio PCM 16-bit al celular vía BLE Notify.
    bool sendAudio(const int16_t* samples, size_t sampleCount);

    /// Retorna si el cliente (celular) está conectado actualmente por BLE.
    bool isConnected() const;

    /// Detiene el stack BLE.
    void stop();

private:
    BleAudioService() = default;

    BLEServer*         _pServer = nullptr;
    BLEService*        _pService = nullptr;
    BLECharacteristic* _pAudioCharacteristic = nullptr;
    BLECharacteristic* _pDisplayCharacteristic = nullptr;

    bool _deviceConnected = false;
    bool _oldDeviceConnected = false;

    DisplayData*       _displayData = nullptr;
    SemaphoreHandle_t  _displayMutex = nullptr;

    friend class BleAudioServerCallbacks;
    friend class BleDisplayCharacteristicCallbacks;
};
