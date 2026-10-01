#include "display.h"
#include <Arduino.h>

Display::Display()
    : _u8g2(U8G2_R0, /* reset=*/ U8X8_PIN_NONE) {}

bool Display::begin() {
    Wire.begin(OLED_SDA, OLED_SCL);

    if (!_u8g2.begin()) {
        Serial.println("[OLED] Error inicializando pantalla");
        return false;
    }

    _u8g2.setContrast(200);
    Serial.println("[OLED] Pantalla 1.3\" inicializada OK (SDA=8, SCL=9)");
    return true;
}

void Display::showSplash() {
    _u8g2.clearBuffer();

    _u8g2.setFont(u8g2_font_helvB18_tr);
    int w = _u8g2.getStrWidth("FIFO");
    _u8g2.drawStr((128 - w) / 2, 28, "FIFO");

    _u8g2.setFont(u8g2_font_6x10_tr);
    w = _u8g2.getStrWidth("Tu amigo robot");
    _u8g2.drawStr((128 - w) / 2, 44, "Tu amigo robot");

    _u8g2.setFont(u8g2_font_5x8_tr);
    w = _u8g2.getStrWidth("Bluetooth Low Energy");
    _u8g2.drawStr((128 - w) / 2, 58, "Bluetooth Low Energy");

    _u8g2.sendBuffer();
}

void Display::showBleWaiting() {
    _u8g2.clearBuffer();

    // Dibujar cara en modo espera
    drawFifoFace("DESCONECTADO", 0.0f, millis());

    _u8g2.setFont(u8g2_font_5x8_tr);
    const char* msg = "Abre app para conectar";
    int w = _u8g2.getStrWidth(msg);
    _u8g2.drawStr((128 - w) / 2, 62, msg);

    _u8g2.sendBuffer();
}

void Display::update(const DisplayData& data) {
    _u8g2.clearBuffer();

    unsigned long now = millis();

    // 1. Barra de estado superior (y=0..10)
    drawStatusBar(data.bleConnected, data.state);

    // 2. Rostro expresivo y animado de Fifo en el centro (y=11..50)
    drawFifoFace(data.state, data.audioLevel, now);

    // 3. Barra o subtítulo inferior con estado o texto (y=52..64)
    if (!data.bleConnected) {
        _u8g2.setFont(u8g2_font_5x8_tr);
        const char* msg = "Esperando conexion BLE...";
        int w = _u8g2.getStrWidth(msg);
        _u8g2.drawStr((128 - w) / 2, 62, msg);
    } else {
        const char* textToShow = (strlen(data.aiText) > 0) ? data.aiText : data.userText;
        drawBottomStatus(data.state, textToShow);
    }

    _u8g2.sendBuffer();
}

void Display::drawStatusBar(bool bleConnected, const char* state) {
    _u8g2.setFont(u8g2_font_5x8_tr);
    _u8g2.drawStr(2, 8, "FIFO");

    if (bleConnected) {
        _u8g2.drawStr(100, 8, "[BLE]");
    } else {
        _u8g2.drawStr(78, 8, "[BUSCANDO]");
    }

    // Línea separadora sutil
    _u8g2.drawLine(0, 10, 127, 10);
}

