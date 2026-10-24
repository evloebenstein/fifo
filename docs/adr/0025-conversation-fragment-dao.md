# ADR-0025: DAO de fragmentos de conversación

**Status:** Accepted

**Date:** 2026-10-23

**Author:** Architecture Review — Local data ownership

---

## Context

`FifoDatabaseHelper` todavía contenía la lectura y el alta de
`conversation_fragments`: consulta por usuario ordenada por fecha, parseo JSON
de temas y entidades, valores por defecto y upsert con ID generado.

Era un bloque autocontenido, consumido por `SqliteMemoryStorageAdapter` y por
la sincronización masiva del helper. Dependía de `Gson`, igual que otros DAOs
del directorio.

---

## Decision

### 1. `ConversationFragmentDao` es dueño del agregado

El nuevo DAO en `data/local/ConversationFragmentDao.kt` contiene:

- `getConversationFragments`;
- `addConversationFragment`.

No conoce `Context` ni el ciclo de vida de la base. Recibe funciones que abren
las conexiones y comparte el `Gson` del helper para serializar `key_topics` y
`named_entities`.

### 2. `FifoDatabaseHelper` compone el DAO

El helper expone:

```kotlin
val conversationFragments = ConversationFragmentDao(
    readableDatabase = { readableDatabase },
    writableDatabase = { writableDatabase },
    gson = gson
)
```

Conserva el singleton, el esquema y las migraciones; delega el SQL del
agregado. `SqliteMemoryStorageAdapter` y `bulkUpsertSyncBundle` usan
`helper.conversationFragments.*`.

### 3. El comportamiento observable no cambia

Se conservan exactamente:

- el orden `recorded_at DESC`;
- los valores por defecto `""`, `"neutral"`, `""`, `"General"` y `""`;
- el parseo tolerante: JSON inválido o ausente produce lista vacía;
- los IDs `frag_*` generados con `UUID`;
- la serialización de `key_topics` y `named_entities` con `Gson`;
- `recorded_at` solo se escribe si viene no vacío;
- `CONFLICT_REPLACE` en las altas.

No se agregó `deleteConversationFragment` porque el helper no lo tenía. La base
sigue en v7 y no hay migración nueva.

---

## Files Changed

| File | Change |
|---|---|
| `data/local/ConversationFragmentDao.kt` | **New** — SQL y codec JSON de fragmentos |
| `data/local/FifoDatabaseHelper.kt` | **Updated** — expone `conversationFragments`, elimina el bloque y usa el DAO en el sync masivo |
| `data/modules/SqliteStorageAdapters.kt` | **Updated** — el adaptador episódico usa el DAO |
| `docs/README.md` | **Updated** — índice de ADR |
| `data/README.md`, `data/local/README.md` | **Updated** — mapa de archivos |

---

## What Did NOT Change

- El esquema de `conversation_fragments` no cambia.
- `EpisodicMemoryModule` y la costura `MemoryStorageAdapter` no cambian.
- La lógica de negocio de fragmentos sigue fuera del DAO.
- No se tocaron `VoiceSession`, `VoiceSessionPipeline`, telefonía, memoria
  durable ni el camino legacy.
- No se agregó una segunda fuente de datos ni una caché paralela.

---

## Consequences

**Positive:**

- El helper pierde otro agregado completo y se acerca a ser solo
  esquema/migraciones + composición.
- Fragmentos de conversación tiene un único dueño de SQL y de su codec JSON.
- El DAO no depende de `Context`.

**Negative / Trade-offs:**

- Hay una indirección más para llegar al SQL de fragmentos.
- El helper sigue construyendo el DAO y conoce su existencia.
- El parseo JSON se mantiene dentro del DAO; si se necesita reutilizar en otro
  agregado, se podrá extraer un codec común.
- No se agregó un test JVM específico porque el DAO solo contiene SQLite; la
  validación es de compilación y de la suite existente.

---

## Related

- [ADR-0019](0019-taste-dao.md) — otro DAO con serialización JSON.
- [ADR-0024](0024-past-conversation-dao.md) — DAO de conversaciones pasadas.
- [ADR-0002](0002-data-repository-modularization.md) — módulos de dominio.
