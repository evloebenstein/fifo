# 📦 Data Layer (`com.fifo.voicepipeline.data`)

Capa de datos de Fifo. Ver **ADR-0002**
(`docs/adr/0002-data-repository-modularization.md`) para la descomposición modular y
**ADR-0011** (`docs/adr/0011-durable-memory-journal.md`) para la memoria durable del
camino `SESSION`.

---

## Módulos

| Archivo / Carpeta | Rol |
| :--- | :--- |
| [`FifoDataRepository.kt`](FifoDataRepository.kt) | **Fachada de delegación** backward-compatible hacia los módulos de dominio. Expone los `StateFlow` para la UI. **Retiene directamente la telefonía** (`incomingCall`, `setIncomingCall`, `answerCurrentCall`, `hangupCurrentCall`) — invariante de ADR-0002. |
| [`modules/UserIdentityModule.kt`](modules/UserIdentityModule.kt) | Perfil de usuario, identidad, bio generada por IA y tags de intereses. Costura: `UserStorageAdapter`. |
| [`modules/CommunityModule.kt`](modules/CommunityModule.kt) | Actividades comunitarias (`ActividadComunitaria`) y red de `FifoAmigos`. Costura: `CommunityStorageAdapter`. |
| [`modules/EpisodicMemoryModule.kt`](modules/EpisodicMemoryModule.kt) | Memoria episódica: fragmentos compactos on-device y recuperación GraphRAG en backend. Costura: `MemoryStorageAdapter`. |
| [`modules/SqliteStorageAdapters.kt`](modules/SqliteStorageAdapters.kt) | Adaptadores SQLite de producción para los módulos de dominio. |
| [`memory/`](memory/README.md) | **Memoria durable del camino nuevo:** `JournalEntry`, puerto `ConversationJournal`, consolidación local/backend y fake JVM. |
| [`local/FifoDatabaseHelper.kt`](local/FifoDatabaseHelper.kt) | Helper SQLite v7: esquema global, migraciones y CRUD de las tablas de dominio que aún no tienen DAO. Compone el diario, la identidad, los gustos, los recordatorios, los recuerdos, las publicaciones sociales y las actividades comunitarias. |
| [`local/UserProfileDao.kt`](local/UserProfileDao.kt) | DAO de identidad local: perfil, demografía, bio, intereses y verificación de usernames (ADR-0017). |
| [`local/UsernameRecommender.kt`](local/UsernameRecommender.kt) | Política pura de recomendación de usernames (ADR-0018). |
| [`local/TasteDao.kt`](local/TasteDao.kt) | DAO de gustos e historias de gustos (ADR-0019). |
| [`local/ReminderDao.kt`](local/ReminderDao.kt) | DAO de recordatorios y eventos (ADR-0020). |
| [`local/MemoryDao.kt`](local/MemoryDao.kt) | DAO de recuerdos (ADR-0021). |
| [`local/SocialPostDao.kt`](local/SocialPostDao.kt) | DAO de publicaciones sociales (ADR-0022). |
| [`local/CommunityActivityDao.kt`](local/CommunityActivityDao.kt) | DAO de actividades comunitarias e inscripciones (ADR-0016). |
| [`local/ConversationJournalSchema.kt`](local/ConversationJournalSchema.kt) | Esquema del diario durable: tabla `conversation_journal` e índice de la cola pendiente. |
| [`local/SqliteConversationJournal.kt`](local/SqliteConversationJournal.kt) | Adaptador durable de `ConversationJournal`: dueño del CRUD, append, recientes, pendientes, reintentos y reparación. |
| [`remote/FifoApiClient.kt`](remote/FifoApiClient.kt) | **Deprecado para nuevas features** — `FifoBackendClient` en `network/` es la fuente preferida. Mantiene sincronización legacy. |
| [`FifoContentFilter.kt`](FifoContentFilter.kt) | Filtro de contenido sensible antes de persistir en memoria. |

---

## Flujo de datos

```
UI (Jetpack Compose)
    │ observa StateFlows
    ▼
FifoDataRepository (fachada)
    ├─► UserIdentityModule ──► SqliteUserStorageAdapter ──► SQLite
    ├─► CommunityModule ────► SqliteCommunityStorageAdapter ──► SQLite
    └─► EpisodicMemoryModule ─► SqliteMemoryStorageAdapter ──► SQLite
                                       └──► FifoBackendClient ──► Backend MySQL (GraphRAG)

VoiceSessionPipeline
    └─► MemoryCoordinator
          ├─► SqliteConversationJournal ──► conversation_journal (SQLite v7)
          └─► RepositorySessionMemoryConsolidator
                ├─► CloudApiClient.extractSessionMemoryChecked()
                ├─► FifoDataRepository (fragmentos, conversaciones, recuerdos, gustos)
                └─► FifoBackendClient.analyzeAndConsolidateSession()
```

---

## Invariantes

1. **Telefonía en la fachada:** `incomingCall` y sus mutaciones permanecen en `FifoDataRepository`. No mover a ningún módulo de dominio. Ver ADR-0002.
2. **Actualizaciones atómicas:** insertar en SQLite antes de actualizar el `StateFlow`.
3. **Tests sin Android:** los módulos de dominio son testables en JVM con los adaptadores `InMemory*`.
4. **Un solo dueño de la memoria durable:** `VoiceSessionPipeline` compone `MemoryCoordinator`, el diario y el consolidador. `VoiceSession` solo ve `SessionMemory`; el facade legacy no crea otro coordinador.
5. **El diario es la fuente de continuidad:** `CloudApiClient.conversationHistory` es solo una caché de contexto (últimos 8 mensajes); al arrancar se repuebla desde los últimos 4 turnos del diario.
