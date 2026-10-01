#include "i2s_speaker.h"
#include <Arduino.h>

#ifdef ENABLE_I2S_SPEAKER
bool I2SSpeaker::begin() {
    // ── Configuración I2S para el MAX98357A ─────────────
    // El MAX98357A acepta datos I2S estándar de 16-bit.
    // tx_desc_auto_clear = true: si no hay datos, envía silencio
    // en vez de repetir el último sample (evita ruido estático).
    i2s_config_t config = {};
    config.mode                 = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX);
    config.sample_rate          = SAMPLE_RATE;
    config.bits_per_sample      = I2S_BITS_PER_SAMPLE_16BIT;
    config.channel_format       = I2S_CHANNEL_FMT_ONLY_LEFT;
    config.communication_format = I2S_COMM_FORMAT_STAND_I2S;
    config.intr_alloc_flags     = ESP_INTR_FLAG_LEVEL1;
    config.dma_buf_count        = 8;     // Más buffers para evitar underrun
    config.dma_buf_len          = 256;
    config.use_apll             = false;
    config.tx_desc_auto_clear   = true;  // Silencio automático si no hay datos
    config.fixed_mclk           = 0;

    esp_err_t err = i2s_driver_install(SPK_I2S_PORT, &config, 0, NULL);
    if (err != ESP_OK) {
        Serial.printf("[SPK] Error instalando driver I2S: %s\n", esp_err_to_name(err));
        return false;
    }

    // ── Asignación de pines ─────────────────────────────
    i2s_pin_config_t pins = {};
    pins.bck_io_num   = SPK_I2S_BCLK;
    pins.ws_io_num    = SPK_I2S_LRC;
    pins.data_out_num = SPK_I2S_DIN;
    pins.data_in_num  = I2S_PIN_NO_CHANGE;

    err = i2s_set_pin(SPK_I2S_PORT, &pins);
    if (err != ESP_OK) {
        Serial.printf("[SPK] Error configurando pines: %s\n", esp_err_to_name(err));
        i2s_driver_uninstall(SPK_I2S_PORT);
        return false;
    }

    // Limpiar buffer para arrancar en silencio
    i2s_zero_dma_buffer(SPK_I2S_PORT);

    _initialized = true;
    Serial.println("[SPK] MAX98357A inicializado OK");
    return true;
}

size_t I2SSpeaker::write(const int16_t* buffer, size_t sampleCount) {
    if (!_initialized || sampleCount == 0) return 0;

    size_t bytesWritten = 0;

    esp_err_t err = i2s_write(
        SPK_I2S_PORT,
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
    if (_initialized) {
        i2s_zero_dma_buffer(SPK_I2S_PORT);
        i2s_driver_uninstall(SPK_I2S_PORT);
        _initialized = false;
        Serial.println("[SPK] Driver I2S desinstalado");
    }
}
#endif

