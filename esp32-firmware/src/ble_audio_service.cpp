#include "ble_audio_service.h"
#include "i2s_speaker.h"

// ── Protocolo de la característica de parlante (ff13) ───────
// byte 0     : opcode
//               0x00 = descartar lo que quede en la cola
//               0x01 = sigue PCM 16-bit little endian a 8 kHz
// byte 1..n  : muestras (solo con opcode 0x01)
static constexpr uint8_t SPEAKER_OP_FLUSH = 0x00;
static constexpr uint8_t SPEAKER_OP_PCM = 0x01;

class BleAudioServerCallbacks : public BLEServerCallbacks {
public:
    explicit BleAudioServerCallbacks(BleAudioService* service) : _service(service) {}

    void onConnect(BLEServer* pServer) override {
        _service->_deviceConnected = true;
        Serial.println("[BLE] Celular conectado por BLE!");

        if (_service->_displayMutex && _service->_displayData) {
            xSemaphoreTake(_service->_displayMutex, portMAX_DELAY);
            _service->_displayData->bleConnected = true;
            strncpy(_service->_displayData->state, "LISTO", sizeof(_service->_displayData->state));
            xSemaphoreGive(_service->_displayMutex);
        }
    }

    void onDisconnect(BLEServer* pServer) override {
        _service->_deviceConnected = false;
        Serial.println("[BLE] Celular desconectado. Reiniciando publicidad BLE...");

        // Nada de audio viejo en la cola cuando vuelva a conectar.
        if (_service->_speaker != nullptr) {
            _service->_speaker->flush();
        }

        if (_service->_displayMutex && _service->_displayData) {
            xSemaphoreTake(_service->_displayMutex, portMAX_DELAY);
            _service->_displayData->bleConnected = false;
            strncpy(_service->_displayData->state, "ESPERANDO", sizeof(_service->_displayData->state));
            _service->_displayData->userText[0] = '\0';
            _service->_displayData->aiText[0] = '\0';
            _service->_displayData->audioLevel = 0.0f;
            xSemaphoreGive(_service->_displayMutex);
        }

        // Reiniciar publicidad para que el celular se reconecte automáticamente
        delay(200);
        BLEDevice::startAdvertising();
        Serial.println("[BLE] Publicidad BLE reactivada");
    }

private:
    BleAudioService* _service;
};

class BleDisplayCharacteristicCallbacks : public BLECharacteristicCallbacks {
public:
    explicit BleDisplayCharacteristicCallbacks(BleAudioService* service) : _service(service) {}

