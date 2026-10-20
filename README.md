#  FIFO Voice Pipeline (100% BLE + ESP32-S3 + Android + Groq LPU)

Asistente robótico interactivo de compañía para adultos mayores con **conexión 100% Bluetooth Low Energy (BLE)**, rostro expresivo en pantalla OLED 1.3", cerebro de IA ultra-rápido en **Groq LPU** (con soporte de Function Calling nativo), ejecución continua 24/7 en segundo plano y activación estricta por palabra clave (**"FIFO"**).

---

##  Características Principales

- **Cerebro de IA en Groq LPU (Ultra-rápido y Gratuito):**
  - **Motor Principal (LLM):** Inferencia en chips LPU de Groq con modelo `qwen/qwen3.8-27b` bajo arquitectura desacoplada `SpeechCognitionSeam` (ADR-0006). Respuestas fluidas y empáticas generadas en **~200-350 ms**, sin costo de API para el usuario.
  - **Soporte de Function Calling / Skills:** El modelo analiza la conversación del adulto mayor y ejecuta de forma autónoma acciones nativas del teléfono (alarmas de medicamentos, llamadas telefónicas, búsqueda de farmacias en Google Maps, recordatorios en calendario) y actualiza su base de datos local (perfil, gustos, biografía).
  - **Arquitectura 100% Groq LPU:** Estandarizado exclusivamente sobre Groq LPU (Qwen 27B + Whisper turbo) para máxima velocidad y predictibilidad.
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
- **Control Telefónico Manos Libres y Captura Concurrente en Llamadas:**
  - Anuncio por voz del contacto entrante consultando la libreta telefónica (*"¡Lucía! Le está llamando Carmen..."*).
  - Comandos directos: *"contesta"* / *"atiende"* en altavoz manos libres o *"cuelga"* / *"rechaza"*.
  - Captura concurrente con `VOICE_RECOGNITION` y supresión de ruido hardware, permitiendo que Fifo escuche activamente las órdenes del usuario mientras una llamada celular o VoIP (WhatsApp, Meet) está en curso.
- **Copiloto Peatonal y Orientación Espacial en Tiempo Real:**
  - Orientación por voz con giroscopio y brújula integrada (*"¿dónde estoy?", "hacia dónde voy?"*), avisando rumbos relativos y cuadras restantes.
  - Búsqueda autónoma de locales esenciales cercanos (Oxxo, farmacias, supermercados) sin latencia.
- **Memoria de Doble Capa y Síntesis Continua de IA:**
  - **Capa Celular Inmediata (On-Device RAM + SQLite):** Fifo retiene en su memoria de trabajo viva los tags de intereses únicos, los extractos cortos de cada gusto, los recuerdos clave y los recordatorios activos pendientes. Responde al instante con calidez y sin latencia de red.
  - **Capa Nube Profunda (MySQL 8.4 + Microservicio FastAPI en Docker):** Preserva la transcripción íntegra de cada turno y utiliza modelos de IA de alta precisión para conectar puntos entre personas mencionadas en distintas charlas, sintetizar biografías enriquecidas y generar los `interest_tags`.
  - **Búsqueda Dinámica de Nicho (`recall_past_context`):** Si el usuario pide recordar un detalle profundo o anécdota antigua, Fifo extrae los segmentos relevantes del backend al instante.
- **Red Comunitaria Fifo Amigos & Matchmaking de Afinidad:**
  - Motor de compatibilidad que analiza los tags de gustos de los usuarios para recomendar amistades cálidas (`GET /users/{id}/fifo-friends/recommendations`), proponiendo temas compartidos para romper el hielo y publicaciones afines en el muro social.
- **Mutación Autónoma en Vivo de la Aplicación por Voz:**
  - **Gestión Integral de Recordatorios:** Programar (`set`), marcar como completado (`complete`), descartar (`delete`) y listar pendientes (`list`).
  - **Historias de Gustos en Detalle (`add_taste_story`):** Registro por voz de pasatiempos con fragmento corto/subtítulo para la memoria viva y descripción profunda para el perfil.
  - **Recuerdos Importantes Instantáneos (`add_memory`):** Guarda notas y afectos en la sección de recuerdos sin tocar la pantalla.