void Display::drawFifoFace(const char* state, float audioLevel, unsigned long nowMs) {
    bool isListening = (strcmp(state, "ESCUCHANDO") == 0);
    bool isSpeaking  = (strcmp(state, "HABLANDO") == 0);
    bool isThinking  = (strcmp(state, "PENSANDO") == 0);
    bool isSleeping  = (strcmp(state, "DURMIENDO") == 0 || strcmp(state, "MUTED") == 0);

    // 1. Antena / Asa superior de Fifo (centrada en x=64, y=11..14)
    _u8g2.drawRFrame(57, 11, 14, 4, 1);

    // 2. Cabeza / Monitor de Fifo (centrada: w=68, h=36, x=30, y=14)
    _u8g2.drawRFrame(30, 14, 68, 36, 5);

    // 3. Mejillas sonrosadas (blush marks de Fifo)
    _u8g2.drawLine(34, 34, 38, 32);
    _u8g2.drawLine(90, 32, 94, 34);

    // 4. Ojos según estado
    if (isSleeping) {
        // Ojos cerrados durmiendo pacíficamente: - -
        _u8g2.drawLine(41, 26, 51, 26);
        _u8g2.drawLine(42, 27, 50, 27);
        _u8g2.drawLine(77, 26, 87, 26);
        _u8g2.drawLine(78, 27, 86, 27);

        // Zzz animado flotando sobre la cabeza
        int zPhase = (nowMs / 600) % 3;
        _u8g2.setFont(u8g2_font_5x8_tr);
        if (zPhase >= 0) _u8g2.drawStr(102, 22, "z");
        if (zPhase >= 1) _u8g2.drawStr(107, 16, "z");
        if (zPhase >= 2) _u8g2.drawStr(113, 10, "Z");
    } else if (isSpeaking) {
        // Ojos felices curvados al hablar: ^ ^
        _u8g2.drawLine(41, 28, 46, 23);
        _u8g2.drawLine(46, 23, 51, 28);
        _u8g2.drawLine(41, 29, 46, 24);
        _u8g2.drawLine(46, 24, 51, 29);

        _u8g2.drawLine(77, 28, 82, 23);
        _u8g2.drawLine(82, 23, 87, 28);
        _u8g2.drawLine(77, 29, 82, 24);
        _u8g2.drawLine(82, 24, 87, 29);
    } else if (isThinking) {
        // Ojos mirando arriba/lado pensativos
        int thinkOffset = ((nowMs / 300) % 2 == 0) ? -2 : 2;
        _u8g2.drawDisc(46 + thinkOffset, 25, 4);
        _u8g2.drawDisc(82 + thinkOffset, 25, 4);

        // Brillo interior
        _u8g2.setDrawColor(0);
        _u8g2.drawDisc(48 + thinkOffset, 24, 1);
        _u8g2.drawDisc(84 + thinkOffset, 24, 1);
        _u8g2.setDrawColor(1);

        // Burbujas de pensamiento sobre la cabeza
        int bubbleStep = (nowMs / 250) % 3;
        if (bubbleStep >= 0) _u8g2.drawPixel(102, 14);
        if (bubbleStep >= 1) _u8g2.drawDisc(106, 12, 1);
        if (bubbleStep >= 2) _u8g2.drawCircle(111, 9, 2);
    } else if (isListening) {
        // Ojos abiertos y atentos al sonido
        int eyeR = (audioLevel > 0.25f) ? 6 : 5;
        _u8g2.drawDisc(46, 26, eyeR);
        _u8g2.drawDisc(82, 26, eyeR);

        // Brillo de pupila
        _u8g2.setDrawColor(0);
        _u8g2.drawDisc(48, 24, 2);
        _u8g2.drawDisc(84, 24, 2);
        _u8g2.setDrawColor(1);

        // Cejas curiosas arriba
        _u8g2.drawLine(41, 19, 51, 19);
        _u8g2.drawLine(77, 19, 87, 19);

        // ONDAS SONORAS DE ESCUCHA (a ambos lados de la cabeza)
        int wavePhase = (nowMs / 140) % 3;
        // Izquierda
        _u8g2.drawLine(25, 26, 23, 30);
        _u8g2.drawLine(23, 30, 25, 34);
        if (wavePhase >= 1 || audioLevel > 0.15f) {
            _u8g2.drawLine(20, 23, 17, 30);
            _u8g2.drawLine(17, 30, 20, 37);
        }
        if (wavePhase >= 2 || audioLevel > 0.35f) {
            _u8g2.drawLine(15, 20, 11, 30);
            _u8g2.drawLine(11, 30, 15, 40);
        }

        // Derecha
        _u8g2.drawLine(103, 26, 105, 30);
        _u8g2.drawLine(105, 30, 103, 34);
        if (wavePhase >= 1 || audioLevel > 0.15f) {
            _u8g2.drawLine(108, 23, 111, 30);
            _u8g2.drawLine(111, 30, 108, 37);
        }
        if (wavePhase >= 2 || audioLevel > 0.35f) {
            _u8g2.drawLine(113, 20, 117, 30);
            _u8g2.drawLine(117, 30, 113, 40);
        }
    } else {
        // En reposo: Ojos abiertos con parpadeo periódico cada 3.5 segundos
        bool isBlinking = ((nowMs % 3500) < 180);
        if (isBlinking) {
            _u8g2.drawLine(42, 27, 50, 27);
            _u8g2.drawLine(78, 27, 86, 27);
        } else {
            _u8g2.drawDisc(46, 26, 5);
            _u8g2.drawDisc(82, 26, 5);
            _u8g2.setDrawColor(0);
            _u8g2.drawDisc(48, 24, 2);
            _u8g2.drawDisc(84, 24, 2);
            _u8g2.setDrawColor(1);
        }
    }

    // 5. Boca animada
    if (isSleeping) {
        // Boca tranquila dormida
        _u8g2.drawLine(61, 38, 67, 38);
    } else if (isSpeaking) {
        // ANIMACIÓN DE HABLA REAL: 4 fases rápidas de apertura/cierre
        int mouthAnim = (nowMs / 120) % 4;
        switch (mouthAnim) {
            case 0:
                // Boca entreabierta pequeña
                _u8g2.drawRBox(59, 38, 10, 4, 1);
                break;
            case 1:
                // Boca abierta mediana hablando
                _u8g2.drawRBox(57, 36, 14, 7, 2);
                break;
            case 2:
                // Boca bien abierta
                _u8g2.drawRBox(55, 35, 18, 9, 3);
                // Dientes / efecto caricatura
                _u8g2.setDrawColor(0);
                _u8g2.drawLine(58, 37, 70, 37);
                _u8g2.setDrawColor(1);
                break;
            case 3:
                // Boca cerrándose
                _u8g2.drawRBox(58, 37, 12, 5, 2);
                break;
        }

        // Ondas de voz bajo la boca
        int speakWave = (nowMs / 150) % 2;
        if (speakWave == 0) {
            _u8g2.drawPixel(62, 47);
            _u8g2.drawPixel(66, 47);
        } else {
            _u8g2.drawLine(60, 48, 68, 48);
        }
    } else if (isListening) {
        // Boca atenta: pequeña 'o' curiosa
        _u8g2.drawCircle(64, 38, 3);
    } else if (isThinking) {
        // Boca recta pensativa
        _u8g2.drawLine(60, 39, 68, 39);
    } else {
        // Sonrisa alegre en reposo
        _u8g2.drawCircle(64, 35, 8, U8G2_DRAW_LOWER_LEFT | U8G2_DRAW_LOWER_RIGHT);
    }
}

void Display::drawBottomStatus(const char* state, const char* text) {
    _u8g2.setFont(u8g2_font_5x8_tr);

    const char* label = state;
    if (strcmp(state, "ESCUCHANDO") == 0) label = "Escuchando...";
    else if (strcmp(state, "HABLANDO") == 0) label = "Fifo te responde...";
    else if (strcmp(state, "PENSANDO") == 0) label = "Pensando...";
    else if (strcmp(state, "DURMIENDO") == 0) label = "Durmiendo · Di 'Fifo'";
    else if (strcmp(state, "LISTO") == 0) label = "Listo - Habla con Fifo";
    else if (strcmp(state, "MUTED") == 0) label = "Mic Silenciado (Mute)";

    if (strlen(text) > 0) {
        char buf[26];
        strncpy(buf, text, sizeof(buf) - 1);
        buf[sizeof(buf) - 1] = '\0';
        int w = _u8g2.getStrWidth(buf);
        _u8g2.drawStr((128 - w) / 2, 62, buf);
    } else {
        int w = _u8g2.getStrWidth(label);
        _u8g2.drawStr((128 - w) / 2, 62, label);
    }
}
