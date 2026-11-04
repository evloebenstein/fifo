#pragma once

#include <cstdint>
#include <cstddef>

// ═══════════════════════════════════════════════════════
//  CONFIGURACIÓN GENERAL — FIFO Voice Pipeline (BLE)
//  Hardware: Seeed XIAO ESP32-S3 (8 MB, sin PSRAM)
//  100% Bluetooth: sin Hotspot ni dependencias Wi-Fi
// ═══════════════════════════════════════════════════════

// ── Bluetooth Low Energy (BLE) ──────────────────────
// ff11: micrófono ESP32 → celular (notify)
// ff12: pantalla OLED, celular → ESP32 (write)
// ff13: parlante, celular → ESP32 (write, PCM 8 kHz)
// ff14: pulsador, ESP32 → celular (notify)
#define BLE_DEVICE_NAME         "FIFO-S3"
#define BLE_SERVICE_UUID        "0000ff10-0000-1000-8000-00805f9b34fb"
#define BLE_AUDIO_CHAR_UUID     "0000ff11-0000-1000-8000-00805f9b34fb"
#define BLE_DISPLAY_CHAR_UUID   "0000ff12-0000-1000-8000-00805f9b34fb"
#define BLE_SPEAKER_CHAR_UUID   "0000ff13-0000-1000-8000-00805f9b34fb"
#define BLE_BUTTON_CHAR_UUID    "0000ff14-0000-1000-8000-00805f9b34fb"
#define BLE_GPS_CHAR_UUID       "0000ff15-0000-1000-8000-00805f9b34fb"

// ── Pantalla OLED 0.96" I2C (SSD1306 128x64) ─────────
// SDA/SCL en los pines I2C por defecto de la XIAO (D4/D5).
#define OLED_SDA        5    // D4
#define OLED_SCL        6    // D5
#define OLED_WIDTH      128
#define OLED_HEIGHT     64

// ── Micrófono I2S INMP441 (I2S_NUM_0, solo RX) ───────
// INMP441 entrega 24 bits en una ranura de 32; el driver los desplaza.
// L/R a GND selecciona el canal izquierdo.
#define MIC_I2S_PORT        0
#define MIC_I2S_SCK         7    // D8  → SCK
#define MIC_I2S_WS          8    // D9  → WS (LRCLK)
#define MIC_I2S_SD          9    // D10 → SD (DIN)
#define MIC_BITS_PER_SAMPLE I2S_BITS_PER_SAMPLE_32BIT
#define MIC_SAMPLE_SHIFT    16   // 24 bits alineados a la izquierda → 16 bits

// ── Amplificador I2S MAX98357A (I2S_NUM_1, solo TX) ──
// El canal de salida es el izquierdo; SD del módulo selecciona L/R.
#define SPK_I2S_PORT    1
#define SPK_I2S_BCLK    1    // D0 → BCLK
#define SPK_I2S_LRC     2    // D1 → LRC (WS)
#define SPK_I2S_DIN     4    // D3 → DIN

// ── Pulsador (a GND, pull-up interno) ────────────────
// GPIO3 (D2) es strapping de JTAG: se mantiene alto por pull-up y solo se
// lee como entrada, así que no altera el arranque.
#define BUTTON_PIN              3    // D2
#define BUTTON_DEBOUNCE_MS      25
#define BUTTON_LONG_PRESS_MS    800

// Eventos de pulsador enviados al celular por ff14
#define BUTTON_EVENT_SHORT      1
#define BUTTON_EVENT_LONG       2

// ── GPS/GNSS ATGM336H (GPS + BeiDou, UART NMEA 0183) ─
// Usa el UART que la XIAO expone en D6/D7: los dos únicos pines que quedaban.
// El módulo habla a 9600 baudios por defecto y emite GGA/RMC a 1 Hz.
#define GPS_UART_PORT       1
#define GPS_BAUD            9600
#define GPS_RX_PIN          44   // D7 → TX del ATGM336H
#define GPS_TX_PIN          43   // D6 → RX del ATGM336H
#define GPS_LINE_MAX        96
#define GPS_PUBLISH_MS      1000
#define GPS_TASK_STACK      3072
#define GPS_TASK_PRIORITY   1

// ── LED de estado ───────────────────────────────────
#define STATUS_LED  21   // LED amarillo integrado de la XIAO

// ── Audio ───────────────────────────────────────────
#define SAMPLE_RATE         16000                              // Mic → celular
#define CHUNK_SAMPLES       256                                // 256 muestras = 16 ms
#define CHUNK_BYTES         (CHUNK_SAMPLES * sizeof(int16_t))  // 512 bytes (MTU 512)

// El enlace BLE no soporta dos flujos de 16 kHz: el TTS baja a 8 kHz mono,
// que son 16 kB/s, y el ESP32 lo reproduce sin remuestreo.
#define SPEAKER_SAMPLE_RATE 8000
#define SPEAKER_CHUNK_SAMPLES 256                              // 32 ms de audio
#define SPEAKER_QUEUE_BYTES (SPEAKER_SAMPLE_RATE * 2 * 40 / 100) // 400 ms de colchón

// ── FreeRTOS ────────────────────────────────────────
#define QUEUE_LENGTH       10
#define MIC_TASK_STACK     4096
#define BLE_TASK_STACK     4096
#define DISPLAY_TASK_STACK 4096
#define SPEAKER_TASK_STACK 4096
#define BUTTON_TASK_STACK  3072
#define MIC_TASK_PRIORITY  5     // Alta — audio en tiempo real
#define SPK_TASK_PRIORITY  4     // Alta — vaciar la cola antes de que desborde
#define DISPLAY_TASK_PRIO  2     // Baja — refresco visual

// ── Estructura para estado del display ──────────────
// Sincronizada entre BLE y la tarea de dibujo OLED
struct DisplayData {
    bool     bleConnected = false;
    char     state[16]    = "INICIO";
    char     userText[64] = "";
    char     aiText[128]  = "";
    float    audioLevel   = 0.0f;  // 0.0 - 1.0
};
