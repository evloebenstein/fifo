# 📚 Documentación de Fifo

Índice de la arquitectura y las decisiones vigentes. Si un documento contradice el
código o un ADR, la fuente de verdad es el ADR y las invariantes de
[`AGENTS.md`](../AGENTS.md).

---

## 🧭 Lectura recomendada

1. [`PRODUCT.md`](../PRODUCT.md) — propósito, usuarios, accesibilidad y principios de producto.
2. [`GLOSSARY.md`](../GLOSSARY.md) — vocabulario de dominio (VoiceSession, CaptureArbiter, TelephonyPort, ConversationJournal…).
3. [`AGENTS.md`](../AGENTS.md) — invariantes que todo cambio debe respetar.
4. [`android-app/app/src/main/java/com/fifo/voicepipeline/pipeline/README.md`](../android-app/app/src/main/java/com/fifo/voicepipeline/pipeline/README.md) — selección `LEGACY`/`SESSION`, captura, barge-in y memoria.
5. [`FIFO_DATABASE_MAP.md`](../FIFO_DATABASE_MAP.md) — esquema de datos, skills y flujos de mutación.

---

## 🏛️ ADR (Architecture Decision Records)

| ADR | Decisión | Estado |
| :--- | :--- | :--- |
| [ADR-0001](adr/0001-deep-voice-session-architecture.md) | Arquitectura profunda de `VoiceSession` con costuras de audio y cognición. | Aceptado; evolucionado por ADR-0005/0008/0010. |
| [ADR-0002](adr/0002-data-repository-modularization.md) | Modularización de `FifoDataRepository` en identidad, comunidad y memoria episódica. | Aceptado. |
| [ADR-0003](adr/0003-knowledge-graph-and-intent-router.md) | Knowledge graph, GraphRAG e intent router. | Aceptado. |
| [ADR-0004](adr/0004-dynamic-contextual-skills-telephony-navigation.md) | Skills contextuales, telefonía y navegación. | Aceptado. |
| [ADR-0005](adr/0005-collapse-voice-pipeline-manager-into-voice-session.md) | Colapsar `VoicePipelineManager` en `VoiceSession`. | Aceptado; audio/telefonía reemplazados por ADR-0008. |
| [ADR-0006](adr/0006-collapse-cloud-api-client-into-speech-cognition-seam.md) | `CloudApiClient` detrás de `SpeechCognitionSeam`. | Aceptado. |
| [ADR-0007](adr/0007-geointentseam-decouple-geo-conversational-cluster.md) | `GeoIntentSeam`: desacoplar el clúster geo-conversacional. | Aceptado. |
| [ADR-0008](adr/0008-capture-arbiter-telephony-port.md) | `CaptureArbiter` como dueño único de captura y `TelephonyPort` fuera de la sesión. | Aceptado. |
| [ADR-0009](adr/0009-caller-context-provider.md) | `CallerContextProvider`: anuncio local inmediato y enriquecimiento backend en paralelo. | Aceptado. |
| [ADR-0010](adr/0010-voice-session-pipeline-flag.md) | `VoiceSessionPipeline`, flag de una release y barge-in por contenido. | Aceptado. |
| [ADR-0011](adr/0011-durable-memory-journal.md) | Diario durable, cola de consolidación y reparación conversacional. | Aceptado. |
| [ADR-0012](adr/0012-network-adapters-stt-tts.md) | Adaptadores de red para STT/TTS y fachada `CloudApiClient`. | Aceptado. |
| [ADR-0013](adr/0013-groq-dialog-client.md) | Motor de diálogo Groq separado de la fachada. | Aceptado. |
| [ADR-0014](adr/0014-session-memory-extractor.md) | Extractor de memoria de sesión y modelos en `data/memory`. | Aceptado. |
| [ADR-0015](adr/0015-conversation-journal-owns-sql.md) | El diario durable es dueño de su esquema y CRUD SQLite. | Aceptado. |
| [ADR-0016](adr/0016-community-activity-dao.md) | DAO de actividades comunitarias fuera del helper central. | Aceptado. |
| [ADR-0017](adr/0017-user-profile-dao.md) | DAO de identidad local (`users` + `user_profiles`) fuera del helper. | Aceptado. |
| [ADR-0018](adr/0018-username-recommender-policy.md) | Recomendación de usernames como política pura y testeable. | Aceptado. |
| [ADR-0019](adr/0019-taste-dao.md) | DAO de gustos e historias de gustos fuera del helper. | Aceptado. |
| [ADR-0020](adr/0020-reminder-dao.md) | DAO de recordatorios y eventos fuera del helper. | Aceptado. |
| [ADR-0021](adr/0021-memory-dao.md) | DAO de recuerdos fuera del helper. | Aceptado. |
| [ADR-0022](adr/0022-social-post-dao.md) | DAO de publicaciones sociales fuera del helper. | Aceptado. |
| [ADR-0023](adr/0023-device-location-dao.md) | DAO de ubicación del dispositivo fuera del helper. | Aceptado. |
| [ADR-0024](adr/0024-past-conversation-dao.md) | DAO de conversaciones pasadas fuera del helper. | Aceptado. |
| [ADR-0025](adr/0025-conversation-fragment-dao.md) | DAO de fragmentos de conversación fuera del helper. | Aceptado. |
| [ADR-0026](adr/0026-daily-routine-dao.md) | DAO de rutinas diarias fuera del helper. | Aceptado. |

