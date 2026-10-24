#  FIFO Voice Pipeline (BLE + ESP32-S3 + Android + Groq LPU)

Asistente robótico interactivo de compañía para adultos mayores con **enlace principal Bluetooth Low Energy (BLE)**, rostro expresivo en pantalla OLED 1.3", cerebro de IA ultra-rápido en **Groq LPU** (con soporte de Function Calling nativo), ejecución continua 24/7 en segundo plano y activación estricta por palabra clave (**"FIFO"**). También puede funcionar con el micrófono y parlante del teléfono.

---

##  Características Principales

- **Cerebro de IA en Groq LPU (Ultra-rápido y Gratuito):**
  - **Motor Principal (LLM):** Inferencia en chips LPU de Groq con modelo `qwen/qwen3.8-27b` bajo arquitectura desacoplada `SpeechCognitionSeam` (ADR-0006). Respuestas fluidas y empáticas generadas en **~200-350 ms**, sin costo de API para el usuario.
  - **Soporte de Function Calling / Skills:** El modelo analiza la conversación del adulto mayor y ejecuta de forma autónoma acciones nativas del teléfono (alarmas de medicamentos, llamadas telefónicas, búsqueda de farmacias en Google Maps, recordatorios en calendario) y actualiza su base de datos local (perfil, gustos, biografía).
  - **Arquitectura 100% Groq LPU:** Estandarizado exclusivamente sobre Groq LPU (Qwen 27B + Whisper turbo) para máxima velocidad y predictibilidad.
- **Arquitectura de Voz con un Solo Pipeline Activo:**
  - `VoicePipelineModeSelector` elige `LEGACY` o `SESSION`; nunca se construyen ambos. `VOICE_SESSION_ENABLED` está apagado en debug y encendido en release.
  - En `SESSION`, `VoiceSessionPipeline` compone `VoiceSession`, `CaptureArbiter`, `AndroidTelephonyPort`, `CallerContextCoordinator` y la memoria durable.
  - Sin STT de nube, release cae a `LEGACY` para que Fifo no quede sordo.
- **Oídos Inteligentes (STT según el camino activo):**
  - **Groq Whisper (`whisper-large-v3-turbo`):** Transcripción de audio PCM en el camino `SESSION` con latencia ultra-baja en la nube de Groq.
  - **Google Voice Recognition Nativo:** Reconocimiento local disponible en el camino `LEGACY` o cuando no hay STT de nube configurado. No se usa durante una llamada telefónica.
- **Activación Estricta por Palabra Clave ("FIFO"):**
  - **Modo Reposo (`DURMIENDO`):** Fifo descansa y permanece en silencio absoluto ante conversaciones cotidianas, música o audio de la televisión en la habitación.
  - **Despertar Inmediato:** Al escuchar **"FIFO"** (o variantes fonéticas como *"Hola Fifo"*, *"Oye Fifo"*), despierta de inmediato (`ESCUCHANDO`), atiende la pregunta y vuelve a reposo tras responder para no capturar ruido posterior.
  - **Modo Escucha Continua:** Si el usuario le pide expresamente *"Fifo, sigue escuchando"*, Fifo se mantendrá atento en modo conversación continua sin requerir repetir su nombre, hasta que se le diga *"Fifo, descansa"* o tras 2 minutos de silencio.
- **Enlace Principal BLE (sin hotspots obligatorios):**
  - El teléfono y el robot ESP32-S3 se comunican bidireccionalmente por Bluetooth Low Energy. El celular mantiene su Wi-Fi o datos móviles activos normalmente.
  - Si el robot no está disponible, Fifo puede usar el micrófono y parlante del teléfono; `CaptureArbiter` decide la fuente y descarta tramas tardías.
- **Ejecución 24/7 en Segundo Plano:**
  - Corre como *Foreground Service* en Android con *WakeLock* parcial y notificación persistente. No se desconecta al bloquear la pantalla o suspender el teléfono.
- **Rostro Animado de Fifo en OLED 1.3" (SH1106 I2C):**
  - **DURMIENDO:** Ojos cerrados (`- -`), animación de "Zzz" flotando y aviso *"Di 'Fifo'"*.
  - **ESCUCHANDO:** Ojos atentos y ondas sonoras activas `))) (((`.
  - **PENSANDO:** Burbujas de pensamiento sobre la cabeza.
  - **HABLANDO:** Boca animada sincronizada con ondas de voz en vivo.
- **Filtro de Ruido Acústico en ESP32-S3:**
  - Filtro IIR Pasa-Altos a 150 Hz y puerta de ruido (*Noise Gate*) para capturar voz nítida desde el micrófono analógico UCC.
- **Control Telefónico Manos Libres y Contexto de Llamante:**
  - `CallerContextProvider.local()` anuncia de inmediato el nombre que ya conoce el teléfono; el backend enriquece en paralelo con timeout de ~800 ms.
  - El anuncio usa `CallerContext.announcementText()`: nombre y parentesco, sin leer temas recientes. Un caller ID sin registro sigue siendo desconocido.
  - Comandos completos obligatorios: *"Fifo contesta"* / *"Fifo cuelga"*. El escudo de eco se evalúa antes de cualquier acción, así que el propio anuncio no puede contestar ni colgar.
  - `CaptureArbiter` sella la captura: durante una llamada la ruta es siempre `PHONE_CALL`, nunca `SpeechRecognizer`; las tramas tardías se descartan.
