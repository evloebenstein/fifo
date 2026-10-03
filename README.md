# 🤖 FIFO Voice Pipeline (100% BLE + ESP32-S3 + Android)

Asistente robótico interactivo con **conexión 100% Bluetooth Low Energy (BLE)**, rostro animado en pantalla OLED 1.3", ejecución continua 24/7 en segundo plano y activación por palabra clave (**"FIFO"**).

---

## 🌟 Características Principales

- **Conexión 100% BLE (Sin Hotspots ni Wi-Fi):** El celular y el robot ESP32-S3 se conectan automáticamente por Bluetooth Low Energy. El celular mantiene su Wi-Fi o datos móviles activos.
- **Activación por Voz ("FIFO"):** Modo reposo (`DURMIENDO`). Fifo ignora el ruido y las conversaciones del entorno en espacios concurridos; al escuchar **"FIFO"** (o *"Hola Fifo"*), despierta de inmediato (`ESCUCHANDO`), dice *"¡Hola! Te escucho"* y responde.
- **Ejecución 24/7 en Segundo Plano:** Corre como un *Foreground Service* en Android con *WakeLock* parcial y notificación persistente. No se desconecta al apagar la pantalla o minimizar la app.
- **Rostro Animado de Fifo en OLED 1.3" (SH1106 I2C):**
  - **DURMIENDO:** Ojos cerrados (`- -`), animación de "Zzz" flotando y aviso *"Di 'Fifo'"*.
  - **ESCUCHANDO:** Ojos atentos y ondas sonoras activas `))) (((`.
  - **PENSANDO:** Burbujas de pensamiento sobre la cabeza.
  - **HABLANDO:** Boca animada de 4 fases con ondas de voz sincronizadas.
- **Inteligencia Artificial en la Nube:**
  - **Cerebro (LLM):** Claude Haiku 4.5 (`claude-haiku-4-5-20251001`), con respuestas fluidas en ~350 ms.
  - **Oídos (STT):** Whisper (OpenAI o Groq gratuito `gsk_...` de latencia ultra-baja).
  - **Voz (TTS):** Parlante integrado del teléfono con Android TextToSpeech nativo o OpenAI TTS.
- **Filtro de Ruido Acústico en ESP32-S3:** Filtro IIR Pasa-Altos a 150 Hz y puerta de ruido (*Noise Gate*) para capturar voz clara en ambientes concurridos.
- **Sistema de Skills y Mapeo de Base de Datos:** Fifo actúa de forma 100% autónoma mediante Function Calling para actualizar el perfil, gustos, historias de vida y ejecutar herramientas del celular (alarmas de medicamentos, calendario, mapas, llamadas). El adulto mayor no necesita tocar la pantalla. Ver detalles en **[FIFO_DATABASE_MAP.md](FIFO_DATABASE_MAP.md)**.
- **Interfaz Android Completa (Figma):** 10 pantallas con estética *soft premium*, dashboard de bienvenida, comunidad de amigos, registro, ejercicios de respiración y panel de ajustes.

---

## 📐 Diagrama de Arquitectura

```
┌─────────────────────────────────────────────────────────────┐
│                 ESP32-S3-N16R8 (El Cuerpo)                  │
│                                                             │
│   [Mic Analógico UCC]              [Pantalla OLED 1.3" I2C] │
│   OUT → GPIO 4 (ADC1 DMA)         SDA → GPIO 8, SCL → GPIO 9│
│            │                                    ▲           │
│   (Filtro IIR + Noise Gate)        (Rostro animado en vivo: │
│            │                        Durmiendo, Escuchando,  │
│            │                        Pensando, Hablando)     │
└────────────┼────────────────────────────────────┼───────────┘
             │ Notificaciones BLE                 │ Escritura BLE
             ▼                                    │
┌─────────────────────────────────────────────────┼───────────┐
│            Celular Android (El Cerebro)         │           │
│                                                             │
│   ┌──────────────────────────────────────────┐  │           │
│   │ FifoVoiceService (24/7 Foreground + Wake)├──┘           │
│   └────────────────────┬─────────────────────┘              │
│                        ▼                                    │
│   ┌──────────────────────────────────────────┐              │
│   │ BleVoiceClient (Auto-reconexión BLE)     │              │
│   └────────────────────┬─────────────────────┘              │
│                        ▼                                    │
│   ┌──────────────────────────────────────────┐              │
│   │ VAD + Detector de Wake Word ("FIFO")     │              │
│   └────────────────────┬─────────────────────┘              │
│                        ▼                                    │
│   ┌──────────────────────────────────────────┐              │
│   │ STT: Whisper (OpenAI / Groq)             │              │
│   └────────────────────┬─────────────────────┘              │
│                        ▼                                    │
│   ┌──────────────────────────────────────────┐              │
│   │ LLM: Claude Haiku 4.5 (Anthropic)        │              │
│   └────────────────────┬─────────────────────┘              │
│                        ▼                                    │
│   ┌──────────────────────────────────────────┐              │
│   │ 🔊 Parlante del Celular (Android TTS)    │              │
│   └──────────────────────────────────────────┘              │
└─────────────────────────────────────────────────────────────┘
```

---

## 🔌 Conexiones de Hardware (ESP32-S3-N16R8)

### 1. Micrófono Analógico UCC

| Pin Módulo UCC | Pin ESP32-S3     | Función                        |
| :-------------- | :--------------- | :------------------------------ |
| **VCC**   | **3.3V**   | Alimentación                   |
| **GND**   | **GND**    | Tierra común                   |
| **OUT**   | **GPIO 4** | ADC1 Canal 3 (I2S DMA a 16 kHz) |

### 2. Pantalla OLED 1.3" I2C (SH1106 128x64)

| Pin OLED      | Pin ESP32-S3     | Función      |
| :------------ | :--------------- | :------------ |
| **VCC** | **3.3V**   | Alimentación |
| **GND** | **GND**    | Tierra común |
| **SDA** | **GPIO 8** | Datos I2C     |
| **SCL** | **GPIO 9** | Reloj I2C     |

---

## 🚀 Puesta en Marcha

### 1. Grabar Firmware en ESP32-S3

Con PlatformIO:

```bash
pio run -d esp32-firmware -t upload --upload-port COM9
```

### 2. Compilar e Instalar App Android

1. Copia `android-app/local.properties.example` como `android-app/local.properties` y coloca tus claves:

```properties
ANTHROPIC_API_KEY=sk-ant-api03-...
OPENAI_API_KEY=gsk_...
```

2. Compila el APK:

```bash
cd android-app
.\gradlew.bat assembleDebug
```

3. El APK generado estará en: `android-app/app/build/outputs/apk/debug/app-debug.apk`.

---

## 🛡️ Claves de API Soportadas

- **Cerebro (LLM):** Anthropic Claude (`sk-ant-...`).
- **Oídos (STT):**
  - **Groq Whisper (Recomendado y Gratuito):** Clave `gsk_...` de [console.groq.com/keys](https://console.groq.com/keys).
  - **OpenAI Whisper:** Clave `sk-...`.
  - *Nota:* Se puede configurar directamente en la pantalla de Perfil -> Configuraciones de la app móvil.
