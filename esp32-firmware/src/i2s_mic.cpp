#include "i2s_mic.h"
#include <Arduino.h>
#include <driver/i2s.h>

bool I2SMic::begin() {
    // ═══════════════════════════════════════════════════
    //  INMP441: I2S maestro RX, ranura de 32 bits mono
    //  El micrófono entrega 24 bits; el DMA los trae completos.
    // ═══════════════════════════════════════════════════
    i2s_config_t config = {};
    config.mode                 = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX);
    config.sample_rate          = SAMPLE_RATE;
    config.bits_per_sample      = MIC_BITS_PER_SAMPLE;
    config.channel_format       = I2S_CHANNEL_FMT_ONLY_LEFT;
    config.communication_format = I2S_COMM_FORMAT_STAND_I2S;
    config.intr_alloc_flags     = ESP_INTR_FLAG_LEVEL1;
    config.dma_buf_count        = 8;
    config.dma_buf_len          = 256;
    config.use_apll             = false;
    config.tx_desc_auto_clear   = false;
    config.fixed_mclk           = 0;

    esp_err_t err = i2s_driver_install((i2s_port_t)MIC_I2S_PORT, &config, 0, NULL);
    if (err != ESP_OK) {
        Serial.printf("[MIC] Error instalando driver I2S: %s\n", esp_err_to_name(err));
        return false;
    }

    i2s_pin_config_t pins = {};
    pins.mck_io_num   = I2S_PIN_NO_CHANGE;
    pins.bck_io_num   = MIC_I2S_SCK;
    pins.ws_io_num    = MIC_I2S_WS;
    pins.data_out_num = I2S_PIN_NO_CHANGE;
    pins.data_in_num  = MIC_I2S_SD;

    err = i2s_set_pin((i2s_port_t)MIC_I2S_PORT, &pins);
    if (err != ESP_OK) {
        Serial.printf("[MIC] Error configurando pines: %s\n", esp_err_to_name(err));
        i2s_driver_uninstall((i2s_port_t)MIC_I2S_PORT);
        return false;
    }

    // Descartar el primer bloque evita arrancar con basura del DMA.
    i2s_zero_dma_buffer((i2s_port_t)MIC_I2S_PORT);

    _initialized = true;
    Serial.println("[MIC] INMP441 (I2S DMA) inicializado OK a 16 kHz");
    Serial.printf("[MIC] SCK=%d WS=%d SD=%d | Sample rate: %d Hz\n",
                  MIC_I2S_SCK, MIC_I2S_WS, MIC_I2S_SD, SAMPLE_RATE);
    return true;
}

size_t I2SMic::read(int16_t* buffer, size_t maxSamples) {
    if (!_initialized || maxSamples == 0) return 0;

    size_t samples = maxSamples > CHUNK_SAMPLES ? CHUNK_SAMPLES : maxSamples;
    size_t bytesRequested = samples * sizeof(int32_t);
    size_t bytesRead = 0;

    esp_err_t err = i2s_read(
        (i2s_port_t)MIC_I2S_PORT,
        _rawBuffer,
        bytesRequested,
        &bytesRead,
        pdMS_TO_TICKS(50)
    );
    if (err != ESP_OK && err != ESP_ERR_TIMEOUT) {
        return 0;
    }

    size_t samplesRead = bytesRead / sizeof(int32_t);
    for (size_t i = 0; i < samplesRead; i++) {
        // 24 bits alineados a la izquierda dentro de la ranura de 32.
        float sample = (float)(_rawBuffer[i] >> MIC_SAMPLE_SHIFT);

        // El INMP441 sale acoplado en alterna, pero conserva una deriva lenta
        // de continua. Un bloqueador de una polo la quita sin tocar la voz.
        _dcOffset = _dcOffset * 0.9995f + sample * 0.0005f;
        int32_t centered = (int32_t)(sample - _dcOffset);

        if (centered > 32767) centered = 32767;
        if (centered < -32768) centered = -32768;
        buffer[i] = (int16_t)centered;
    }

    return samplesRead;
}

void I2SMic::end() {
    if (_initialized) {
        i2s_driver_uninstall((i2s_port_t)MIC_I2S_PORT);
        _initialized = false;
        Serial.println("[MIC] Driver I2S desinstalado");
    }
}