- **Memoria Durable y Reparación Conversacional:**
  - **Diario antes de hablar:** cada turno se escribe en `conversation_journal` (SQLite v8) antes de `speakResponse`; si la app se cierra después, el turno no se pierde.
  - **Cola persistente:** `MemoryCoordinator` reintenta consolidaciones fallidas con backoff de 30 s a 15 min. La cola vive en disco, no en RAM, y sobrevive reinicios.
  - **Consolidación real:** `RepositorySessionMemoryConsolidator` escribe fragmentos, conversaciones, recuerdos, gustos y contactos, y sincroniza con el backend. Si el turno fue corregido, aprende `repairText`.
  - **Contexto restaurado:** al arrancar, el pipeline repuebla los últimos 4 turnos del diario (8 mensajes) en `CloudApiClient`.
  - **Reparación ±1 turno:** una corrección explícita se locuta si el turno anterior tiene ~10 s o menos; si llega tarde, se guarda en diario/OLED sin interrumpir. Un “No” aislado solo cuenta tras `needsRepair`.
  - **Recuperación profunda:** `recall_past_context` sigue disponible para anécdotas antiguas fuera de la memoria viva.
- **Copiloto Peatonal y Orientación Espacial en Tiempo Real:**
  - Orientación por voz con giroscopio y brújula integrada (*"¿dónde estoy?", "hacia dónde voy?"*), avisando rumbos relativos y cuadras restantes.
  - Búsqueda autónoma de locales esenciales cercanos (Oxxo, farmacias, supermercados) sin latencia.
- **Memoria Viva Local y Análisis en Nube:**
  - **On-device (SQLite):** tags de intereses, extractos de gustos, recuerdos clave, recordatorios activos y el diario `conversation_journal` para responder sin esperar a la red.
  - **Nube (MySQL 8.4 + FastAPI):** consolidación de conversaciones, conexión de personas mencionadas, biografías enriquecidas e `interest_tags`. La nube complementa la memoria local; no reemplaza el diario durable.
  - **Búsqueda Dinámica de Nicho (`recall_past_context`):** si el usuario pide un detalle profundo o una anécdota antigua, Fifo consulta el backend bajo demanda.
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
  - **Contexto Conversacional Restaurado:** El prompt usa los últimos 2 turnos de trabajo para no inflar la ventana; el diario durable repuebla hasta 4 turnos (8 mensajes) al arrancar. Permite diálogos fluidos como *"busca farmacias"* seguido de *"llévame allá"* con latencia de inferencia de apenas **~618 ms**.
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
ESP32-S3 (El Cuerpo)
  ├─ Micrófono UCC + filtro IIR/noise gate
  ├─ Pantalla OLED 1.3" (rostro animado)
  └─ BLE ↔ Android (audio PCM y estado)
                    │
                    ▼
Android (El Cerebro)
  FifoVoiceService (primer plano 24/7)
        │
        ▼
  VoicePipelineManager (fachada única)
        │
        ▼
  VoicePipelineModeSelector
        ├─ LEGACY (debug o sin STT de nube)
        └─ SESSION (release con STT de nube)
              VoiceSessionPipeline
              ├─ CaptureArbiter → micrófono / BLE / llamada
              ├─ SpeechSegmenter → VAD + pre-roll + WAV
              ├─ CloudApiClient → Whisper + Qwen (Groq)
              ├─ FifoSkillRegistry → Function Calling
              ├─ AndroidTelephonyPort + CallerContextCoordinator
              ├─ MemoryCoordinator → SQLite conversation_journal
              └─ Android TTS → parlante del celular
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
# Clave principal recomendada (100% gratuita para LLM Qwen y STT Whisper)
# Obtén tu clave en: https://console.groq.com/keys
GROQ_API_KEY=gsk_...

# Opcional: Whisper oficial de OpenAI si no usas Groq para STT
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

### 5. Ejecutar Pruebas

```bash
cd android-app
.\gradlew.bat testDebugUnitTest
.\gradlew.bat testReleaseUnitTest
.\gradlew.bat compileReleaseKotlin
```

Las pruebas cubren captura, telefonía, contexto de llamante, memoria durable,
reparación y barge-in por transcripción sin requerir un dispositivo físico.

---

##  Claves de API y Modelos Soportados

