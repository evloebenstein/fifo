#include "i2s_speaker.h"
#include <Arduino.h>
#include <driver/i2s.h>

bool I2SSpeaker::begin() {
    // ── Configuración I2S para el MAX98357A ─────────────
    // El MAX98357A acepta datos I2S estándar de 16-bit.
    // tx_desc_auto_clear = true: si no hay datos, envía silencio
    // en vez de repetir el último sample (evita ruido estático).
    i2s_config_t config = {};
    config.mode                 = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX);
    config.sample_rate          = SPEAKER_SAMPLE_RATE;
    config.bits_per_sample      = I2S_BITS_PER_SAMPLE_16BIT;
    config.channel_format       = I2S_CHANNEL_FMT_ONLY_LEFT;
    config.communication_format = I2S_COMM_FORMAT_STAND_I2S;
    config.intr_alloc_flags     = ESP_INTR_FLAG_LEVEL1;
    config.dma_buf_count        = 8;     // Más buffers para evitar underrun
    config.dma_buf_len          = 256;
    config.use_apll             = false;
    config.tx_desc_auto_clear   = true;  // Silencio automático si no hay datos
    config.fixed_mclk           = 0;

    esp_err_t err = i2s_driver_install((i2s_port_t)SPK_I2S_PORT, &config, 0, NULL);
    if (err != ESP_OK) {
        Serial.printf("[SPK] Error instalando driver I2S: %s\n", esp_err_to_name(err));
        return false;
    }

    // ── Asignación de pines ─────────────────────────────
    i2s_pin_config_t pins = {};
    pins.mck_io_num   = I2S_PIN_NO_CHANGE;
    pins.bck_io_num   = SPK_I2S_BCLK;
    pins.ws_io_num    = SPK_I2S_LRC;
    pins.data_out_num = SPK_I2S_DIN;
    pins.data_in_num  = I2S_PIN_NO_CHANGE;

    err = i2s_set_pin((i2s_port_t)SPK_I2S_PORT, &pins);
    if (err != ESP_OK) {
        Serial.printf("[SPK] Error configurando pines: %s\n", esp_err_to_name(err));
        i2s_driver_uninstall((i2s_port_t)SPK_I2S_PORT);
        return false;
    }

    // Limpiar buffer para arrancar en silencio
    i2s_zero_dma_buffer((i2s_port_t)SPK_I2S_PORT);

    _queue = xStreamBufferCreate(SPEAKER_QUEUE_BYTES, 1);
    if (_queue == nullptr) {
        Serial.println("[SPK] Error creando la cola de reproducción");
        i2s_driver_uninstall((i2s_port_t)SPK_I2S_PORT);
        return false;
    }

    _initialized = true;
    Serial.println("[SPK] MAX98357A inicializado OK");
    Serial.printf("[SPK] BCLK=%d LRC=%d DIN=%d | %d Hz\n",
                  SPK_I2S_BCLK, SPK_I2S_LRC, SPK_I2S_DIN, SPEAKER_SAMPLE_RATE);
    return true;
}

bool I2SSpeaker::submit(const uint8_t* pcmBytes, size_t byteCount) {
    if (!_initialized || _queue == nullptr || pcmBytes == nullptr) return false;

    // Solo bloques enteros de 16 bits: una muestra partida desalinea todo.
    byteCount &= ~static_cast<size_t>(1);
    if (byteCount == 0) return false;

    // Un solo productor (callbacks BLE serializados por el stack): comprobar
    // espacio y escribir es atómico en la práctica. Si no cabe, se descarta el
    // bloque completo en vez de recortarlo.
    if (xStreamBufferSpacesAvailable(_queue) < byteCount) return false;

    return xStreamBufferSend(_queue, pcmBytes, byteCount, 0) == byteCount;
}

void I2SSpeaker::flush() {
    if (_queue != nullptr) {
        xStreamBufferReset(_queue);
    }
}

size_t I2SSpeaker::pump(TickType_t waitTicks) {
    if (!_initialized || _queue == nullptr) return 0;

    size_t received = xStreamBufferReceive(_queue, _chunk, sizeof(_chunk), waitTicks);
    if (received < sizeof(int16_t)) return 0;

    return write(_chunk, received / sizeof(int16_t));
}

size_t I2SSpeaker::write(const int16_t* buffer, size_t sampleCount) {
    if (!_initialized || sampleCount == 0) return 0;

    size_t bytesWritten = 0;

    esp_err_t err = i2s_write(
        (i2s_port_t)SPK_I2S_PORT,
        buffer,
        sampleCount * sizeof(int16_t),
        &bytesWritten,
        portMAX_DELAY
    );

    if (err != ESP_OK) {
        Serial.printf("[SPK] Error escribiendo I2S: %s\n", esp_err_to_name(err));
        return 0;
    }

    return bytesWritten / sizeof(int16_t);
}

void I2SSpeaker::end() {
    if (_queue != nullptr) {
        vStreamBufferDelete(_queue);
        _queue = nullptr;
    }
    if (_initialized) {
        i2s_zero_dma_buffer((i2s_port_t)SPK_I2S_PORT);
        i2s_driver_uninstall((i2s_port_t)SPK_I2S_PORT);
        _initialized = false;
        Serial.println("[SPK] Driver I2S desinstalado");
    }
}
