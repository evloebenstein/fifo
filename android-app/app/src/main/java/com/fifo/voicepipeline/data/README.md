# 📦 Data Layer (`com.fifo.voicepipeline.data`)

Capa de datos de Fifo. Ver **ADR-0002** (`docs/adr/0002-data-repository-modularization.md`) para la descomposición modular.

---

## Módulos

| Archivo / Carpeta | Rol |
| :--- | :--- |
| [`FifoDataRepository.kt`](FifoDataRepository.kt) | **Fachada de delegación** 100% backward-compatible hacia los tres módulos de dominio. Expone todos los `StateFlow` para la UI. **Retiene directamente la telefonía** (`incomingCall`, `setIncomingCall`, `answerCurrentCall`, `hangupCurrentCall`) — invariante de ADR-0002, no mover. |
| [`modules/UserIdentityModule.kt`](modules/UserIdentityModule.kt) | Perfil de usuario, identidad, bio generada por IA, tags de intereses. Costura: `UserStorageAdapter`. |
| [`modules/CommunityModule.kt`](modules/CommunityModule.kt) | Actividades comunitarias (`ActividadComunitaria`) y red de `FifoAmigos`. Costura: `CommunityStorageAdapter`. |
| [`modules/EpisodicMemoryModule.kt`](modules/EpisodicMemoryModule.kt) | Memoria episódica dual-capa: contexto on-device (<80 tokens) + recuperación GraphRAG en backend. Costura: `MemoryStorageAdapter`. |
| [`modules/SqliteStorageAdapters.kt`](modules/SqliteStorageAdapters.kt) | Adaptadores SQLite de producción para los tres módulos. |
| [`local/FifoDatabaseHelper.kt`](local/FifoDatabaseHelper.kt) | Helper SQLite: esquema, migraciones y acceso de bajo nivel. |
| [`remote/FifoApiClient.kt`](remote/FifoApiClient.kt) | **Deprecado para nuevas features** — `FifoBackendClient` en `network/` es la fuente preferida. Mantiene sincronización legacy de recuerdos. |
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
```

---

## Invariantes

1. **Telefonía en la fachada:** `incomingCall` y sus mutaciones permanecen en `FifoDataRepository`. No mover a ningún módulo de dominio. Ver ADR-0002.
2. **Actualizaciones atómicas:** Siempre insertar en SQLite antes de actualizar el `StateFlow`.
3. **Tests sin Android:** Los tres módulos de dominio son 100% testables en JVM puro con los adaptadores `InMemory*`.