    void onWrite(BLECharacteristic* pCharacteristic) override {
        std::string val = pCharacteristic->getValue();
        if (val.empty() || !_service->_displayData || !_service->_displayMutex) return;

        String msg = String(val.c_str());

        xSemaphoreTake(_service->_displayMutex, portMAX_DELAY);

        // Formato 1: Pipes -> "STATE|USER_TEXT|AI_TEXT|LEVEL"
        if (msg.indexOf('|') >= 0) {
            int p1 = msg.indexOf('|');
            int p2 = msg.indexOf('|', p1 + 1);
            int p3 = (p2 >= 0) ? msg.indexOf('|', p2 + 1) : -1;

            String state = msg.substring(0, p1);
            String user  = (p2 >= 0) ? msg.substring(p1 + 1, p2) : msg.substring(p1 + 1);
            String ai    = (p2 >= 0 && p3 >= 0) ? msg.substring(p2 + 1, p3) : ((p2 >= 0) ? msg.substring(p2 + 1) : "");
            String level = (p3 >= 0) ? msg.substring(p3 + 1) : "0";

            if (state.length() > 0) {
                strncpy(_service->_displayData->state, state.c_str(), sizeof(_service->_displayData->state) - 1);
            }
            if (user.length() > 0) {
                strncpy(_service->_displayData->userText, user.c_str(), sizeof(_service->_displayData->userText) - 1);
            }
            if (ai.length() > 0) {
                strncpy(_service->_displayData->aiText, ai.c_str(), sizeof(_service->_displayData->aiText) - 1);
            }
            if (level.length() > 0) {
                _service->_displayData->audioLevel = level.toFloat();
            }
        }
        // Formato 2: JSON -> {"state":"...","user":"...","ai":"...","level":0.5}
        else if (msg.indexOf("\"state\"") >= 0) {
            int sIdx = msg.indexOf("\"state\":\"");
            if (sIdx >= 0) {
                sIdx += 9;
                int sEnd = msg.indexOf('"', sIdx);
                if (sEnd > sIdx) {
                    String st = msg.substring(sIdx, sEnd);
                    strncpy(_service->_displayData->state, st.c_str(), sizeof(_service->_displayData->state) - 1);
                }
            }

            int uIdx = msg.indexOf("\"user\":\"");
            if (uIdx >= 0) {
                uIdx += 8;
                int uEnd = msg.indexOf('"', uIdx);
                if (uEnd >= uIdx) {
                    String u = msg.substring(uIdx, uEnd);
                    memset(_service->_displayData->userText, 0, sizeof(_service->_displayData->userText));
                    strncpy(_service->_displayData->userText, u.c_str(), sizeof(_service->_displayData->userText) - 1);
                }
            }

            int aIdx = msg.indexOf("\"ai\":\"");
            if (aIdx >= 0) {
                aIdx += 6;
                int aEnd = msg.indexOf('"', aIdx);
                if (aEnd >= aIdx) {
                    String a = msg.substring(aIdx, aEnd);
                    memset(_service->_displayData->aiText, 0, sizeof(_service->_displayData->aiText));
                    strncpy(_service->_displayData->aiText, a.c_str(), sizeof(_service->_displayData->aiText) - 1);
                }
            }

            int lIdx = msg.indexOf("\"level\":");
            if (lIdx >= 0) {
                lIdx += 8;
                _service->_displayData->audioLevel = msg.substring(lIdx).toFloat();
            }
        } else {
            // Texto simple asignado directamente a estado
            strncpy(_service->_displayData->state, msg.c_str(), sizeof(_service->_displayData->state) - 1);
        }

        xSemaphoreGive(_service->_displayMutex);
    }

private:
    BleAudioService* _service;
};

class BleSpeakerCharacteristicCallbacks : public BLECharacteristicCallbacks {
public:
    explicit BleSpeakerCharacteristicCallbacks(BleAudioService* service) : _service(service) {}

    void onWrite(BLECharacteristic* pCharacteristic) override {
        if (_service->_speaker == nullptr) return;

        std::string val = pCharacteristic->getValue();
        if (val.size() < 2) return;

        const uint8_t* data = reinterpret_cast<const uint8_t*>(val.data());
        const uint8_t opcode = data[0];

        if (opcode == SPEAKER_OP_FLUSH) {
            _service->_speaker->flush();
            return;
        }

        if (opcode == SPEAKER_OP_PCM) {
            // Si no cabe, submit descarta el bloque: el audio se degrada, pero el
            // callback de BLE nunca se queda esperando al DMA.
            _service->_speaker->submit(data + 1, val.size() - 1);
        }
    }

private:
    BleAudioService* _service;
};

BleAudioService& BleAudioService::getInstance() {
    static BleAudioService instance;
    return instance;
}

