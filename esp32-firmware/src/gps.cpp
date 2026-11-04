#include "gps.h"
#include <Arduino.h>
#include <cstdlib>
#include <cstring>

namespace {

/// Verifica el XOR entre '$' y '*' de una trama NMEA.
bool checksumOk(const char* sentence) {
    if (sentence[0] != '$') return false;

    char expected = 0;
    size_t i = 1;
    for (; sentence[i] != '\0' && sentence[i] != '*'; i++) {
        expected ^= sentence[i];
    }
    if (sentence[i] != '*') return false;

    const char* hex = sentence + i + 1;
    if (strlen(hex) < 2) return false;

    char* end = nullptr;
    const long declared = strtol(hex, &end, 16);
    if (end == hex) return false;

    return (expected & 0xFF) == (declared & 0xFF);
}

/// Convierte "ddmm.mmmm" + hemisferio a grados decimales.
double nmeaToDegrees(const char* value, const char* hemisphere) {
    if (value == nullptr || value[0] == '\0') return 0.0;

    const double raw = atof(value);
    const double degrees = static_cast<int>(raw / 100.0);
    const double minutes = raw - degrees * 100.0;
    double result = degrees + minutes / 60.0;

    if (hemisphere != nullptr && (hemisphere[0] == 'S' || hemisphere[0] == 'W')) {
        result = -result;
    }
    return result;
}

}  // namespace

bool GpsModule::begin() {
    Serial1.begin(GPS_BAUD, SERIAL_8N1, GPS_RX_PIN, GPS_TX_PIN);
    Serial.printf("[GPS] ATGM336H en UART%d (RX=%d, TX=%d) a %d baudios\n",
                  GPS_UART_PORT, GPS_RX_PIN, GPS_TX_PIN, GPS_BAUD);
    return true;
}

bool GpsModule::poll(unsigned long nowMs) {
    bool newSentence = false;

    while (Serial1.available() > 0) {
        const char c = static_cast<char>(Serial1.read());

        if (c == '$') {
            _receiving = true;
            _lineLength = 0;
            _line[_lineLength++] = c;
            continue;
        }

        if (!_receiving) continue;

        if (c == '\n') {
            _line[_lineLength] = '\0';
            _receiving = false;
            if (_lineLength > 0) {
                handleLine(_line, nowMs);
                newSentence = true;
            }
            _lineLength = 0;
            continue;
        }

        if (c == '\r') continue;

        // Una trama más larga que el buffer se descarta entera.
        if (_lineLength + 1 >= sizeof(_line)) {
            _receiving = false;
            _lineLength = 0;
            continue;
        }

        _line[_lineLength++] = c;
    }

    return newSentence;
}

void GpsModule::handleLine(char* line, unsigned long nowMs) {
    if (!checksumOk(line)) return;

    // GGA es la única trama con calidad de fix y altitud.
    if (strstr(line, "GGA") == nullptr) return;

    parseGga(line, nowMs);
}

void GpsModule::parseGga(char* sentence, unsigned long nowMs) {
    // $GNGGA,hhmmss.ss,ddmm.mmmm,N,dddmm.mmmm,E,calidad,sats,hdop,altitud,M,...
    char* fields[16] = {};
    size_t count = 0;

    char* cursor = sentence;
    while (cursor != nullptr && count < 16) {
        fields[count++] = cursor;
        char* comma = strchr(cursor, ',');
        if (comma == nullptr) break;
        *comma = '\0';
        cursor = comma + 1;
    }

    if (count < 10) return;

    const int quality = atoi(fields[6]);
    GpsFix fix;
    fix.satellites = static_cast<uint8_t>(atoi(fields[7]));
    fix.hdop = atof(fields[8]);
    fix.altitudeMeters = atof(fields[9]);
    fix.updatedAtMs = nowMs;

    if (quality > 0) {
        fix.valid = true;
        fix.latitude = nmeaToDegrees(fields[2], fields[3]);
        fix.longitude = nmeaToDegrees(fields[4], fields[5]);

        // Un fix sin coordenadas no sirve: es peor que no tener fix.
        if (fix.latitude == 0.0 && fix.longitude == 0.0) {
            fix.valid = false;
        }
    }

    if (!fix.valid) {
        // Sin fix se conservan los satélites y el estado, nunca una posición vieja.
        fix.latitude = 0.0;
        fix.longitude = 0.0;
        fix.altitudeMeters = 0.0f;
    }

    _fix = fix;
}
