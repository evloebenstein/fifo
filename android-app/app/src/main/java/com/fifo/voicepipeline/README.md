# 🧠 Fifo Kotlin Architecture Map (`com.fifo.voicepipeline`)

Raíz del código fuente en Kotlin de la aplicación Android de **FIFO**.

La arquitectura de voz vigente está descrita en los ADR
[`docs/adr/`](../../../../../../../../docs/adr/): captura y telefonía en ADR-0008,
contexto de llamante en ADR-0009, selección del pipeline en ADR-0010 y memoria
durable/reparación en ADR-0011.

---

## 🗺️ Mapa de Archivos y Subpaquetes

| Archivo / Subpaquete | Tipo | Rol Principal |
| :--- | :--- | :--- |
| [`MainActivity.kt`](MainActivity.kt) | Actividad Principal | Punto de entrada UI (`ComponentActivity`). Inicializa `FifoDataRepository`, enlaza `FifoVoiceService`, gestiona permisos (micrófono, BLE, teléfono, ubicación, notificaciones) y renderiza `MainScreen`. |
| [`audio/`](audio/README.md) | Subpaquete | Primitivas de audio: `PhoneMicRecorder` (solo a través de `CaptureArbiter`), VAD, buffer PCM, reconocedor nativo, TTS nativo y sanitización de texto. |
| [`data/`](data/README.md) | Subpaquete | Repositorio reactivo (`FifoDataRepository`), módulos de dominio, SQLite local, diario durable (`data/memory`) y cliente REST heredado. |
| [`data/local/`](data/local/README.md) | Subpaquete | `FifoDatabaseHelper` (SQLite v7) y `SqliteConversationJournal`. |
| [`data/memory/`](data/memory/README.md) | Subpaquete | Modelo `JournalEntry`, puerto `ConversationJournal`, consolidador de sesión y consolidación local/backend. |
| [`location/`](location/README.md) | Subpaquete | GPS, brújula digital, geocodificación y navegación peatonal asistida. |
| [`network/`](network/README.md) | Subpaquete | `BleVoiceClient`, `CloudApiClient` (Groq STT/LLM), `FifoBackendClient`, aprovisionamiento y WebSocket local. |
| [`pipeline/`](pipeline/README.md) | Subpaquete | **Núcleo de voz.** `VoicePipelineManager` es la fachada; `VoicePipelineModeSelector` elige `LEGACY` o `SESSION`; `VoiceSessionPipeline` compone el camino nuevo. |
| [`service/`](service/README.md) | Subpaquete | `FifoVoiceService` (primer plano 24/7) y `FifoAccessibilityService` (control de llamadas). |
| [`skills/`](skills/README.md) | Subpaquete | Catálogo de herramientas ejecutables por la IA: recordatorios, llamadas, agenda, navegación, búsqueda y control del teléfono. |
| [`telephony/`](telephony/README.md) | Subpaquete | `TelephonyPort`, `AndroidTelephonyPort`, gestión de llamadas y contexto de llamante en dos fases. |
| [`ui/`](ui/README.md) | Subpaquete | Jetpack Compose: pantallas, navegación, diálogos y caritas animadas. |

---

## 🧭 Diagrama de Flujo Arquitectónico

```
                      +-------------------+
                      |   MainActivity    |
                      +---------+---------+
                                │ enlaza / observa
                                ▼
                      +-------------------+
                      | FifoVoiceService  |
                      +---------+---------+
                                │ sostiene
                                ▼
                    +------------------------+
                    |  VoicePipelineManager  |  fachada única
                    +------------+-----------+
                                 │
                    VoicePipelineModeSelector
                     ┌───────────┴───────────┐
                     ▼                       ▼
                 LEGACY                   SESSION
          (debug / sin STT)        VoiceSessionPipeline
                                             │
              ┌──────────────────────────────┼──────────────────────────────┐
              ▼                              ▼                              ▼
     +-----------------+          +-------------------+          +--------------------+
     |   pipeline/     |          |     network/      |          |     telephony/     |
     | VoiceSession    |          | Groq Whisper/LLM  |          | TelephonyPort      |
     | SpeechSegmenter |          | FifoBackendClient |          | CallerContext      |
     | CaptureArbiter  |          +---------+---------+          +---------+----------+
     +--------+--------+                    │                              │
              │                             ▼                              ▼
              │                   +-------------------+          +--------------------+
              │                   |      data/        |          |  AndroidTelephony  |
              │                   | SQLite + memory/  |          |  FifoCallManager   |
              │                   | journal durable   |          +--------------------+
              │                   +-------------------+
              ▼
     +-----------------+
     |     audio/      |
     | Mic / BLE / TTS |
     +-----------------+
```

En `SESSION`, `VoiceSessionPipeline` es el único lugar que construye la sesión, los
adaptadores de audio, la telefonía y la memoria durable. En `LEGACY`,
`VoicePipelineManager` conserva el camino anterior y no construye nada del camino nuevo.
