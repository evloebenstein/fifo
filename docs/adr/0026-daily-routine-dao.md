# ADR-0026: DAO de rutinas diarias

**Status:** Accepted

**Date:** 2026-10-23

**Author:** Architecture Review — Local data ownership

---

## Context

`FifoDatabaseHelper` todavía contenía la lectura y el upsert de
`user_daily_routines`: consulta de rutinas activas ordenadas por hora y
confianza, mapeo con valores por defecto y alta con ID generado.

El bloque estaba dentro de la sección de contactos relacionales y rutinas, pero
solo compartía el encabezado, no el SQL. Lo consumían
`SqliteMemoryStorageAdapter` y la sincronización masiva del helper.

---

## Decision

### 1. `DailyRoutineDao` es dueño del agregado

El nuevo DAO en `data/local/DailyRoutineDao.kt` contiene:

- `getDailyRoutines`;
- `addOrUpdateDailyRoutine`.

No conoce `Context` ni el ciclo de vida de la base. Recibe funciones que abren
las conexiones de lectura y escritura.

### 2. `FifoDatabaseHelper` compone el DAO

El helper expone:

```kotlin
val dailyRoutines = DailyRoutineDao(
    readableDatabase = { readableDatabase },
    writableDatabase = { writableDatabase }
)
```

Conserva el singleton, el esquema y las migraciones; delega el SQL del
agregado. `SqliteMemoryStorageAdapter` y `bulkUpsertSyncBundle` usan
`helper.dailyRoutines.*`. La sección del helper queda enfocada en contactos
relacionales.

### 3. El comportamiento observable no cambia

Se conservan exactamente:

- el filtro `is_active = 1`;
- el orden `typical_time_str ASC, confidence_score DESC`;
- los valores por defecto `"medication"`, `"morning"` y `"daily"`;
- los IDs `rtn_*` generados con `UUID`;
- `CONFLICT_REPLACE` en el upsert.

No se agregó `deleteDailyRoutine` porque el helper no lo tenía. La base sigue
en v7 y no hay migración nueva.

---

## Files Changed

| File | Change |
|---|---|
| `data/local/DailyRoutineDao.kt` | **New** — SQL de rutinas diarias |
| `data/local/FifoDatabaseHelper.kt` | **Updated** — expone `dailyRoutines`, elimina los métodos y usa el DAO en el sync masivo |
| `data/modules/SqliteStorageAdapters.kt` | **Updated** — el adaptador episódico usa el DAO |
| `docs/README.md` | **Updated** — índice de ADR |
| `data/README.md`, `data/local/README.md` | **Updated** — mapa de archivos |

---

## What Did NOT Change

- El esquema de `user_daily_routines` no cambia.
- Los contactos relacionales siguen en el helper.
- `EpisodicMemoryModule` y la costura `MemoryStorageAdapter` no cambian.
- No se tocaron `VoiceSession`, `VoiceSessionPipeline`, telefonía, memoria
  durable ni el camino legacy.
- No se agregó una segunda fuente de datos ni una caché paralela.

---

## Consequences

**Positive:**

- El helper pierde otro agregado completo y se acerca a ser solo
  esquema/migraciones + composición.
- Rutinas diarias tiene un único dueño de SQL.
- La sección de contactos del helper deja de mezclar dos agregados.
- El DAO no depende de `Context`.

**Negative / Trade-offs:**

- Hay una indirección más para llegar al SQL de rutinas.
- El helper sigue construyendo el DAO y conoce su existencia.
- No se agregó un test JVM específico porque el DAO solo contiene SQLite; la
  validación es de compilación y de la suite existente.

---

## Related

- [ADR-0016](0016-community-activity-dao.md) — patrón de DAO por agregado.
- [ADR-0025](0025-conversation-fragment-dao.md) — DAO de fragmentos de conversación.
- [ADR-0002](0002-data-repository-modularization.md) — módulos de dominio.
