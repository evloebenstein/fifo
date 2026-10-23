# ADR-0019: DAO de gustos e historias de gustos

**Status:** Accepted

**Date:** 2026-10-23

**Author:** Architecture Review — Local data ownership

---

## Context

`FifoDatabaseHelper` seguía siendo dueño del SQL de `tastes` y
`taste_stories`: lectura de gustos activos, alta con ID generado, baja lógica,
lectura de historias, alta con tags JSON y borrado por usuario.

Era un bloque autocontenido de ~75 líneas, consumido por el repositorio, los
adaptadores SQLite, el cliente de sincronización legacy y la sincronización
masiva del helper.

---

## Decision

### 1. `TasteDao` es dueño del agregado

El nuevo DAO en `data/local/TasteDao.kt` contiene:

- `getTastes`, `addTaste`, `removeTaste`;
- `getTasteStories`, `addTasteStory`, `deleteTasteStory`.

No conoce `Context` ni el ciclo de vida de la base. Recibe funciones que abren
las conexiones y comparte el `Gson` del helper para serializar los tags.

### 2. `FifoDatabaseHelper` compone el DAO

El helper expone:

```kotlin
val tastes = TasteDao(
    readableDatabase = { readableDatabase },
    writableDatabase = { writableDatabase },
    gson = gson
)
```

Conserva el singleton, el esquema y las migraciones; delega el SQL del
agregado. `FifoDataRepository`, `SqliteEpisodicMemoryStorageAdapter`,
`FifoApiClient` y `bulkUpsertSyncBundle` usan `helper.tastes.*`.

### 3. El comportamiento observable no cambia

Se conservan exactamente:

- la consulta de gustos activos ordenada por `learned_at`;
- los IDs `t_*` y `story_*` generados con `UUID`;
- la baja lógica con `is_active = 0`;
- el orden de historias por `created_at DESC`;
- la serialización/deserialización de `tags` con fallback a lista vacía;
- `CONFLICT_REPLACE` en altas.

La base sigue en v7 y no hay migración nueva.

---

## Files Changed

| File | Change |
|---|---|
| `data/local/TasteDao.kt` | **New** — SQL de gustos e historias |
| `data/local/FifoDatabaseHelper.kt` | **Updated** — expone `tastes`, elimina el bloque y usa el DAO en el sync masivo |
| `data/FifoDataRepository.kt` | **Updated** — altas iniciales y story directa vía DAO |
| `data/modules/SqliteStorageAdapters.kt` | **Updated** — el adaptador episódico usa el DAO |
| `data/remote/FifoApiClient.kt` | **Updated** — sincronización legacy usa el DAO |
| `docs/README.md` | **Updated** — índice de ADR |
| `data/README.md`, `data/local/README.md` | **Updated** — mapa de archivos |

---

## What Did NOT Change

- El esquema de `tastes` y `taste_stories` no cambia.
- `EpisodicMemoryModule` y la costura `MemoryStorageAdapter` no cambian.
- La lógica de deduplicación y moderación de gustos sigue en el módulo.
- No se tocaron `VoiceSession`, `VoiceSessionPipeline`, telefonía, memoria
  durable ni el camino legacy.
- No se agregó una segunda fuente de datos ni una caché paralela.

---

## Consequences

**Positive:**

- El helper central pierde otro agregado completo y queda más cerca de ser solo
  esquema/migraciones + composición.
- Gustos e historias tienen un único dueño de SQL.
- El DAO no depende de `Context`; se puede probar con instrumentación o dobles
  de base.

**Negative / Trade-offs:**

- Hay una indirección más para llegar al SQL de gustos.
- El helper sigue construyendo el DAO y conoce su existencia.
- `TasteDao` y `UserProfileDao` comparten la dependencia de `Gson`; no se
  extrajo un codec común porque todavía son dos usos acotados.

---

## Related

- [ADR-0016](0016-community-activity-dao.md) — patrón de DAO por agregado.
- [ADR-0017](0017-user-profile-dao.md) — DAO de identidad local.
- [ADR-0002](0002-data-repository-modularization.md) — módulos de dominio.