void BleAudioService::begin(DisplayData* displayData, SemaphoreHandle_t displayMutex, I2SSpeaker* speaker) {
    _displayData = displayData;
    _displayMutex = displayMutex;
    _speaker = speaker;
    _deviceConnected = false;

    Serial.println("[BLE] Inicializando stack BLE...");
    BLEDevice::init(BLE_DEVICE_NAME);

    // Ajustar MTU máxima permitida para transmisión rápida de audio
    BLEDevice::setMTU(517);

    _pServer = BLEDevice::createServer();
    _pServer->setCallbacks(new BleAudioServerCallbacks(this));

    _pService = _pServer->createService(BLE_SERVICE_UUID);

    // ── Característica de Audio (Notify): Micrófono → Celular ────────
    _pAudioCharacteristic = _pService->createCharacteristic(
        BLE_AUDIO_CHAR_UUID,
        BLECharacteristic::PROPERTY_READ |
        BLECharacteristic::PROPERTY_NOTIFY
    );
    _pAudioCharacteristic->addDescriptor(new BLE2902());

    // ── Característica de Display (Write): Celular → OLED ESP32 ───────
    _pDisplayCharacteristic = _pService->createCharacteristic(
        BLE_DISPLAY_CHAR_UUID,
        BLECharacteristic::PROPERTY_READ |
        BLECharacteristic::PROPERTY_WRITE |
        BLECharacteristic::PROPERTY_WRITE_NR
    );
    _pDisplayCharacteristic->setCallbacks(new BleDisplayCharacteristicCallbacks(this));

    // ── Característica de Parlante (Write): Celular → MAX98357A ───────
    _pSpeakerCharacteristic = _pService->createCharacteristic(
        BLE_SPEAKER_CHAR_UUID,
        BLECharacteristic::PROPERTY_WRITE |
        BLECharacteristic::PROPERTY_WRITE_NR
    );
    _pSpeakerCharacteristic->setCallbacks(new BleSpeakerCharacteristicCallbacks(this));

    // ── Característica de Pulsador (Notify): ESP32 → Celular ──────────
    _pButtonCharacteristic = _pService->createCharacteristic(
        BLE_BUTTON_CHAR_UUID,
        BLECharacteristic::PROPERTY_READ |
        BLECharacteristic::PROPERTY_NOTIFY
    );
    _pButtonCharacteristic->addDescriptor(new BLE2902());

    // ── Característica de GNSS (Notify): ESP32 → Celular ──────────────
    _pGpsCharacteristic = _pService->createCharacteristic(
        BLE_GPS_CHAR_UUID,
        BLECharacteristic::PROPERTY_READ |
        BLECharacteristic::PROPERTY_NOTIFY
    );
    _pGpsCharacteristic->addDescriptor(new BLE2902());

    _pService->start();

    // ── Publicidad BLE ──────────────────────────────────────────────
    // Publicamos el Service UUID directamente en el anuncio principal
    // para que Android lo detecte al instante en segundo plano.
    BLEAdvertising* pAdvertising = BLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(BLE_SERVICE_UUID);
    pAdvertising->setScanResponse(true);
    pAdvertising->setMinPreferred(0x06); // 7.5ms
    pAdvertising->setMinPreferred(0x12); // 22.5ms
    BLEDevice::startAdvertising();

    Serial.println("[BLE] Servicio de voz activo como '" BLE_DEVICE_NAME "'");
    Serial.println("[BLE] UUID Servicio: " BLE_SERVICE_UUID);
    Serial.println("[BLE] UUID Audio (Notify): " BLE_AUDIO_CHAR_UUID);
    Serial.println("[BLE] UUID Display (Write): " BLE_DISPLAY_CHAR_UUID);
    Serial.println("[BLE] UUID Parlante (Write): " BLE_SPEAKER_CHAR_UUID);
    Serial.println("[BLE] UUID Pulsador (Notify): " BLE_BUTTON_CHAR_UUID);
    Serial.println("[BLE] UUID GPS (Notify): " BLE_GPS_CHAR_UUID);
}

bool BleAudioService::sendAudio(const int16_t* samples, size_t sampleCount) {
    if (!_deviceConnected || !_pAudioCharacteristic || sampleCount == 0) {
        return false;
    }

    size_t byteLength = sampleCount * sizeof(int16_t);
    _pAudioCharacteristic->setValue((uint8_t*)samples, byteLength);
    _pAudioCharacteristic->notify();
    return true;
}

bool BleAudioService::notifyButton(uint8_t event) {
    if (!_deviceConnected || !_pButtonCharacteristic) {
        return false;
    }

    _pButtonCharacteristic->setValue(&event, 1);
    _pButtonCharacteristic->notify();
    Serial.printf("[BTN] Evento %u notificado al celular\n", event);
    return true;
}

bool BleAudioService::sendGpsFix(const GpsFix& fix) {
    if (!_deviceConnected || !_pGpsCharacteristic) return false;

    // Formato compacto: "valid|lat|lon|alt|sats|hdop"
    char buf[96];
    snprintf(buf, sizeof(buf), "%d|%.6f|%.6f|%.1f|%u|%.1f",
             fix.valid ? 1 : 0,
             fix.latitude,
             fix.longitude,
             fix.altitudeMeters,
             fix.satellites,
             fix.hdop);

    _pGpsCharacteristic->setValue((uint8_t*)buf, strlen(buf));
    _pGpsCharacteristic->notify();
    return true;
}

bool BleAudioService::isConnected() const {
    return _deviceConnected;
}

void BleAudioService::stop() {
    if (_pServer) {
        BLEDevice::deinit(true);
        _pServer = nullptr;
        _deviceConnected = false;
        Serial.println("[BLE] Servicio detenido");
    }
}
