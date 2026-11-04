#pragma once

#include <U8g2lib.h>
#include <Wire.h>
#include "config.h"

/// Driver para pantalla OLED 0.96" I2C (SSD1306 128x64) en la XIAO ESP32-S3.
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
    // El constructor recibe clock y data para que U8g2 inicialice Wire en los
    // pines de la XIAO (D4/D5) y no en los I2C por defecto del ESP32-S3.
    U8G2_SSD1306_128X64_NONAME_F_HW_I2C _u8g2;

    void drawStatusBar(bool bleConnected, const char* state);
    void drawFifoFace(const char* state, float audioLevel, unsigned long nowMs);
    void drawBottomStatus(const char* state, const char* text);
};
