#include <Arduino.h>
#include "config.h"
#include "button.h"
#include "gps.h"
#include "i2s_mic.h"
#include "i2s_speaker.h"
#include "display.h"
#include "ble_audio_service.h"

// ── Instancias de hardware ──────────────────────────
I2SMic mic;
I2SSpeaker speaker;
Display oled;
Button button;
GpsModule gps;
BleAudioService& bleService = BleAudioService::getInstance();

DisplayData displayData;
SemaphoreHandle_t displayMutex = nullptr;

// ── Tarea 1: Captura de Audio del Micrófono (Core 1) ─
void micTask(void* param) {
    Serial.println("[TASK] micTask iniciada en Core " + String(xPortGetCoreID()));

    int16_t sampleBuffer[CHUNK_SAMPLES];

    while (true) {
        // Lee 256 muestras PCM (16ms de audio a 16kHz) desde el INMP441
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

// ── Tarea 2: Reproducción del parlante (Core 1) ──────
// Duerme en la cola hasta que el celular manda voz; nunca hace polling.
void speakerTask(void* param) {
    Serial.println("[TASK] speakerTask iniciada en Core " + String(xPortGetCoreID()));

    while (true) {
        speaker.pump(portMAX_DELAY);
    }
}

// ── Tarea 3: Pulsador (Core 0) ──────────────────────
// Solo clasifica el gesto y lo notifica: la acción la decide el celular.
void buttonTask(void* param) {
    Serial.println("[TASK] buttonTask iniciada en Core " + String(xPortGetCoreID()));

    button.begin();
    while (true) {
        switch (button.poll(millis())) {
            case ButtonEvent::SHORT_PRESS:
                bleService.notifyButton(BUTTON_EVENT_SHORT);
                break;
            case ButtonEvent::LONG_PRESS:
                bleService.notifyButton(BUTTON_EVENT_LONG);
                break;
            case ButtonEvent::NONE:
            default:
                break;
        }
        vTaskDelay(pdMS_TO_TICKS(10));
    }
}

// ── Tarea 4: GPS/GNSS ATGM336H (Core 0) ─────────────
// Lee NMEA del UART, actualiza el fix y lo publica por BLE cada segundo.
void gpsTask(void* param) {
    Serial.println("[TASK] gpsTask iniciada en Core " + String(xPortGetCoreID()));

    if (!gps.begin()) {
        Serial.println("[WARN] GPS no detectado — continuando sin GNSS");
        vTaskDelete(NULL);
        return;
    }

    unsigned long lastPublish = 0;

    while (true) {
        gps.poll(millis());

        // Publicar el fix por BLE una vez por segundo (o cuando cambia a válido).
        unsigned long now = millis();
        if (now - lastPublish >= GPS_PUBLISH_MS && bleService.isConnected()) {
            bleService.sendGpsFix(gps.fix());
            lastPublish = now;
        }

        vTaskDelay(pdMS_TO_TICKS(20));
    }
}

// ── Tarea 5: Refresco de Pantalla OLED (Core 0) ──────
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
    Serial.println("  FIFO Voice Pipeline — 100% BLE (XIAO S3) ");
    Serial.println("  Mic INMP441 | OLED SSD1306 | MAX98357A    ");
    Serial.println("  GPS ATGM336H | Pulsador                    ");
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

    // 2. Micrófono I2S INMP441
    if (!mic.begin()) {
        Serial.println("[ERROR] Error iniciando micrófono");
    }

    // 3. Parlante I2S MAX98357A
    if (!speaker.begin()) {
        Serial.println("[ERROR] Error iniciando parlante");
    }

    // 4. Servicio BLE (Audio + Pantalla + Parlante + Pulsador + GPS)
    bleService.begin(&displayData, displayMutex, &speaker);

    // 5. Iniciar tareas FreeRTOS
    xTaskCreatePinnedToCore(micTask,     "mic",  MIC_TASK_STACK,     NULL, MIC_TASK_PRIORITY, NULL, 1);
    xTaskCreatePinnedToCore(speakerTask, "spk",  SPEAKER_TASK_STACK, NULL, SPK_TASK_PRIORITY, NULL, 1);
    xTaskCreatePinnedToCore(buttonTask,  "btn",  BUTTON_TASK_STACK,  NULL, DISPLAY_TASK_PRIO, NULL, 0);
    xTaskCreatePinnedToCore(gpsTask,     "gps",  GPS_TASK_STACK,     NULL, GPS_TASK_PRIORITY, NULL, 0);
    xTaskCreatePinnedToCore(displayTask, "disp", DISPLAY_TASK_STACK, NULL, DISPLAY_TASK_PRIO, NULL, 0);

    Serial.println("[READY] Sistema listo. Esperando conexión BLE desde la App...\n");
}

void loop() {
    // El trabajo pesado ocurre en las tareas FreeRTOS
    vTaskDelay(pdMS_TO_TICKS(1000));
}
