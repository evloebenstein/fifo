#pragma once

#include "config.h"
#include <cstddef>
#include <cstdint>

/// Micrófono digital I2S INMP441.
///
/// Usa el controlador I2S0 en modo maestro RX con ranuras de 32 bits y solo el
/// canal izquierdo (L/R a GND). El INMP441 entrega 24 bits alineados a la
/// izquierda, así que se desplazan a PCM 16 bits.
class I2SMic {
public:
    /// Inicializa el driver I2S de recepción.
    bool begin();

    /// Lee hasta `maxSamples` muestras PCM 16-bit convertidas.
    /// @return Número de muestras leídas.
    size_t read(int16_t* buffer, size_t maxSamples);

    /// Libera recursos.
    void end();

private:
    bool _initialized = false;
    int32_t _rawBuffer[CHUNK_SAMPLES] = {};
    float _dcOffset = 0.0f;
};
