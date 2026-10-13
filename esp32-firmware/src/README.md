# ⚡ ESP32 Source Code Map (`esp32-firmware/src/`)

Código fuente modular en C++ para el microcontrolador ESP32 de Fifo.

---

## 🗺️ Mapa de Archivos del Directorio

| Archivo | Rol y Responsabilidad |
| :--- | :--- |
| [`config.h`](config.h) | **Configuración central del hardware**: Mapeo de pines GPIO (I2C para OLED, I2S para micrófono y parlante), parámetros de audio (16,000 Hz, 16-bit mono), UUIDs para el servicio GATT BLE de audio y display, y constantes de sincronización. |
| [`main.cpp`](main.cpp) | **Punto de entrada (`setup` y `loop`)**: Inicializa el monitor serial, la pantalla OLED, el bus I2S y el servidor BLE. En el bucle principal orquesta la lectura de tramas de micrófono hacia el buffer BLE y la actualización de las expresiones faciales. |
| [`ble_audio_service.h`](ble_audio_service.h) / [`.cpp`](ble_audio_service.cpp) | **Servidor Bluetooth Low Energy**: Gestiona el servicio GATT con características para streaming bidireccional de paquetes de voz hacia el celular Android y recepción de comandos de estado de la pantalla OLED (`ESCUCHANDO`, `HABLANDO`, `PENSANDO`, `DURMIENDO`). |
| [`display.h`](display.h) / [`.cpp`](display.cpp) | **Controlador de Pantalla OLED SSD1306 (128x64)**: Dibuja los ojos animados y expresiones faciales de Fifo (parpadeo, mirada atenta, boca que habla con modulación de volumen) y renderiza subtítulos de texto para el usuario. |
| [`i2s_mic.h`](i2s_mic.h) / [`.cpp`](i2s_mic.cpp) | **Driver de Micrófono I2S (INMP441)**: Lee muestras de audio PCM mono de 16 bits en tiempo real desde el puerto I2S0 utilizando DMA para no bloquear el CPU. |
| [`i2s_speaker.h`](i2s_speaker.h) / [`.cpp`](i2s_speaker.cpp) | **Driver de Parlante I2S (MAX98357A)**: Escribe paquetes de audio decodificados al puerto I2S1 para reproducir la voz de Fifo a través del altavoz integrado del robot. |

---

## 🔄 Ciclo de Vida y Flujo de Datos

```
[ INMP441 Mic ] ──► i2s_mic.cpp ──► ble_audio_service.cpp ──► (BLE) ──► [ Android App ]
                                                                               │
[ SSD1306 OLED] ◄── display.cpp ◄── ble_audio_service.cpp ◄── (BLE) ◄──────────┤
                                                                               │
[ MAX98357A Spk] ◄── i2s_speaker.cpp ◄── ble_audio_service.cpp ◄── (BLE) ◄─────┘
```

---

## 🧭 Reglas de Programación en `esp32-firmware/src/`
1. **DMA y Buffers:** El tamaño de buffer en `i2s_mic.cpp` debe ser potencia de 2 y múltiplo de los paquetes MTU de BLE (típicamente 256 o 512 bytes).
2. **Prioridad de Tareas:** Si se usa FreeRTOS, la tarea de audio I2S debe correr en Core 0 con alta prioridad, mientras el display y BLE corren en Core 1 para evitar cortes en el audio.
3. **No bloquear el bucle:** Evitar `delay()` mayores a 10ms en `main.cpp`; utilizar `millis()` para las animaciones del display.