| Componente | Proveedor Recomendado | Modelo por Defecto | Tipo de Costo |
| :--- | :--- | :--- | :--- |
| **Cerebro (LLM)** | **Groq LPU** | `qwen/qwen3.8-27b` | **Gratis** ([console.groq.com](https://console.groq.com/keys)) |
| **Oídos (STT - Nube)** | **Groq Cloud** | `whisper-large-v3-turbo` | **Gratis** |
| **Oídos (STT - Nube alternativo)** | OpenAI | `whisper-1` (solo si configuras `OPENAI_API_KEY`) | Pago por uso |
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
  - [`android-app/`](android-app/README.md) - Configuración Gradle raíz, arquitectura de voz, SDK y comandos de terminal.
  - [`android-app/app/`](android-app/app/README.md) - Módulo Android, manifiesto de permisos, dependencias y pruebas.
  - [`android-app/app/src/main/java/com/fifo/voicepipeline/`](android-app/app/src/main/java/com/fifo/voicepipeline/README.md) - Raíz del paquete Kotlin, `MainActivity.kt` y mapa de arquitectura.
  - [`.../audio/`](android-app/app/src/main/java/com/fifo/voicepipeline/audio/README.md) - Primitivas de audio: captura PCM, VAD, reconocedor nativo, TTS y sanitización. La propiedad de captura/VAD vive en `pipeline/session`.
  - [`.../data/`](android-app/app/src/main/java/com/fifo/voicepipeline/data/README.md) - Repositorio reactivo, módulos de dominio, diario durable y consolidación.
  - [`.../data/local/`](android-app/app/src/main/java/com/fifo/voicepipeline/data/local/README.md) - SQLite v8, `FifoDatabaseHelper`, `contact_appearances` y `conversation_journal`.
  - [`.../data/memory/`](android-app/app/src/main/java/com/fifo/voicepipeline/data/memory/README.md) - `JournalEntry`, puerto `ConversationJournal` y consolidador de sesión.
  - [`.../data/remote/`](android-app/app/src/main/java/com/fifo/voicepipeline/data/remote/README.md) - Cliente REST heredado para sincronización con la nube.
  - [`.../location/`](android-app/app/src/main/java/com/fifo/voicepipeline/location/README.md) - GPS, brújula digital y guiado de navegación peatonal por voz estilo Waze.
  - [`.../network/`](android-app/app/src/main/java/com/fifo/voicepipeline/network/README.md) - Cliente BLE GATT, WebSocket local, Groq STT/LLM, backend y extracción de memoria.
  - [`.../pipeline/`](android-app/app/src/main/java/com/fifo/voicepipeline/pipeline/README.md) - **Núcleo del asistente de voz:** selección `LEGACY`/`SESSION`, `VoiceSessionPipeline`, `CaptureArbiter`, `SpeechSegmenter`, wake word, follow-up, eco, barge-in y memoria.
  - [`.../service/`](android-app/app/src/main/java/com/fifo/voicepipeline/service/README.md) - Servicios en primer plano para escucha 24/7 y accesibilidad telefónica.
  - [`.../skills/`](android-app/app/src/main/java/com/fifo/voicepipeline/skills/README.md) - Catálogo de habilidades ejecutables por la IA.
  - [`.../telephony/`](android-app/app/src/main/java/com/fifo/voicepipeline/telephony/README.md) - `TelephonyPort`, `AndroidTelephonyPort`, gestión de llamadas y contexto de llamante en dos fases.
  - [`.../ui/`](android-app/app/src/main/java/com/fifo/voicepipeline/ui/README.md) - Andamiaje de interfaz de usuario Compose (`MainScreen.kt`).
  - [`.../ui/components/`](android-app/app/src/main/java/com/fifo/voicepipeline/ui/components/README.md) - Diálogos modales, carita animada (`FifoFace.kt`) y bottom sheet.
  - [`.../ui/model/`](android-app/app/src/main/java/com/fifo/voicepipeline/ui/model/README.md) - Modelos inmutables de datos para la UI (`FifoModels.kt`).
  - [`.../ui/screens/`](android-app/app/src/main/java/com/fifo/voicepipeline/ui/screens/README.md) - Pantallas completas (Inicio, Perfil, Amigos, Actividades, Onboarding, Login).
  - [`.../ui/theme/`](android-app/app/src/main/java/com/fifo/voicepipeline/ui/theme/README.md) - Paleta de colores cálidos y tokens de accesibilidad.

---

##  Documentación Adicional

- **[docs/README.md](docs/README.md):** Índice de arquitectura, ADR-0001 a ADR-0011 y guías por subsistema.
- **[AGENTS.md](AGENTS.md):** Invariantes de arquitectura que todo cambio debe respetar.
- **[GLOSSARY.md](GLOSSARY.md):** Términos de dominio (VoiceSession, CaptureArbiter, TelephonyPort, ConversationJournal, etc.).
- **[PRODUCT.md](PRODUCT.md):** Visión del producto, arquitectura de telefonía, accesibilidad, memoria y privacidad.
- **[FIFO_DATABASE_MAP.md](FIFO_DATABASE_MAP.md):** Mapa de la base de datos, catálogo de Skills y flujo de mutaciones por voz.
- **[CONTRIBUTING.md](CONTRIBUTING.md):** Guía de contribución, entorno de desarrollo, pruebas y estándares de código.

---

## 📄 Licencia

Este proyecto está bajo la Licencia **MIT** — consulta el archivo [LICENSE](LICENSE) para más detalles.
