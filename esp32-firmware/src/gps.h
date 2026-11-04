#pragma once

#include "config.h"
#include <cstdint>

/// Última posición conocida del módulo GNSS ATGM336H.
///
/// Es un hecho, no una frase: el firmware no decide si hay o no hay ubicación
/// que contar, solo publica `valid` y las coordenadas.
struct GpsFix {
    bool     valid = false;
    double   latitude = 0.0;
    double   longitude = 0.0;
    float    altitudeMeters = 0.0f;
    uint8_t  satellites = 0;
    float    hdop = 0.0f;
    uint32_t updatedAtMs = 0;
};

/// Lector de tramas NMEA 0183 del ATGM336H (GPS + BeiDou).
///
/// Solo interpreta GGA: de ahí salen el estado del fix, las coordenadas, la
/// altitud, el número de satélites y la dilución de precisión. No habla, no
/// filtra y no inventa una posición cuando el módulo no tiene fix.
class GpsModule {
public:
    /// Arranca el UART del GPS.
    bool begin();

    /// Consume los caracteres disponibles y actualiza el fix.
    /// @return `true` si se acaba de cerrar una trama GGA.
    bool poll(unsigned long nowMs);

    /// Último fix conocido (puede ser inválido).
    const GpsFix& fix() const { return _fix; }

private:
    void handleLine(char* line, unsigned long nowMs);
    void parseGga(char* sentence, unsigned long nowMs);

    GpsFix _fix;
    char _line[GPS_LINE_MAX] = {};
    size_t _lineLength = 0;
    bool _receiving = false;
};
