# 🤖 FIFO Voice Pipeline (100% BLE + ESP32-S3 + Android + Groq LPU)

Asistente robótico interactivo de compañía para adultos mayores con **conexión 100% Bluetooth Low Energy (BLE)**, rostro expresivo en pantalla OLED 1.3", cerebro de IA ultra-rápido en **Groq LPU** (con soporte de Function Calling nativo), ejecución continua 24/7 en segundo plano y activación estricta por palabra clave (**"FIFO"**).

---

## 🌟 Características Principales

- **Cerebro de IA en Groq LPU (Ultra-rápido y Gratuito):**
  - **Motor Principal (LLM):** Inferencia en chips LPU de Groq con modelos de razonamiento de código abierto de última generación (`openai/gpt-oss-120b` y `llama-3.3-70b-versatile`). Respuestas fluidas y empáticas generadas en **~200-350 ms**, sin costo de API para el usuario.
  - **Soporte de Function Calling / Skills:** El modelo analiza la conversación del adulto mayor y ejecuta de forma autónoma acciones nativas del teléfono (alarmas de medicamentos, llamadas telefónicas, búsqueda de farmacias en Google Maps, recordatorios en calendario) y actualiza su base de datos local (perfil, gustos, biografía).
  - **Fallback Multi-Proveedor:** Compatibilidad completa con Anthropic Claude (`claude-haiku-4-5-20251001`) si se prefiere una clave de Anthropic.
- **Oídos Inteligentes (STT Dual):**
  - **Groq Whisper (`whisper-large-v3-turbo`):** Transcripción de audio PCM desde el ESP32 con latencia ultra-baja en la nube de Groq.
  - **Google Voice Recognition Nativo:** Reconocimiento de voz local en el teléfono Android (100% gratuito, sin necesidad de claves de API ni consumo de cuotas).
- **Activación Estricta por Palabra Clave ("FIFO"):**
  - **Modo Reposo (`DURMIENDO`):** Fifo descansa y permanece en silencio absoluto ante conversaciones cotidianas, música o audio de la televisión en la habitación.
  - **Despertar Inmediato:** Al escuchar **"FIFO"** (o variantes fonéticas como *"Hola Fifo"*, *"Oye Fifo"*), despierta de inmediato (`ESCUCHANDO`), atiende la pregunta y vuelve a reposo tras responder para no capturar ruido posterior.
  - **Modo Escucha Continua:** Si el usuario le pide expresamente *"Fifo, sigue escuchando"*, Fifo se mantendrá atento en modo conversación continua sin requerir repetir su nombre, hasta que se le diga *"Fifo, descansa"* o tras 2 minutos de silencio.
- **Conexión 100% BLE (Sin Hotspots ni Wi-Fi):**
  - El teléfono y el robot ESP32-S3 se comunican bidireccionalmente por Bluetooth Low Energy. El celular mantiene su Wi-Fi o datos móviles activos normalmente.
- **Ejecución 24/7 en Segundo Plano:**
  - Corre como *Foreground Service* en Android con *WakeLock* parcial y notificación persistente. No se desconecta al bloquear la pantalla o suspender el teléfono.
- **Rostro Animado de Fifo en OLED 1.3" (SH1106 I2C):**
  - **DURMIENDO:** Ojos cerrados (`- -`), animación de "Zzz" flotando y aviso *"Di 'Fifo'"*.
  - **ESCUCHANDO:** Ojos atentos y ondas sonoras activas `))) (((`.
  - **PENSANDO:** Burbujas de pensamiento sobre la cabeza.
  - **HABLANDO:** Boca animada sincronizada con ondas de voz en vivo.
- **Filtro de Ruido Acústico en ESP32-S3:**
  - Filtro IIR Pasa-Altos a 150 Hz y puerta de ruido (*Noise Gate*) para capturar voz nítida desde el micrófono analógico UCC.
