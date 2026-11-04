#pragma once

#include "config.h"
#include "gps.h"
#include <Arduino.h>
#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

class I2SSpeaker;

/**
 * Servicio BLE bidireccional para FIFO:
 * - Transmite audio de micrófono en tiempo real (PCM 16 kHz) vía NOTIFY al celular.
 * - Recibe actualizaciones de pantalla OLED y estados del asistente vía WRITE desde el celular.
 * - Recibe la voz del asistente (PCM 8 kHz) vía WRITE y la encola en el MAX98357A.
 * - Notifica los gestos del pulsador para que el celular decida qué hacer.
 *
 * El servicio no habla, no decide ni reproduce: solo transporta y encola.
 */
class BleAudioService {
public:
    static BleAudioService& getInstance();

    /// Inicializa servidor BLE, características de audio, display, parlante y pulsador.
    /// @param speaker Parlante que recibe el audio descendente; puede ser nullptr.
    void begin(DisplayData* displayData, SemaphoreHandle_t displayMutex, I2SSpeaker* speaker);

    /// Envía un bloque de audio PCM 16-bit al celular vía BLE Notify.
    bool sendAudio(const int16_t* samples, size_t sampleCount);

    /// Notifica un gesto del pulsador al celular.
    bool notifyButton(uint8_t event);

    /// Publica el último fix GPS. Formato: "valid|lat|lon|alt|sats|hdop".
    bool sendGpsFix(const GpsFix& fix);

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
    BLECharacteristic* _pSpeakerCharacteristic = nullptr;
    BLECharacteristic* _pButtonCharacteristic = nullptr;
    BLECharacteristic* _pGpsCharacteristic = nullptr;

    I2SSpeaker*        _speaker = nullptr;

    bool _deviceConnected = false;
    bool _oldDeviceConnected = false;

    DisplayData*       _displayData = nullptr;
    SemaphoreHandle_t  _displayMutex = nullptr;

    friend class BleAudioServerCallbacks;
    friend class BleDisplayCharacteristicCallbacks;
    friend class BleSpeakerCharacteristicCallbacks;
};
