# ADR-0020: DAO de recordatorios y eventos

**Status:** Accepted

**Date:** 2026-10-23

**Author:** Architecture Review — Local data ownership

---

## Context

`FifoDatabaseHelper` todavía contenía el CRUD de `reminders_and_events`:
consulta ordenada, alta con ID generado, cambio de estado y borrado por usuario.

Era un bloque pequeño y autocontenido, usado por `FifoDataRepository`, por el
adaptador SQLite de memoria episódica y por la sincronización masiva del propio
helper. No dependía de otras tablas ni de reglas de negocio externas.

---

## Decision

### 1. `ReminderDao` es dueño del agregado

El nuevo DAO en `data/local/ReminderDao.kt` contiene:

- `getReminders`;
- `addReminder`;
- `completeReminder`;
- `deleteReminder`.

No conoce `Context` ni el ciclo de vida de la base. Recibe funciones que abren
las conexiones de lectura y escritura.

### 2. `FifoDatabaseHelper` compone el DAO

El helper expone:

```kotlin
val reminders = ReminderDao(
    readableDatabase = { readableDatabase },
    writableDatabase = { writableDatabase }
)
```

Conserva el singleton, el esquema y las migraciones; delega el SQL del
agregado. `FifoDataRepository`, `SqliteEpisodicMemoryStorageAdapter` y
`bulkUpsertSyncBundle` usan `helper.reminders.*`.

### 3. El comportamiento observable no cambia

Se conservan exactamente:

- el orden `is_completed ASC, time_str ASC`;
- la categoría por defecto `"medication"`;
- los IDs `rem_*` generados con `UUID`;
- el texto hablado `"Es hora de su recordatorio: <título>"`;
- `CONFLICT_REPLACE` en las altas;
- el filtrado por `user_id` en actualización y borrado.

La base sigue en v7 y no hay migración nueva.

---

## Files Changed

| File | Change |
|---|---|
| `data/local/ReminderDao.kt` | **New** — SQL de recordatorios y eventos |
| `data/local/FifoDatabaseHelper.kt` | **Updated** — expone `reminders`, elimina el bloque y usa el DAO en el sync masivo |
| `data/FifoDataRepository.kt` | **Updated** — alta directa vía DAO |
| `data/modules/SqliteStorageAdapters.kt` | **Updated** — el adaptador episódico usa el DAO |
| `docs/README.md` | **Updated** — índice de ADR |
| `data/README.md`, `data/local/README.md` | **Updated** — mapa de archivos |

---

## What Did NOT Change

- El esquema de `reminders_and_events` no cambia.
- `EpisodicMemoryModule` y la costura `MemoryStorageAdapter` no cambian.
- La lógica de negocio de recordatorios sigue fuera del DAO.
- No se tocaron `VoiceSession`, `VoiceSessionPipeline`, telefonía, memoria
  durable ni el camino legacy.
- No se agregó una segunda fuente de datos ni una caché paralela.

---

## Consequences

**Positive:**

- El helper pierde otro agregado completo y se acerca a ser solo
  esquema/migraciones + composición.
- Recordatorios tiene un único dueño de SQL.
- El DAO no depende de `Context`.

**Negative / Trade-offs:**

- Hay una indirección más para llegar al SQL de recordatorios.
- El helper sigue construyendo el DAO y conoce su existencia.
- No se agregó un test JVM específico porque el DAO solo contiene SQLite; la
  validación es de compilación y de la suite existente.

---

## Related

- [ADR-0016](0016-community-activity-dao.md) — patrón de DAO por agregado.
- [ADR-0019](0019-taste-dao.md) — DAO de gustos e historias.
- [ADR-0002](0002-data-repository-modularization.md) — módulos de dominio.