- **Interfaz Móvil Accesible (Jetpack Compose):**
  - Diseñada especialmente para adultos mayores con alto contraste, tipografía grande, retroalimentación táctil, botón de silencio con protección visual, localizador GPS/sonoro para encontrar el robot extraviado y comunidad *Fifo Amigos*.

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
             │ Celular Android (El Cerebro)       │           │
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
│   │ VAD + Filtro Estricto Wake Word ("FIFO") │              │
│   └────────────────────┬─────────────────────┘              │
│                        ▼                                    │
│   ┌──────────────────────────────────────────┐              │
│   │ STT: Groq Whisper / Google Voice Nativo  │              │
│   └────────────────────┬─────────────────────┘              │
│                        ▼                                    │
│   ┌──────────────────────────────────────────┐              │
│   │ LLM: Groq LPU (GPT-OSS 120B / LLaMA 3.3) │              │
│   │ (Fallback opcional: Claude Haiku 4.5)    │              │
│   └────────────────────┬─────────────────────┘              │
│                        ▼                                    │
│   ┌──────────────────────────────────────────┐              │
│   │ FifoSkillRegistry (Function Calling)     │              │
│   │ Alarmas, Teléfono, Maps, Mutación de BD  │              │
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

| Pin Módulo UCC | Pin ESP32-S3 | Función |
| :--- | :--- | :--- |
| **VCC** | **3.3V** | Alimentación |
| **GND** | **GND** | Tierra común |
| **OUT** | **GPIO 4** | ADC1 Canal 3 (I2S DMA a 16 kHz) |

### 2. Pantalla OLED 1.3" I2C (SH1106 128x64)

| Pin OLED | Pin ESP32-S3 | Función |
| :--- | :--- | :--- |
| **VCC** | **3.3V** | Alimentación |
| **GND** | **GND** | Tierra común |
| **SDA** | **GPIO 8** | Datos I2C |
| **SCL** | **GPIO 9** | Reloj I2C |

---

## 🚀 Puesta en Marcha

### 1. Grabar Firmware en ESP32-S3

Con [PlatformIO](https://platformio.org/):

```bash
pio run -d esp32-firmware -t upload --upload-port COM9
```

### 2. Configurar Claves de API en Android

1. Copia o edita `android-app/local.properties` y añade tu clave gratuita de Groq:

```properties
# Clave principal recomendada (100% gratuita para LLM ultra-rápido y STT Whisper)
# Obtén tu clave en: https://console.groq.com/keys
GROQ_API_KEY=gsk_...

# Opcional (si deseas usar Anthropic Claude como respaldo)
ANTHROPIC_API_KEY=sk-ant-api03-...

# Opcional (si deseas usar Whisper oficial de OpenAI en lugar de Groq Whisper)
OPENAI_API_KEY=sk-...
```

> **Nota de Seguridad:** El archivo `local.properties` está excluido en `.gitignore`. Las claves nunca se suben al repositorio. Además, el usuario puede actualizar la API key directamente desde la app en el panel **Perfil → Ajustes**.

### 3. Compilar e Instalar App Android

```bash
cd android-app
.\gradlew.bat assembleDebug
```

El archivo APK generado se ubica en: `android-app/app/build/outputs/apk/debug/app-debug.apk`.

Para instalarlo en un teléfono o emulador conectado por USB/ADB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 🛡️ Claves de API y Modelos Soportados

| Componente | Proveedor Recomendado | Modelo por Defecto | Tipo de Costo |
| :--- | :--- | :--- | :--- |
| **Cerebro (LLM)** | **Groq LPU** | `openai/gpt-oss-120b` / `llama-3.3-70b-versatile` | **Gratis** ([console.groq.com](https://console.groq.com/keys)) |
| **Cerebro (Fallback)** | Anthropic | `claude-haiku-4-5-20251001` | Pago por token |
| **Oídos (STT - Nube)** | **Groq Cloud** | `whisper-large-v3-turbo` | **Gratis** |
| **Oídos (STT - Local)** | Google Android | Reconocimiento nativo de Android | **Gratis (Sin clave)** |
| **Voz (TTS)** | Android Nativo | Android TextToSpeech en español | **Gratis (Sin clave)** |

---

## 📚 Documentación Adicional

- **[FIFO_DATABASE_MAP.md](FIFO_DATABASE_MAP.md):** Mapa exhaustivo de la base de datos central, catálogo de Skills (Function Calling) y flujo de mutaciones automáticas por voz.
