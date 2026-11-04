# 🤖 Firmware Architecture (`esp32-firmware/`)

Firmware en C++ para la **Seeed XIAO ESP32-S3** estándar (8 MB de flash, sin
PSRAM), gestionado con **PlatformIO**. Es el cuerpo del robot de Fifo: captura
de voz por micrófono I2S, reproducción por amplificador I2S, rostro en pantalla
OLED I2C, pulsador físico y enlace Bluetooth Low Energy (BLE) con el teléfono
Android.

---

## 🗺️ Mapa de Archivos del Directorio

| Archivo / Carpeta | Tipo | Rol Principal |
| :--- | :--- | :--- |
| [`platformio.ini`](platformio.ini) | Configuración PlatformIO | Placa `seeed_xiao_esp32s3`, 8 MB de flash QIO sin PSRAM, particiones `default_8MB.csv`, monitor a 115200 baudios y librerías externas (WebSockets, U8g2). |
| [`src/`](src/README.md) | Subdirectorio C++ | Código fuente modular: drivers I2S de entrada y salida, servicio GATT BLE, rostro OLED, pulsador y composición de tareas. |

---

## 🔌 Hardware y Conexiones Principales (Pinout)

La XIAO ESP32-S3 solo expone nueve GPIO utilizables: D0–D5 y D8–D10. D6/D7 son
UART (GPIO 43/44) y quedan fuera; GPIO 19/20 son USB y GPIO 0 es BOOT.

```
        +----------------------------------------+
        |          XIAO ESP32-S3                 |
        |                                        |
        |  D4 / GPIO 5  ──────► OLED SSD1306 SDA |
        |  D5 / GPIO 6  ──────► OLED SSD1306 SCL |
        |  3V3          ──────► OLED VCC / INMP441 VDD / MAX98357A VIN
        |  GND          ──────► OLED GND / INMP441 GND+L-R / MAX98357A GND
        |                                        |
        |  D8 / GPIO 7  ──────► INMP441 SCK      |
        |  D9 / GPIO 8  ──────► INMP441 WS       |
        |  D10/ GPIO 9  ──────► INMP441 SD       |
        |                                        |
        |  D0 / GPIO 1  ──────► MAX98357A BCLK   |
        |  D1 / GPIO 2  ──────► MAX98357A LRC    |
        |  D3 / GPIO 4  ──────► MAX98357A DIN    |
        |                                        |
        |  D2 / GPIO 3  ──────► Pulsador ── GND  |
        +----------------------------------------+
```

| Bloque | Pines | Notas |
| :--- | :--- | :--- |
| OLED SSD1306 0.96" (I2C) | SDA = GPIO 5, SCL = GPIO 6 | Son los I2C por defecto de la XIAO; U8g2 los recibe por constructor. |
| INMP441 (I2S0 RX) | SCK = GPIO 7, WS = GPIO 8, SD = GPIO 9 | `L/R` a GND selecciona el canal izquierdo. El driver lee ranuras de 32 bits. |
| MAX98357A (I2S1 TX) | BCLK = GPIO 1, LRC = GPIO 2, DIN = GPIO 4 | Salida mono a 8 kHz, la frecuencia del enlace descendente. |
| Pulsador 6×6 | GPIO 3 → GND | `INPUT_PULLUP`; GPIO 3 es strapping de JTAG, se mantiene alto y solo se lee. |
| Interruptor SS12D07 | En serie con la batería LiPo | Corta la alimentación; el firmware no lo lee. |
| LED integrado | GPIO 21 | No se usa como indicador de estado. |

> **Por qué 8 kHz en el parlante.** El micrófono sube 16 kB/s (16 kHz × 2 B).
> El enlace BLE no aguanta dos flujos de 16 kHz a la vez, así que la voz del
> asistente baja a 8 kHz (16 kB/s) y el MAX98357A reproduce sin remuestreo.

---

## 📡 Características BLE

| UUID | Dirección | Contenido |
| :--- | :--- | :--- |
| `0000ff10-…` | Servicio | Agrupa las cuatro características. |
| `0000ff11-…` | ESP32 → celular (notify) | PCM 16-bit mono a 16 kHz del INMP441. |
| `0000ff12-…` | celular → ESP32 (write) | Estado y textos del rostro: `STATE\|USER\|AI\|LEVEL`. |
| `0000ff13-…` | celular → ESP32 (write) | Voz del asistente: `0x00` descarta la cola, `0x01` + PCM 16-bit LE a 8 kHz. |
| `0000ff14-…` | ESP32 → celular (notify) | Gestos del pulsador: `1` pulsación corta, `2` pulsación larga. |

El firmware solo transporta y clasifica: no decide qué significa un gesto ni
redacta respuestas. El celular es quien actúa (ADR-0055).

---

## 🚀 Compilación y Flasheo

```bash
# 1. Compilar firmware
pio run

# 2. Subir a la XIAO por USB-C
pio run --target upload

# 3. Abrir monitor serial para depurar
pio device monitor
```

La placa de PlatformIO describe la variante **con PSRAM**; `platformio.ini`
corrige `memory_type` a `qio_qspi` y retira `-DBOARD_HAS_PSRAM`. Si se cambia a
la XIAO ESP32-S3 **Plus** (8 MB de PSRAM), hay que revertir esos dos ajustes.
