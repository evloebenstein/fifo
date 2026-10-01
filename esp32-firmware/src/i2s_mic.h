#pragma once

#include "config.h"
#include <driver/adc.h>

/// Wrapper para micrófono analógico (módulo UCC con ganancia ajustable)
/// usando el controlador Digital ADC DMA (Continuous) del ESP32-S3.
/// Muestrea a 16 kHz por hardware DMA sin consumir ciclos de CPU.
class I2SMic {
public:
    /// Inicializa el ADC continuo por DMA.
    bool begin();

    /// Lee hasta `maxSamples` muestras PCM 16-bit convertidas.
    /// @return Número de muestras leídas.
    size_t read(int16_t* buffer, size_t maxSamples);

    /// Libera recursos.
    void end();

private:
    bool _initialized = false;
    adc_digi_output_data_t _dmaBuffer[CHUNK_SAMPLES];
    float _dcBias = 2048.0f;
    float _prevIn = 0.0f;
    float _prevOut = 0.0f;
};

