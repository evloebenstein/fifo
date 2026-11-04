#include "button.h"
#include <Arduino.h>

void Button::begin() {
    pinMode(BUTTON_PIN, INPUT_PULLUP);
    _stable = true;
    _lastRead = true;
    _longFired = false;
    _lastChange = millis();
    _pressedAt = 0;
    Serial.printf("[BTN] Pulsador inicializado en GPIO %d (activo bajo)\n", BUTTON_PIN);
}

ButtonEvent Button::poll(unsigned long nowMs) {
    const bool raw = digitalRead(BUTTON_PIN) != LOW;  // true = suelto

    if (raw != _lastRead) {
        _lastRead = raw;
        _lastChange = nowMs;
    }

    // Mientras el pin no lleve estable el tiempo de antirrebote no se decide nada.
    if (nowMs - _lastChange < BUTTON_DEBOUNCE_MS) {
        return ButtonEvent::NONE;
    }

    ButtonEvent event = ButtonEvent::NONE;

    if (raw != _stable) {
        _stable = raw;
        if (!_stable) {
            // Flanco de bajada: comienza una pulsación.
            _pressedAt = nowMs;
            _longFired = false;
        } else if (!_longFired && nowMs - _pressedAt >= BUTTON_DEBOUNCE_MS) {
            // Flanco de subida sin haber disparado la larga.
            event = ButtonEvent::SHORT_PRESS;
        }
    }

    // La pulsación larga se anuncia al alcanzar el umbral, sin esperar a soltar.
    if (!_stable && !_longFired && nowMs - _pressedAt >= BUTTON_LONG_PRESS_MS) {
        _longFired = true;
        event = ButtonEvent::LONG_PRESS;
    }

    return event;
}