- **Inicio de Sesión con Google Nativo (Zero Fricción & Cero Dictado):**
  - **Cero Dictado de Correos:** Diseñado específicamente para adultos mayores. Elimina la frustración de tener que dictar por voz o escribir en pantalla correos complejos como *"lucia punto gonzalez arroba..."*.
  - **Integración con Servicios de Google:** Con un solo toque en "Registrarme rápido con Google", la app invoca directamente a los Servicios de Google (`GoogleSignInClient` / Google Play Services) o al `AccountManager` nativo de Android.
  - **Diálogo del Sistema Nativo:** Se despliega la ventana flotante nativa de Google (*"Choose an account to continue to Fifo"*), donde el usuario solo toca su cuenta ya guardada en el teléfono (`nombre@gmail.com`).
  - **Aprovisionamiento Automático:** Al seleccionar la cuenta, se extrae el nombre y correo del token de Google y se crea/sincroniza automáticamente el perfil en la base de datos MySQL/SQLite con biografía y gustos iniciales generados por IA.
  - **Selector y Conmutación de Perfiles en Vivo:** Permite alternar entre diferentes usuarios registrados desde la pantalla de login con un toque.
- **Optimización de Cuota LPU en Groq (Cero Rate Limits 429):**
  - **Presupuesto Estricto de Tokens (<1,800 tokens):** Esquema de Function Calling ultracomprimido (reducido de 2,894 a 1,070 tokens) y prompt del sistema minimalista (<180 tokens).
  - **Memoria Conversacional Rodante:** Mantiene el contexto de los últimos turnos sin inflar el historial, permitiendo diálogos fluidos como *"busca farmacias"* seguido de *"llévame allá"* con latencia de inferencia de apenas **~618 ms**.
- **Herramientas de Bienestar y Utilidades Probadas:**
  - **"Baja el ritmo" (Respiración Guiada):** Modal interactivo con ciclo 4-4-4 para relajación y manejo de ansiedad en personas mayores.
  - **"¿Dónde está mi Fifo?" (Localizador del Robot):** Detección por proximidad Bluetooth LE, pista de habitación del hogar y botón para abrir ubicación exacta en Google Maps.
  - **"Actividades y Talleres":** Cartelera de actividades recreativas comunitarias (Club de lectura, caminatas, música clásica).
  - **"Fifo Amigos" & "Perfil Dinámico":** Red social de intereses compartidos, cuentos y biografía viva sintetizada con IA.
- **Interfaz Móvil Accesible (Jetpack Compose):**
  - Diseñada para adultos mayores con alto contraste, tipografía grande, retroalimentación táctil, botón de silencio con protección visual y navegación fluida por pestañas.

---

##  Diagrama de Arquitectura

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
│   │  Parlante del Celular (Android TTS)    │              │
│   └──────────────────────────────────────────┘              │
└─────────────────────────────────────────────────────────────┘
```

---

##  Conexiones de Hardware (ESP32-S3-N16R8)

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

##  Puesta en Marcha

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

### 3. Levantar Microservicio de Backend y Base de Datos MySQL (Docker)

El backend de Fifo provee la sincronización de perfil, grafo relacional, síntesis de biografía por IA y la red social Fifo Amigos:

```bash
cd backend
# 1. Copiar plantilla de variables si aún no existe
copy .env.example .env
# 2. Iniciar contenedores MySQL 8.4 LTS, FastAPI y Adminer
docker compose up -d
```

- **API REST:** `http://localhost:8090` (Docs interactivos en `/docs`)
- **Panel Web Adminer:** `http://localhost:8080` (Base de datos: `fifo_db`)

> **Conexión Celular ↔ PC:** Si ejecutas la app en un teléfono físico conectado por USB, redirige el puerto con ADB:
> ```bash
> adb reverse tcp:8090 tcp:8090
> ```

### 4. Compilar e Instalar App Android

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

##  Claves de API y Modelos Soportados

