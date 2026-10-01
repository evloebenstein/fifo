#pragma once

#include <U8g2lib.h>
#include <Wire.h>
#include "config.h"

/// Driver para pantalla OLED 1.3" I2C (SH1106 128x64) en ESP32-S3.
/// Muestra el rostro animado y expresivo de "Fifo" con animaciones
/// de escucha (ondas reactivas) y habla (apertura y cierre de boca sincronizado).
class Display {
public:
    Display();

    /// Inicializa I2C y la pantalla OLED.
    bool begin();

    /// Redibuja toda la pantalla con los datos actuales.
    void update(const DisplayData& data);

    /// Muestra splash screen de inicio.
    void showSplash();

    /// Muestra pantalla de espera BLE al encender.
    void showBleWaiting();

private:
    U8G2_SH1106_128X64_NONAME_F_HW_I2C _u8g2;

    void drawStatusBar(bool bleConnected, const char* state);
    void drawFifoFace(const char* state, float audioLevel, unsigned long nowMs);
    void drawBottomStatus(const char* state, const char* text);
};
