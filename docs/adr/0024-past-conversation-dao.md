# ADR-0024: DAO de conversaciones pasadas

**Status:** Accepted

**Date:** 2026-10-23

**Author:** Architecture Review — Local data ownership

---

## Context

`FifoDatabaseHelper` todavía contenía el CRUD de `past_conversations`: listado
por usuario ordenado por fecha de grabación, alta con ID generado y borrado
acotado por usuario.

Era un bloque pequeño y autocontenido, consumido por
`SqliteMemoryStorageAdapter` y por la sincronización masiva del helper. Los
fragmentos compactos (`conversation_fragments`) viven en un bloque separado y
tienen su propia serialización JSON, por lo que quedaron fuera de este cambio.

---

## Decision

### 1. `PastConversationDao` es dueño del agregado

El nuevo DAO en `data/local/PastConversationDao.kt` contiene:

- `getPastConversations`;
- `addPastConversation`;
- `deletePastConversation`.

No conoce `Context` ni el ciclo de vida de la base. Recibe funciones que abren
las conexiones de lectura y escritura.

### 2. `FifoDatabaseHelper` compone el DAO

El helper expone:

```kotlin
val pastConversations = PastConversationDao(
    readableDatabase = { readableDatabase },
    writableDatabase = { writableDatabase }
)
```

Conserva el singleton, el esquema y las migraciones; delega el SQL del
agregado. `SqliteMemoryStorageAdapter` y `bulkUpsertSyncBundle` usan
`helper.pastConversations.*`.

### 3. El comportamiento observable no cambia

Se conservan exactamente:

- el orden `recorded_at DESC`;
- los valores por defecto `"Hoy"`, `"5 min"`, `""`, `"Conversación"` y
  `"heart"`;
- los IDs `c_*` generados con `UUID`;
- `CONFLICT_REPLACE` en las altas;
- el borrado con `user_id = ? AND id = ?`.

La base sigue en v7 y no hay migración nueva.

---

## Files Changed

| File | Change |
|---|---|
| `data/local/PastConversationDao.kt` | **New** — SQL de conversaciones pasadas |
| `data/local/FifoDatabaseHelper.kt` | **Updated** — expone `pastConversations`, elimina el bloque y usa el DAO en el sync masivo |
| `data/modules/SqliteStorageAdapters.kt` | **Updated** — el adaptador episódico usa el DAO |
| `docs/README.md` | **Updated** — índice de ADR |
| `data/README.md`, `data/local/README.md` | **Updated** — mapa de archivos |

---

## What Did NOT Change

- El esquema de `past_conversations` no cambia.
- `conversation_fragments`, su serialización JSON y su DAO futuro no cambian.
- `EpisodicMemoryModule` y la costura `MemoryStorageAdapter` no cambian.
- No se tocaron `VoiceSession`, `VoiceSessionPipeline`, telefonía, memoria
  durable ni el camino legacy.
- No se agregó una segunda fuente de datos ni una caché paralela.

---

## Consequences

**Positive:**

- El helper pierde otro agregado completo y se acerca a ser solo
  esquema/migraciones + composición.
- Conversaciones pasadas tiene un único dueño de SQL.
- El DAO no depende de `Context`.

**Negative / Trade-offs:**

- Hay una indirección más para llegar al SQL de conversaciones.
- El helper sigue construyendo el DAO y conoce su existencia.
- No se agregó un test JVM específico porque el DAO solo contiene SQLite; la
  validación es de compilación y de la suite existente.

---

## Related

- [ADR-0016](0016-community-activity-dao.md) — patrón de DAO por agregado.
- [ADR-0023](0023-device-location-dao.md) — DAO de ubicación del dispositivo.
- [ADR-0002](0002-data-repository-modularization.md) — módulos de dominio.
