# 📦 Data Layer (`com.fifo.voicepipeline.data`)

Capa de datos de Fifo. Ver **ADR-0002**
(`docs/adr/0002-data-repository-modularization.md`) para la descomposición modular,
**ADR-0011** (`docs/adr/0011-durable-memory-journal.md`) para la memoria durable del
camino `SESSION` y **ADR-0049** (`docs/adr/0049-single-owner-user-state.md`) para el
composition root y la propiedad de cada estado.

---

## Módulos

| Archivo / Carpeta | Rol |
| :--- | :--- |
| [`AppGraph.kt`](AppGraph.kt) | **Composition root (ADR-0049).** Construye una sola vez `identity`, `memory`, `community`, `relationalGraph`, `session`, `device` y `conversationContext`. Lo instala `FifoApplication`; la actividad y el servicio lo consumen. |
| [`FifoDataRepository.kt`](FifoDataRepository.kt) | **Accessor `@Deprecated` (ADR-0049).** Ya no posee estado: delega en `AppGraph` con la misma API pública para la migración strangler. La telefonía conserva `incomingCall` y las acciones delegadas en `CallStateCoordinator` (ADR-0030). |
| [`model/FifoModels.kt`](model/FifoModels.kt) | **Modelos puros de dominio.** No conocen Android ni Compose; los consumen datos, red y pruebas. |
| [`context/ConversationContext.kt`](context/ConversationContext.kt) | **Única ventana de contexto (ADR-0049):** hora, ubicación, ruta, memoria compacta y política de recuperación de cinco disparadores. `searchDeep()` consulta el backend y cae al índice local. |
| [`context/DeepContextPort.kt`](context/DeepContextPort.kt) | Costura de búsqueda profunda: `BackendDeepContextPort` en producción e `InMemoryDeepContextPort` en pruebas. |
| [`session/UserSession.kt`](session/UserSession.kt) | Dueño de la sesión: usuario activo, login, cambio de perfil e hidratación/sincronización del bundle (ADR-0049). |
| [`device/DeviceState.kt`](device/DeviceState.kt) | Dueño del robot (`FifoDeviceLocation`), del hardware del teléfono y de la conexión con el backend. Publica por `DeviceRemotePort`. |
| [`modules/UserIdentityModule.kt`](modules/UserIdentityModule.kt) | Perfil de usuario, identidad, bio generada por IA y tags de intereses. Costura: `UserStorageAdapter`. |
| [`modules/CommunityModule.kt`](modules/CommunityModule.kt) | Actividades comunitarias (`ActividadComunitaria`) y red de `FifoAmigos`. Costura: `CommunityStorageAdapter`. |
| [`modules/EpisodicMemoryModule.kt`](modules/EpisodicMemoryModule.kt) | Memoria episódica: fragmentos compactos on-device y recuperación GraphRAG en backend. Costura: `MemoryStorageAdapter`. |
| [`modules/EpisodicContextSearch.kt`](modules/EpisodicContextSearch.kt) | Política pura de recuperación local: stop words, tokens, puntuación de fragmentos, sesgo temporal y síntesis (ADR-0035). |
| [`modules/ActivitySuggestionPolicy.kt`](modules/ActivitySuggestionPolicy.kt) | Política pura del borrador de actividad: categoría, gustos, título, descripción y organizador (ADR-0036). |
| [`modules/SqliteStorageAdapters.kt`](modules/SqliteStorageAdapters.kt) | Adaptadores SQLite de producción para los módulos de dominio. |
| [`memory/`](memory/README.md) | **Memoria durable del camino nuevo:** `JournalEntry`, puerto `ConversationJournal`, consolidación local/backend y fake JVM. |
| [`contacts/RelationalGraphCoordinator.kt`](contacts/RelationalGraphCoordinator.kt) | Política pura de fusión multi-fuente del grafo relacional: libreta, FifoAmigos y menciones (ADR-0031). |
| [`modules/RelationalGraphModule.kt`](modules/RelationalGraphModule.kt) | **Dueño del grafo relacional (ADR-0049):** ingesta, fusión, procedencia, indicios y publicación por `RelationalRemotePort`. La lista reactiva sigue en `EpisodicMemoryModule` para la ventana compacta. |
| [`contacts/ContactSourcePort.kt`](contacts/ContactSourcePort.kt) | Costura de la libreta (`ContactSourcePort`), implementación Android (`AndroidContactSource`) y orquestador `DeviceContactsSync`. No persiste ni fusiona. |
| [`contacts/ContactMatcher.kt`](contacts/ContactMatcher.kt) | Política pura de resolución de personas por nombre, rol, apodo o tokens, sin distinguir acentos ni mayúsculas (ADR-0034). |
| [`contacts/PhoneHintExtractor.kt`](contacts/PhoneHintExtractor.kt) | Extracción conservadora de teléfonos explícitos en menciones; nunca sobrescribe la libreta ni escribe `phoneNumber` (ADR-0031, ADR-0032). |
| [`device/DeviceLocationPolicy.kt`](device/DeviceLocationPolicy.kt) | Política pura de transición del robot: fusión de conexión, última ubicación y beeper (ADR-0037). |
| [`local/FifoSchema.kt`](local/FifoSchema.kt) | **Fuente única del esquema SQLite v9**: DDL de tablas e índices, versión y sentencias de creación, reparación y migración (ADR-0033). |
| [`local/FifoDatabaseHelper.kt`](local/FifoDatabaseHelper.kt) | Helper SQLite: singleton, composición de DAOs y ejecución de las sentencias de `FifoSchema`. Ya no contiene DDL, SQL operativo de dominio ni la sincronización del bundle. |
| [`local/UserProfileDao.kt`](local/UserProfileDao.kt) | DAO de identidad local: perfil, demografía, bio, intereses, verificación de usernames y upsert del perfil sincronizado (ADR-0017, ADR-0028). |
| [`local/UsernameRecommender.kt`](local/UsernameRecommender.kt) | Política pura de recomendación de usernames (ADR-0018). |
| [`local/TasteDao.kt`](local/TasteDao.kt) | DAO de gustos e historias de gustos (ADR-0019). |
| [`local/ReminderDao.kt`](local/ReminderDao.kt) | DAO de recordatorios y eventos (ADR-0020). |
| [`local/MemoryDao.kt`](local/MemoryDao.kt) | DAO de recuerdos (ADR-0021). |
| [`local/SocialPostDao.kt`](local/SocialPostDao.kt) | DAO de publicaciones sociales (ADR-0022). |
| [`local/DeviceLocationDao.kt`](local/DeviceLocationDao.kt) | DAO de ubicación del dispositivo (ADR-0023). |
| [`local/PastConversationDao.kt`](local/PastConversationDao.kt) | DAO de conversaciones pasadas (ADR-0024). |
| [`local/ConversationFragmentDao.kt`](local/ConversationFragmentDao.kt) | DAO de fragmentos de conversación y su codec JSON (ADR-0025). |
| [`local/DailyRoutineDao.kt`](local/DailyRoutineDao.kt) | DAO de rutinas diarias (ADR-0026). |
| [`local/RelationalContactDao.kt`](local/RelationalContactDao.kt) | DAO de contactos relacionales, fusión e ingesta por lotes (ADR-0027). |
| [`local/ContactAppearanceDao.kt`](local/ContactAppearanceDao.kt) | DAO local append-only de procedencia del grafo: dónde apareció cada persona (ADR-0031). |
| [`local/ContactPhoneHintDao.kt`](local/ContactPhoneHintDao.kt) | DAO local de teléfonos mencionados pendientes de confirmar o descartar (ADR-0032). |
| [`local/SyncBundleWriter.kt`](local/SyncBundleWriter.kt) | Escritor transaccional del bundle remoto (ADR-0028). |
| [`local/CommunityActivityDao.kt`](local/CommunityActivityDao.kt) | DAO de actividades comunitarias e inscripciones (ADR-0016). |
| [`local/ConversationJournalSchema.kt`](local/ConversationJournalSchema.kt) | Esquema del diario durable: tabla `conversation_journal` e índice de la cola pendiente. |
| [`local/SqliteConversationJournal.kt`](local/SqliteConversationJournal.kt) | Adaptador durable de `ConversationJournal`: dueño del CRUD, append, recientes, pendientes, reintentos y reparación. |
| [`remote/FifoApiClient.kt`](remote/FifoApiClient.kt) | **Deprecado para nuevas features** — `FifoBackendClient` en `network/` es la fuente preferida. Mantiene sincronización legacy. |
| [`FifoContentFilter.kt`](FifoContentFilter.kt) | Filtro de contenido sensible antes de persistir en memoria. |

