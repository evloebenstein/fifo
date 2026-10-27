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
| [ADR-0007](adr/0007-geointentseam-decouple-geo-conversational-cluster.md) | `GeoIntentSeam`: desacoplar el clúster geo-conversacional. | Aceptado; shim retirado por ADR-0038. |
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
| [ADR-0027](adr/0027-relational-contact-dao.md) | DAO de contactos relacionales fuera del helper. | Aceptado; fusión ampliada por ADR-0031. |
| [ADR-0028](adr/0028-sync-bundle-writer.md) | Escritor transaccional de bundles remotos fuera del helper. | Aceptado. |
| [ADR-0029](adr/0029-fifo-backend-adapters.md) | Adaptadores por dominio para el backend propio. | Aceptado. |
| [ADR-0030](adr/0030-call-state-coordinator.md) | Estado de llamada extraído de `FifoDataRepository`. | Aceptado. |
| [ADR-0031](adr/0031-relational-graph-multi-source.md) | Grafo relacional multi-fuente y procedencia de personas (SQLite v8). | Aceptado; ampliado por ADR-0032. |
| [ADR-0032](adr/0032-phone-hint-confirmation.md) | Teléfonos mencionados como indicios locales pendientes de confirmación (SQLite v9). | Aceptado. |
| [ADR-0033](adr/0033-single-source-sqlite-schema.md) | `FifoSchema` como fuente única del DDL SQLite; el helper solo compone y ejecuta. | Aceptado. |
| [ADR-0034](adr/0034-contact-matcher-policy.md) | `ContactMatcher`: resolución de personas por nombre, rol, apodo y tokens como política pura. | Aceptado. |
| [ADR-0035](adr/0035-episodic-context-search-policy.md) | `EpisodicContextSearch`: recuperación local de memoria episódica como política pura. | Aceptado. |
| [ADR-0036](adr/0036-activity-suggestion-policy.md) | `ActivitySuggestionPolicy`: borrador de actividad comunitaria como política pura. | Aceptado. |
| [ADR-0037](adr/0037-device-location-policy.md) | `DeviceLocationPolicy`: transiciones del estado del robot como política pura. | Aceptado. |
| [ADR-0038](adr/0038-navigation-manager-injection.md) | Retiro del singleton de `FifoNavigationManager`; inyección por constructor en todos los consumidores. | Aceptado. |
| [ADR-0039](adr/0039-dynamic-location-and-navigation-answers.md) | Ubicación y orientación se responden con el LLM y contexto espacial; el fast-path deja de usar plantillas. | Aceptado. |
| [ADR-0040](adr/0040-dynamic-autonomous-navigation-guidance.md) | La guía autónoma se redacta con el LLM a partir de avisos estructurados; memoria conversacional de la ruta (encargos, pausas, correcciones). | Aceptado. |
| [ADR-0041](adr/0041-durable-navigation-session-and-notice-coalescing.md) | La ruta activa y su sesión sobreviven al cierre de la app; los avisos coalescen al más reciente y esperan sin plazo al turno en curso. | Aceptado. |
| [ADR-0042](adr/0042-single-utterance-policy.md) | `UtterancePolicy` es la única autoridad léxica: invocación, silencio, sueño, eco, pregunta y control de llamada se comparten entre `SESSION` y legacy. | Aceptado. |
| [ADR-0043](adr/0043-two-stage-wake-word-invocation.md) | La invocación se resuelve con un candidato acústico (`WakeWordDetector`) que solo despierta, y la confirmación léxica de `UtterancePolicy`; la acústica nunca autoriza llamadas. | Aceptado. |
| [ADR-0044](adr/0044-synthetic-wake-word-harness.md) | La invocación se mide con un corpus sintético determinista y un arnés en JVM: FRR por SNR, negativos difíciles, falsos aceptos/hora y latencia. El detector de referencia por plantilla espectral vive solo en pruebas y documenta el techo del enfoque. | Aceptado. |
| [ADR-0045](adr/0045-field-simulation-for-invocation.md) | El arnés añade un corpus de campo simulado (RIR por método de imagen, efecto Lombard y VTLP) sin dependencias. Con plantillas de la misma sala el FRR cae de 87,5 % a 12,5 %: el límite es el canal, no las features. | Aceptado. |
| [ADR-0046](adr/0046-synthetic-corpus-does-not-train.md) | El corpus sintético mide pero no entrena: regresión logística, red 257→24→1 y plantillas aumentadas fallan la barra de ≤1 FA/h. `NoopWakeWordDetector` sigue por defecto; el siguiente paso es openWakeWord con embeddings y muchas voces. | Aceptado; resultado negativo, no se envía. |
| [ADR-0047](adr/0047-export-corpus-and-mel-cnn.md) | El corpus se exporta a WAV + manifiesto y se entrena un log-mel + CNN con 100 locutores. La representación baja el FRR de campo de 100 % a 33 %, pero sigue sin cumplir ≤1 FA/h; no se envía. | Aceptado; resultado negativo, herramientas solo de desarrollo. |
| [ADR-0048](adr/0048-speaker-identity.md) | La identidad de locutor personaliza y atribuye turnos, nunca autentica. Costura `SpeakerIdentityProvider` con `Noop` por defecto, política pura, almacén cifrado con Keystore y consentimiento, calibración con locutores disjuntos y extracción/arnés sintético. Tres modelos reales quedaron en 42-50 % EER global y un control con LibriSpeech en 0,0 %: el corpus sintético no ordena motores ni certifica campo. El campo simulado con 40 voces reales sí prefiltra: CAM++ zh_en advanced 1,2 % EER crudo (AS-Norm no siempre ayuda) y `white-5` aún en 10,7 % FRR, pero no autoriza a cambiar `Noop`. El corpus de campo tiene layout, plan disjunto y validación propios. La UI de consentimiento/estado/borrado existe y comparte el almacén cifrado; el enrolamiento sigue deshabilitado sin extractor. El spoofing queda como costura `SpoofingDetector` + `Noop`: un riesgo alto solo degrada a `UNCERTAIN`. | Aceptado; `Noop` por defecto hasta validar con un corpus real. |

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
| [`.../data/local/README.md`](../android-app/app/src/main/java/com/fifo/voicepipeline/data/local/README.md) | SQLite v9, `FifoSchema`, `conversation_journal`, `contact_appearances` e indicios telefónicos. |
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
- **Grafo relacional multi-fuente:** libreta, FifoAmigos y menciones se fusionan en `RelationalGraphCoordinator`; la procedencia vive solo en `contact_appearances` y se muestra como “Visto en…” en la tarjeta de contacto. Los teléfonos mencionados viven en `contact_phone_hints` como indicios locales y solo pasan a `phoneNumber` cuando el usuario los confirma (ADR-0032).
