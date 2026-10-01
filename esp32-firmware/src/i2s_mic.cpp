#include "i2s_mic.h"
#include <Arduino.h>
#include <driver/adc.h>

bool I2SMic::begin() {
    // ═══════════════════════════════════════════════════
    //  ESP32-S3 ADC Continuous (DMA) Mode
    //  Muestreo continuo por hardware a 16 kHz vía DMA
    // ═══════════════════════════════════════════════════
    adc_digi_init_config_t adc_dma_config = {};
    adc_dma_config.max_store_buf_size = 4096;
    adc_dma_config.conv_num_each_intr = 512;
    adc_dma_config.adc1_chan_mask = (1 << MIC_ADC_CHANNEL);
    adc_dma_config.adc2_chan_mask = 0;

    esp_err_t err = adc_digi_initialize(&adc_dma_config);
    if (err != ESP_OK) {
        Serial.printf("[MIC] Error adc_digi_initialize: %s\n", esp_err_to_name(err));
        return false;
    }

    adc_digi_pattern_config_t pattern = {};
    pattern.atten = ADC_ATTEN_DB_12;
    pattern.channel = (uint8_t)MIC_ADC_CHANNEL;
    pattern.unit = ADC_UNIT_1;
    pattern.bit_width = 12;

    adc_digi_configuration_t dig_cfg = {};
    dig_cfg.conv_limit_en = false;
    dig_cfg.conv_limit_num = 0;
    dig_cfg.pattern_num = 1;
    dig_cfg.adc_pattern = &pattern;
    dig_cfg.sample_freq_hz = SAMPLE_RATE; // 16000 Hz
    dig_cfg.conv_mode = ADC_CONV_SINGLE_UNIT_1;
    dig_cfg.format = ADC_DIGI_OUTPUT_FORMAT_TYPE2;

    err = adc_digi_controller_configure(&dig_cfg);
    if (err != ESP_OK) {
        Serial.printf("[MIC] Error adc_digi_controller_configure: %s\n", esp_err_to_name(err));
        adc_digi_deinitialize();
        return false;
    }

    err = adc_digi_start();
    if (err != ESP_OK) {
        Serial.printf("[MIC] Error adc_digi_start: %s\n", esp_err_to_name(err));
        adc_digi_deinitialize();
        return false;
    }

    _initialized = true;
    Serial.println("[MIC] Micrófono analógico UCC (ADC DMA) inicializado OK a 16kHz");
    Serial.printf("[MIC] Pin: GPIO %d | ADC1 Canal %d | Sample rate: %d Hz\n",
                  MIC_ADC_PIN, MIC_ADC_CHANNEL, SAMPLE_RATE);
    return true;
}

size_t I2SMic::read(int16_t* buffer, size_t maxSamples) {
    if (!_initialized || maxSamples == 0) return 0;

    uint32_t toReadBytes = maxSamples * sizeof(adc_digi_output_data_t);
    if (toReadBytes > sizeof(_dmaBuffer)) {
        toReadBytes = sizeof(_dmaBuffer);
    }

    uint32_t outLength = 0;
    esp_err_t err = adc_digi_read_bytes((uint8_t*)_dmaBuffer, toReadBytes, &outLength, 50);
    if (err != ESP_OK && err != ESP_ERR_TIMEOUT) {
        return 0;
    }

    size_t samplesCount = outLength / sizeof(adc_digi_output_data_t);
    for (size_t i = 0; i < samplesCount; i++) {
        float sampleRaw = (float)(_dmaBuffer[i].type2.data); // 12 bits: 0..4095
        
        // 1. Seguimiento adaptativo de la componente continua (DC Bias)
        _dcBias = _dcBias * 0.999f + sampleRaw * 0.001f;
        float centered = sampleRaw - _dcBias;

        // 2. Filtro paso-altos (~150 Hz a 16 kHz): elimina zumbidos graves y vibraciones
        float hpOut = 0.942f * (_prevOut + centered - _prevIn);
        _prevIn = centered;
        _prevOut = hpOut;

        // 3. Puerta de ruido suave (Soft Noise Gate):
        // Atenúa el murmullo lejano de baja amplitud (< 30 unidades ADC).
        // Al hablar de cerca al micrófono UCC, la señal supera 100-500 unidades y pasa nítida
        float absVal = fabsf(hpOut);
        float gain = 16.0f;
        if (absVal < 30.0f) {
            gain = 4.0f; // Atenúa murmullo distante
        } else if (absVal < 70.0f) {
            gain = 4.0f + (absVal - 30.0f) * (12.0f / 40.0f);
        }

        int32_t scaled = (int32_t)(hpOut * gain);
        if (scaled > 32767) scaled = 32767;
        if (scaled < -32768) scaled = -32768;
        buffer[i] = (int16_t)scaled;
    }

    return samplesCount;
}

void I2SMic::end() {
    if (_initialized) {
        adc_digi_stop();
        adc_digi_deinitialize();
        _initialized = false;
        Serial.println("[MIC] Driver ADC DMA desinstalado");
    }
}