| Componente | Proveedor Recomendado | Modelo por Defecto | Tipo de Costo |
| :--- | :--- | :--- | :--- |
| **Cerebro (LLM)** | **Groq LPU** | `openai/gpt-oss-120b` / `llama-3.3-70b-versatile` | **Gratis** ([console.groq.com](https://console.groq.com/keys)) |
| **Cerebro (Fallback)** | Anthropic | `claude-haiku-4-5-20251001` | Pago por token |
| **Oídos (STT - Nube)** | **Groq Cloud** | `whisper-large-v3-turbo` | **Gratis** |
| **Oídos (STT - Local)** | Google Android | Reconocimiento nativo de Android | **Gratis (Sin clave)** |
| **Voz (TTS)** | Android Nativo | Android TextToSpeech en español | **Gratis (Sin clave)** |

---

## 🗺️ Mapa Arquitectónico y Guías por Carpeta

Cada carpeta del proyecto cuenta con su propio archivo `README.md` detallando la responsabilidad de cada archivo, diagramas de flujo y buenas prácticas de programación:

- 🐳 **Backend & Persistencia:**
  - [`backend/`](backend/README.md) - Orquestación Docker Compose (MySQL 8.4, Adminer y API FastAPI).
  - [`backend/api/`](backend/api/README.md) - Microservicio REST en FastAPI (`main.py`), endpoints de perfiles, memoria y Fifo Amigos.
  - [`backend/init/`](backend/init/README.md) - DDL del esquema relacional (`01_schema.sql`) y datos iniciales de prueba (`02_seed_lucia.sql`).
  - [`database/`](database/README.md) - Módulo desacoplado de base de datos.
- 🤖 **Firmware ESP32:**
  - [`esp32-firmware/`](esp32-firmware/README.md) - Proyecto PlatformIO, hardware, diagrama de pines (pinout) y compilación.
  - [`esp32-firmware/src/`](esp32-firmware/src/README.md) - Código fuente en C++ (I2S mic/speaker, display OLED SSD1306 y GATT BLE).
- 📱 **Aplicación Android (Kotlin + Jetpack Compose):**
  - [`android-app/`](android-app/README.md) - Configuración Gradle raíz, SDK y comandos de terminal.
  - [`android-app/app/`](android-app/app/README.md) - Módulo Android, manifiesto de permisos y dependencias.
  - [`android-app/app/src/main/java/com/fifo/voicepipeline/`](android-app/app/src/main/java/com/fifo/voicepipeline/README.md) - Raíz del paquete Kotlin y `MainActivity.kt`.
  - [`.../audio/`](android-app/app/src/main/java/com/fifo/voicepipeline/audio/README.md) - VAD, buffer PCM, Google Voice nativo, Android TTS y sanitización de texto.
  - [`.../data/`](android-app/app/src/main/java/com/fifo/voicepipeline/data/README.md) - Repositorio reactivo central Local-First (`FifoDataRepository.kt`).
  - [`.../data/local/`](android-app/app/src/main/java/com/fifo/voicepipeline/data/local/README.md) - Base de datos relacional SQLite local (`FifoDatabaseHelper.kt`).
  - [`.../data/remote/`](android-app/app/src/main/java/com/fifo/voicepipeline/data/remote/README.md) - Cliente REST para sincronización nube.
  - [`.../location/`](android-app/app/src/main/java/com/fifo/voicepipeline/location/README.md) - GPS, brújula digital y guiado de navegación peatonal por voz estilo Waze.
  - [`.../network/`](android-app/app/src/main/java/com/fifo/voicepipeline/network/README.md) - Cliente BLE GATT, WebSocket server, y conectores a Groq/Claude.
  - [`.../pipeline/`](android-app/app/src/main/java/com/fifo/voicepipeline/pipeline/README.md) - **Núcleo del asistente de voz**: máquina de estados, palabra de activación estricta (*"Fifo"*), follow-up fluido, cancelación de eco y barge-in.
  - [`.../service/`](android-app/app/src/main/java/com/fifo/voicepipeline/service/README.md) - Servicios en primer plano para escucha 24/7 y accesibilidad telefónica.
  - [`.../skills/`](android-app/app/src/main/java/com/fifo/voicepipeline/skills/README.md) - Catálogo de 14 habilidades ejecutables por la IA.
  - [`.../telephony/`](android-app/app/src/main/java/com/fifo/voicepipeline/telephony/README.md) - Gestor de llamadas entrantes y contestador por voz.
  - [`.../ui/`](android-app/app/src/main/java/com/fifo/voicepipeline/ui/README.md) - Andamiaje de interfaz de usuario Compose (`MainScreen.kt`).
  - [`.../ui/components/`](android-app/app/src/main/java/com/fifo/voicepipeline/ui/components/README.md) - Diálogos modales, carita animada (`FifoFace.kt`) y bottom sheet.
  - [`.../ui/model/`](android-app/app/src/main/java/com/fifo/voicepipeline/ui/model/README.md) - Modelos inmutables de datos para la UI (`FifoModels.kt`).
  - [`.../ui/screens/`](android-app/app/src/main/java/com/fifo/voicepipeline/ui/screens/README.md) - Pantallas completas (Inicio, Perfil, Amigos, Actividades, Onboarding, Login).
  - [`.../ui/theme/`](android-app/app/src/main/java/com/fifo/voicepipeline/ui/theme/README.md) - Paleta de colores cálidos y tokens de accesibilidad.

---

##  Documentación Adicional

- **[PRODUCT.md](PRODUCT.md):** Visión del producto, arquitectura invisible de telefonía tipo Alexa, principios de accesibilidad y políticas de privacidad para personas mayores.
- **[FIFO_DATABASE_MAP.md](FIFO_DATABASE_MAP.md):** Mapa exhaustivo de la base de datos central, catálogo de Skills (Function Calling) y flujo de mutaciones automáticas por voz.
- **[CONTRIBUTING.md](CONTRIBUTING.md):** Guía de contribución, entorno de desarrollo y estándares de código.

---

## 📄 Licencia

Este proyecto está bajo la Licencia **MIT** — consulta el archivo [LICENSE](LICENSE) para más detalles.
