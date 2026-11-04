#pragma once

#include "config.h"
#include <cstdint>

/// Eventos que produce el pulsador del robot.
enum class ButtonEvent : uint8_t {
    NONE = 0,
    SHORT_PRESS = BUTTON_EVENT_SHORT,
    LONG_PRESS = BUTTON_EVENT_LONG,
};

/// Pulsador físico con antirrebote y detección de pulsación larga.
///
/// No ejecuta acciones: solo clasifica el gesto. Quien decide qué hacer es el
/// celular, que recibe el evento por la característica BLE ff14.
class Button {
public:
    /// Configura el pin como entrada con pull-up.
    void begin();

    /// Avanza la máquina de estados y devuelve el evento, si lo hay.
    /// Debe llamarse con frecuencia (cada 10–20 ms).
    ButtonEvent poll(unsigned long nowMs);

private:
    bool _stable = true;           // true = suelto (pull-up)
    bool _lastRead = true;
    bool _longFired = false;
    unsigned long _lastChange = 0;
    unsigned long _pressedAt = 0;
};
