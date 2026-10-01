#pragma once

#include <cstdint>
#include <cstddef>
#include <driver/adc.h>

// ═══════════════════════════════════════════════════════
//  CONFIGURACIÓN GENERAL — FIFO Voice Pipeline (BLE)
//  100% Bluetooth: Sin Hotspot ni dependencias Wi-Fi
// ═══════════════════════════════════════════════════════

// ── Bluetooth Low Energy (BLE) ──────────────────────
#define BLE_DEVICE_NAME         "FIFO-S3"
#define BLE_SERVICE_UUID        "0000ff10-0000-1000-8000-00805f9b34fb"
#define BLE_AUDIO_CHAR_UUID     "0000ff11-0000-1000-8000-00805f9b34fb"
#define BLE_DISPLAY_CHAR_UUID   "0000ff12-0000-1000-8000-00805f9b34fb"

// ── Micrófono Analógico (UCC con ganancia ajustable) ───────
// En ESP32-S3 usamos ADC1 Canal 0 (GPIO 1) con ADC DMA continuo.
#define MIC_ADC_PIN     1               // GPIO 1 (ADC1 Ch0)
#define MIC_ADC_UNIT    ADC_UNIT_1
#define MIC_ADC_CHANNEL ADC1_CHANNEL_0  // GPIO 1 = ADC1 Canal 0

// ── Pantalla OLED 1.3" I2C ─────────────────────────
// Pines estándar I2C del ESP32-S3
#define OLED_SDA        8    // I2C Data  (SDA default ESP32-S3)
#define OLED_SCL        9    // I2C Clock (SCL default ESP32-S3)
#define OLED_WIDTH      128
#define OLED_HEIGHT     64

// ── Audio ───────────────────────────────────────────
#define SAMPLE_RATE     16000                              // 16 kHz
#define CHUNK_SAMPLES   256                                // 256 muestras = 16ms de audio (512 bytes)
#define CHUNK_BYTES     (CHUNK_SAMPLES * sizeof(int16_t))  // 512 bytes (cabe perfecto en BLE MTU 512)

// ── FreeRTOS ────────────────────────────────────────
#define QUEUE_LENGTH       10
#define MIC_TASK_STACK     4096
#define BLE_TASK_STACK     4096
#define DISPLAY_TASK_STACK 4096
#define MIC_TASK_PRIORITY  5     // Alta — audio en tiempo real
#define DISPLAY_TASK_PRIO  2     // Baja — refresco visual

// ── LED de estado ───────────────────────────────────
#define STATUS_LED  2

// ── Estructura para estado del display ──────────────
// Sincronizada entre BLE y la tarea de dibujo OLED
struct DisplayData {
    bool     bleConnected = false;
    char     state[16]    = "INICIO";
    char     userText[64] = "";
    char     aiText[128]  = "";
    float    audioLevel   = 0.0f;  // 0.0 - 1.0
};
