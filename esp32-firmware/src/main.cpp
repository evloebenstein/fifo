#include <Arduino.h>
#include "config.h"
#include "i2s_mic.h"
#include "display.h"
#include "ble_audio_service.h"

// ── Instancias de hardware ──────────────────────────
I2SMic mic;
Display oled;
BleAudioService& bleService = BleAudioService::getInstance();

DisplayData displayData;
SemaphoreHandle_t displayMutex = nullptr;

// ── Tarea 1: Captura de Audio del Micrófono (Core 1) ─
void micTask(void* param) {
    Serial.println("[TASK] micTask iniciada en Core " + String(xPortGetCoreID()));

    int16_t sampleBuffer[CHUNK_SAMPLES];

    while (true) {
        // Lee 256 muestras PCM (16ms de audio a 16kHz) desde el ADC DMA continuo
        size_t readCount = mic.read(sampleBuffer, CHUNK_SAMPLES);

        if (readCount > 0 && bleService.isConnected()) {
            bleService.sendAudio(sampleBuffer, readCount);
        }

        // Si no hay datos inmediatos, pequeña pausa para no saturar el watchdog
        if (readCount == 0) {
            vTaskDelay(pdMS_TO_TICKS(5));
        }
    }
}

// ── Tarea 2: Refresco de Pantalla OLED (Core 0) ──────
void displayTask(void* param) {
    Serial.println("[TASK] displayTask iniciada en Core " + String(xPortGetCoreID()));

    while (true) {
        DisplayData localData;
        if (xSemaphoreTake(displayMutex, pdMS_TO_TICKS(50)) == pdTRUE) {
            localData = displayData;
            xSemaphoreGive(displayMutex);
        }

        oled.update(localData);
        vTaskDelay(pdMS_TO_TICKS(100)); // 10 FPS
    }
}

// ═══════════════════════════════════════════════════════
//  SETUP & LOOP
// ═══════════════════════════════════════════════════════
void setup() {
    Serial.begin(115200);
    delay(500);

    Serial.println();
    Serial.println("============================================");
    Serial.println("  FIFO Voice Pipeline — 100% BLE (ESP32-S3) ");
    Serial.println("  Mic UCC (GPIO 1) | OLED 1.3\" (SDA 8, SCL 9)");
    Serial.println("============================================");

    displayMutex = xSemaphoreCreateMutex();

    // 1. Pantalla OLED
    if (oled.begin()) {
        oled.showSplash();
        delay(1200);
        oled.showBleWaiting();
    } else {
        Serial.println("[WARN] OLED no detectada — continuando en modo headless");
    }

    // 2. Micrófono Analógico UCC
    if (!mic.begin()) {
        Serial.println("[ERROR] Error iniciando micrófono");
    }

    // 3. Servicio BLE (Audio Stream + Pantalla)
    bleService.begin(&displayData, displayMutex);

    // 4. Iniciar tareas FreeRTOS
    xTaskCreatePinnedToCore(micTask,     "mic",  MIC_TASK_STACK,     NULL, MIC_TASK_PRIORITY, NULL, 1);
    xTaskCreatePinnedToCore(displayTask, "disp", DISPLAY_TASK_STACK, NULL, DISPLAY_TASK_PRIO, NULL, 0);

    Serial.println("[READY] Sistema listo. Esperando conexión BLE desde la App...\n");
}

void loop() {
    // El trabajo pesado ocurre en las tareas FreeRTOS
    vTaskDelay(pdMS_TO_TICKS(1000));
}
