# 🤖 ESP32 Firmware Architecture (`esp32-firmware/`)

Firmware en C++ para el microcontrolador **ESP32**, gestionado con **PlatformIO**. Proporciona el hardware del robot físico de Fifo: captura de audio por micrófono I2S, reproducción por parlante I2S, interfaz gráfica en pantalla OLED SSD1306 vía I2C y conectividad Bluetooth Low Energy (BLE) con el teléfono Android.

---

## 🗺️ Mapa de Archivos del Directorio

| Archivo / Carpeta | Tipo | Rol Principal |
| :--- | :--- | :--- |
| [`platformio.ini`](platformio.ini) | Configuración PlatformIO | Define la placa (`esp32dev`), framework Arduino, velocidad de subida (921600 baudios), velocidad del monitor serial (115200 baudios) y librerías externas (Adafruit SSD1306, Adafruit GFX, NimBLE-Arduino). |
| [`src/`](src/README.md) | Subdirectorio C++ | Código fuente modular del firmware: drivers de audio I2S, servicio GATT BLE, animaciones de pantalla OLED y bucle principal. |

---

## 🔌 Hardware y Conexiones Principales (Pinout)

```
        +----------------------------------------+
        |                ESP32                   |
        |                                        |
        |  GPIO 21 (SDA) ──────► OLED SSD1306    |
        |  GPIO 22 (SCL) ──────► OLED SSD1306    |
        |                                        |
        |  GPIO 32 (BCLK) ─────► Micrófono INMP441|
        |  GPIO 25 (LRCL) ─────► Micrófono INMP441|
        |  GPIO 33 (DIN)  ─────► Micrófono INMP441|
        |                                        |
        |  GPIO 26 (BCLK) ─────► DAC MAX98357A   |
        |  GPIO 27 (LRC)  ─────► DAC MAX98357A   |
        |  GPIO 14 (DOUT) ─────► DAC MAX98357A   |
        +----------------------------------------+
```

---

## 🚀 Compilación y Flasheo

```bash
# 1. Compilar firmware
pio run

# 2. Subir al ESP32 por cable USB
pio run --target upload

# 3. Abrir monitor serial para depurar
pio device monitor
```