---

## 🗂️ Guías por subsistema

### Android

| Documento | Contenido |
| :--- | :--- |
| [`android-app/README.md`](../android-app/README.md) | Build, arquitectura de voz, memoria durable y pruebas. |
| [`.../voicepipeline/README.md`](../android-app/app/src/main/java/com/fifo/voicepipeline/README.md) | Mapa del paquete Kotlin. |
| [`.../pipeline/README.md`](../android-app/app/src/main/java/com/fifo/voicepipeline/pipeline/README.md) | Núcleo de voz: selección, captura, VAD, barge-in, telefonía y memoria. |
| [`.../audio/README.md`](../android-app/app/src/main/java/com/fifo/voicepipeline/audio/README.md) | Primitivas de audio y reglas de captura/TTS. |
| [`.../telephony/README.md`](../android-app/app/src/main/java/com/fifo/voicepipeline/telephony/README.md) | `TelephonyPort` y contexto de llamante. |
| [`.../data/README.md`](../android-app/app/src/main/java/com/fifo/voicepipeline/data/README.md) | Repositorio, módulos y memoria durable. |
| [`.../data/memory/README.md`](../android-app/app/src/main/java/com/fifo/voicepipeline/data/memory/README.md) | Diario, consolidación y cola pendiente. |
| [`.../data/local/README.md`](../android-app/app/src/main/java/com/fifo/voicepipeline/data/local/README.md) | SQLite v7 y `conversation_journal`. |
| [`.../network/README.md`](../android-app/app/src/main/java/com/fifo/voicepipeline/network/README.md) | BLE, Groq, backend y extracción de memoria. |
| [`.../service/README.md`](../android-app/app/src/main/java/com/fifo/voicepipeline/service/README.md) | Servicio en primer plano y accesibilidad. |

### Backend, datos y firmware

| Documento | Contenido |
| :--- | :--- |
| [`backend/README.md`](../backend/README.md) | Docker Compose, MySQL y API. |
| [`backend/api/README.md`](../backend/api/README.md) | Endpoints FastAPI y consolidación. |
| [`database/README.md`](../database/README.md) | Esquema y módulo de base de datos. |
| [`esp32-firmware/README.md`](../esp32-firmware/README.md) | Hardware, pinout y compilación del ESP32-S3. |

---

## 🔒 Reglas transversales

- **Un solo pipeline:** `VoicePipelineModeSelector` decide `LEGACY` o `SESSION`; nunca ambos.
- **Un solo dueño de captura:** `CaptureArbiter`.
- **Un solo dueño de VAD/pre-roll:** `SpeechSegmenter`.
- **Telefonía fuera de `VoiceSession`:** `TelephonyPort` + `CallerContextCoordinator`.
- **Memoria antes de hablar:** `conversation_journal` se escribe antes de `speakResponse`.
- **Barge-in por contenido:** el RMS no corta locuciones; el eco se ignora antes de cualquier acción.
- **Comandos de llamada completos:** *“Fifo contesta”* / *“Fifo cuelga”*.
