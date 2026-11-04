#pragma once

#include "config.h"
#include <cstddef>
#include <cstdint>
#include <freertos/FreeRTOS.h>
#include <freertos/stream_buffer.h>

/// Amplificador DAC MAX98357A por I2S.
///
/// Reproduce PCM 16-bit mono a 8 kHz, que es la frecuencia del audio que baja
/// del celular por BLE. El audio recibido entra en una cola de stream buffer y
/// la tarea de parlante la vacía hacia el DMA: el callback de BLE nunca se
/// bloquea esperando al hardware.
class I2SSpeaker {
public:
    /// Inicializa el puerto I2S en modo TX y la cola de reproducción.
    bool begin();

    /// Encola PCM 16-bit little endian venido de BLE.
    /// No bloquea: si la cola no tiene espacio, descarta el bloque completo
    /// para no romper la alineación de muestras.
    bool submit(const uint8_t* pcmBytes, size_t byteCount);

    /// Descarta lo que quede en la cola (fin de locución o barge-in).
    void flush();

    /// Saca muestras de la cola y las escribe en I2S.
    /// @param waitTicks Tiempo máximo a esperar por datos.
    /// @return Número de muestras escritas.
    size_t pump(TickType_t waitTicks);

    /// Escribe muestras PCM 16-bit directamente al altavoz.
    size_t write(const int16_t* buffer, size_t sampleCount);

    /// Libera el driver I2S y la cola.
    void end();

private:
    bool _initialized = false;
    StreamBufferHandle_t _queue = nullptr;
    int16_t _chunk[SPEAKER_CHUNK_SAMPLES] = {};
};
