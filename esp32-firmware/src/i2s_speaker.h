#pragma once

#include "config.h"

#ifdef ENABLE_I2S_SPEAKER
/// Wrapper para el amplificador DAC MAX98357A vía I2S.
/// Reproduce audio PCM 16-bit mono a 16kHz por el altavoz.
class I2SSpeaker {
public:
    /// Inicializa el puerto I2S en modo TX con la configuración del MAX98357A.
    bool begin();

    /// Escribe muestras PCM 16-bit al altavoz.
    /// @return Número de muestras efectivamente escritas.
    size_t write(const int16_t* buffer, size_t sampleCount);

    /// Libera el driver I2S del altavoz.
    void end();

private:
    bool _initialized = false;
};
#endif

