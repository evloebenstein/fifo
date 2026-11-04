# ⚡ Firmware Source Map (`esp32-firmware/src/`)

Código fuente modular en C++ para la Seeed XIAO ESP32-S3 de Fifo.

---

## 🗺️ Mapa de Archivos del Directorio

| Archivo | Rol y Responsabilidad |
| :--- | :--- |
| [`config.h`](config.h) | **Configuración central del hardware**: pines de la OLED (I2C), del INMP441 (I2S0 RX) y del MAX98357A (I2S1 TX), pin y tiempos del pulsador, parámetros de audio (mic 16 kHz, parlante 8 kHz), UUIDs GATT y tamaños de cola. |
| [`main.cpp`](main.cpp) | **Punto de entrada (`setup` y `loop`)**: inicializa pantalla, micrófono, parlante y servidor BLE, y crea las cuatro tareas FreeRTOS (micrófono, parlante, pulsador y rostro). |
| [`ble_audio_service.h`](ble_audio_service.h) / [`.cpp`](ble_audio_service.cpp) | **Servidor BLE**: características de audio (notify), display (write), parlante (write) y pulsador (notify). No reproduce ni decide: encola el audio y publica el gesto. |
| [`display.h`](display.h) / [`.cpp`](display.cpp) | **Rostro en OLED SSD1306 0.96"**: ojos animados, ondas de escucha, boca sincronizada y subtítulos. |
| [`i2s_mic.h`](i2s_mic.h) / [`.cpp`](i2s_mic.cpp) | **Micrófono INMP441**: lee ranuras de 32 bits por DMA, las desplaza a PCM 16 bits y quita la deriva de continua. |
| [`i2s_speaker.h`](i2s_speaker.h) / [`.cpp`](i2s_speaker.cpp) | **Parlante MAX98357A**: cola de 400 ms en un stream buffer y volcado al DMA. El callback de BLE nunca espera al hardware: si la cola está llena, descarta el bloque. |
| [`button.h`](button.h) / [`.cpp`](button.cpp) | **Pulsador**: antirrebote y detección de pulsación corta o larga. Solo clasifica el gesto. |

---

## 🔄 Ciclo de Vida y Flujo de Datos

```
[ INMP441 Mic ] ──► i2s_mic.cpp ──► ble_audio_service.cpp ──► (BLE) ──► [ Android App ]
                                                                                │
[ SSD1306 OLED] ◄── display.cpp ◄── ble_audio_service.cpp ◄── (BLE) ◄──────────┤
                                                                                │
[ MAX98357A Spk] ◄── i2s_speaker.cpp ◄── ble_audio_service.cpp ◄── (BLE) ◄─────┤
                                                                                │
[ Pulsador     ] ──► button.cpp ──► ble_audio_service.cpp ──► (BLE) ──►─────────┘
```

---

## 🧭 Reglas de Programación en `esp32-firmware/src/`

1. **Un dueño por hardware.** Solo `i2s_mic.cpp` toca el I2S0 y solo
   `i2s_speaker.cpp` el I2S1. `ble_audio_service.cpp` no configura pines.
2. **El callback de BLE nunca se bloquea.** El audio que baja se encola con
   `submit()` (sin espera). Si no cabe, se descarta el bloque completo: nunca se
   recorta una muestra a la mitad.
3. **Nada de `delay()` largos en `main.cpp`.** El trabajo vive en tareas; el
   `loop()` solo duerme.
4. **El pulsador no ejecuta acciones.** Clasifica el gesto y lo notifica; la
   decisión (despertar, silenciar) es del celular.
5. **Los buffers de audio son múltiplos de 512 bytes** para caber en un paquete
   BLE con MTU 517 sin fragmentación.
