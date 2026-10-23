# ADR-0021: DAO de recuerdos

**Status:** Accepted

**Date:** 2026-10-23

**Author:** Architecture Review — Local data ownership

---

## Context

`FifoDatabaseHelper` todavía contenía la lectura y el alta de `memories`:
consulta por usuario ordenada por fecha de creación, mapeo del emoji y la
etiqueta de aprendizaje, y alta con ID generado.

Era un bloque pequeño y autocontenido, consumido por `FifoDataRepository`, por
el adaptador SQLite de memoria episódica y por la sincronización masiva del
helper. No dependía de otras tablas ni de reglas de negocio externas.

---

## Decision

### 1. `MemoryDao` es dueño del agregado

El nuevo DAO en `data/local/MemoryDao.kt` contiene:

- `getMemories`;
- `addMemory`.

No conoce `Context` ni el ciclo de vida de la base. Recibe funciones que abren
las conexiones de lectura y escritura.

### 2. `FifoDatabaseHelper` compone el DAO

El helper expone:

```kotlin
val memories = MemoryDao(
    readableDatabase = { readableDatabase },
    writableDatabase = { writableDatabase }
)
```

Conserva el singleton, el esquema y las migraciones; delega el SQL del
agregado. `FifoDataRepository`, `SqliteMemoryStorageAdapter` y
`bulkUpsertSyncBundle` usan `helper.memories.*`.

### 3. El comportamiento observable no cambia

Se conservan exactamente:

- el orden `created_at DESC`;
- el emoji por defecto `"⭐"`;
- la etiqueta de aprendizaje por defecto `""`;
- los IDs `mem_*` generados con `UUID`;
- `CONFLICT_REPLACE` en las altas;
- el filtrado por `user_id`.

No se agregó `deleteMemory` porque el helper no lo tenía y el adaptador
mantiene `removeMemory` como no-op. La base sigue en v7 y no hay migración
nueva.

---

## Files Changed

| File | Change |
|---|---|
| `data/local/MemoryDao.kt` | **New** — SQL de recuerdos |
| `data/local/FifoDatabaseHelper.kt` | **Updated** — expone `memories`, elimina el bloque y usa el DAO en el sync masivo |
| `data/FifoDataRepository.kt` | **Updated** — alta directa vía DAO |
| `data/modules/SqliteStorageAdapters.kt` | **Updated** — el adaptador episódico usa el DAO |
| `docs/README.md` | **Updated** — índice de ADR |
| `data/README.md`, `data/local/README.md` | **Updated** — mapa de archivos |

---

## What Did NOT Change

- El esquema de `memories` no cambia.
- `EpisodicMemoryModule` y la costura `MemoryStorageAdapter` no cambian.
- La lógica de negocio de recuerdos sigue fuera del DAO.
- No se tocaron `VoiceSession`, `VoiceSessionPipeline`, telefonía, memoria
  durable ni el camino legacy.
- No se agregó una segunda fuente de datos ni una caché paralela.

---

## Consequences

**Positive:**

- El helper pierde otro agregado completo y se acerca a ser solo
  esquema/migraciones + composición.
- Recuerdos tiene un único dueño de SQL.
- El DAO no depende de `Context`.

**Negative / Trade-offs:**

- Hay una indirección más para llegar al SQL de recuerdos.
- El helper sigue construyendo el DAO y conoce su existencia.
- No se agregó un test JVM específico porque el DAO solo contiene SQLite; la
  validación es de compilación y de la suite existente.

---

## Related

- [ADR-0016](0016-community-activity-dao.md) — patrón de DAO por agregado.
- [ADR-0020](0020-reminder-dao.md) — DAO de recordatorios.
- [ADR-0002](0002-data-repository-modularization.md) — módulos de dominio.