---

## Flujo de datos

```
FifoApplication.onCreate()
    └─► AppGraph (una sola instancia)
          ├─ identity ──► UserIdentityModule ──► SqliteUserStorageAdapter ──► SQLite
          ├─ memory ────► EpisodicMemoryModule ─► SqliteMemoryStorageAdapter ──► SQLite
          ├─ community ─► CommunityModule ─────► SqliteCommunityStorageAdapter ─► SQLite
          ├─ relationalGraph ─► RelationalGraphCoordinator ─► RelationalContactDao
          │                       ├─ ContactAppearanceDao (local-only)
          │                       └─ ContactPhoneHintDao (local-only)
          ├─ session ───► UserSession ──► FifoBackendClient (/users/{id}/sync)
          ├─ device ────► DeviceState ──► DeviceLocationDao + DeviceRemotePort
          └─ conversationContext ─► ConversationContext
                                      ├─ ventana compacta (local)
                                      └─ searchDeep() ─► DeepContextPort ─► backend
                                                       └─ fallback EpisodicContextSearch

UI / skills / pipeline
    └─► AppGraph.<módulo>          (destino)
    └─► FifoDataRepository        (accessor deprecado durante la migración)
```

---

## Invariantes

1. **Telefonía fuera de la capa de datos:** `FifoDataRepository` conserva la API pública por compatibilidad, pero el estado mutable vive en `CallStateCoordinator` (`telephony/`). Sigue habiendo un único `FifoCallManager`. Ver ADR-0030.
2. **Actualizaciones atómicas:** insertar en SQLite antes de actualizar el `StateFlow`.
3. **Tests sin Android:** los módulos de dominio son testables en JVM con los adaptadores `InMemory*`.
4. **Un solo dueño de la memoria durable:** `VoiceSessionPipeline` compone `MemoryCoordinator`, el diario y el consolidador. `VoiceSession` solo ve `SessionMemory`; el facade legacy no crea otro coordinador.
5. **El diario es la fuente de continuidad:** `CloudApiClient.conversationHistory` es solo una caché de contexto (últimos 8 mensajes); al arrancar se repuebla desde los últimos 4 turnos del diario.
6. **Un solo dueño de la fusión relacional:** `RelationalGraphCoordinator` decide cómo se combinan libreta, FifoAmigos y menciones. `RelationalGraphModule` compone, persiste y publica; el accessor solo delega. La procedencia es local (`contact_appearances`), no viaja al backend y se lee con `getContactAppearancesByContact()`. Los teléfonos solo se aceptan si son explícitos (`PhoneHintExtractor`) y nunca reemplazan uno existente.
7. **Un teléfono mencionado no es un teléfono confirmado:** la mención produce un `ContactPhoneHint` en `contact_phone_hints`; solo `confirmPhoneHint()` lo copia a `RelationalContact.phoneNumber` y lo sincroniza. `discardPhoneHint()` lo marca `DISCARDED` sin tocar el contacto (ADR-0032).
8. **Un solo dueño del DDL:** tablas, columnas e índices locales viven en `local/FifoSchema.kt`; `conversation_journal` sigue en `ConversationJournalSchema`. El helper solo ejecuta sentencias y no declara SQL de esquema (ADR-0033).
9. **Una sola resolución de personas:** `ContactMatcher` decide qué contacto corresponde a un nombre, rol, apodo o fragmento. Las skills y el pipeline no reimplementan normalización de acentos ni comparación de tokens; `FifoDataRepository.findContactByRoleOrName()` solo delega (ADR-0034).
10. **Una sola recuperación episódica local:** `EpisodicContextSearch` posee stop words, puntuación, sesgo temporal y síntesis del fallback offline. `EpisodicMemoryModule` solo intenta el backend y compone la instantánea (ADR-0035).
11. **Un solo borrador de actividad:** `ActivitySuggestionPolicy` decide categoría, gustos, título y descripción a partir de la idea y el perfil. `FifoDataRepository.suggestActivityWithFifo()` solo aporta `organizerId` y `organizerName` (ADR-0036).
12. **Una sola transición del dispositivo:** `DeviceLocationPolicy` fusiona conexión, última ubicación y beeper. `DeviceState` posee el `StateFlow`, el reloj, la persistencia y la publicación por `DeviceRemotePort`; no vuelve a componer el `copy()` (ADR-0037, ADR-0049).
13. **Un solo composition root:** `FifoApplication` instala `AppGraph` una vez. La actividad y el servicio no crean almacenamiento ni módulos paralelos. El accessor `FifoDataRepository` solo delega y se borra al terminar la migración (ADR-0049).
14. **Una sola ventana de contexto:** `ConversationContext` compone hora, ubicación, ruta y memoria compacta; `searchDeep()` es `suspend`, backend primero y respaldo local. Los cinco disparadores de recuperación son prompt, no locución, y el skill devuelve payload (ADR-0049).
15. **Modelos puros:** `data/model` no conoce Android ni Compose; `ui/model` solo añade typealiases e iconos. Un modelo nuevo de dominio nace en `data/model` (ADR-0049).
