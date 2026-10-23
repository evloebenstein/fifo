# ADR-0016: DAO de actividades comunitarias fuera del helper central

**Status:** Accepted

**Date:** 2026-10-23

**Author:** Architecture Review — Local data ownership

---

## Context

`FifoDatabaseHelper` concentraba el esquema, las migraciones y el CRUD de todas
las tablas. Las actividades comunitarias (`community_activities` y
`activity_participants`) eran un bloque autocontenido de ~210 líneas: consulta
con subconsultas de participantes, alta, join, leave, delete y siembra inicial.

El helper no necesitaba conocer esas consultas para crear o migrar la base. A
medida que se agregan agregados, el archivo central se vuelve un punto de
conflicto y mezcla el ciclo de vida SQLite con el SQL operativo de cada dominio.

---

## Decision

### 1. `CommunityActivityDao` es dueño del SQL de actividades

El nuevo DAO en `data/local/CommunityActivityDao.kt` contiene todo el CRUD de:

- `community_activities`;
- `activity_participants`;
- la siembra de actividades por defecto.

No conoce `Context` ni el ciclo de vida de la base: recibe funciones que abren
las conexiones de lectura y escritura.

### 2. `FifoDatabaseHelper` compone el DAO, no lo implementa

El helper expone:

```kotlin
val communityActivities = CommunityActivityDao(
    readableDatabase = { readableDatabase },
    writableDatabase = { writableDatabase }
)
```

Así conserva el singleton y la creación/migración del esquema, pero delega el
SQL del agregado. `FifoDataRepository` y `SqliteCommunityStorageAdapter` usan
`helper.communityActivities` en lugar de métodos `*CommunityActivity*` del helper.

### 3. El comportamiento observable no cambia

Se conservan exactamente:

- las tablas, columnas e índice;
- la consulta con `is_user_joined` y `real_joined_count`;
- el cálculo de cupos (`spotsTaken`, `spotsMax`, `remaining`);
- el orden por `created_at DESC`;
- `CONFLICT_REPLACE` en altas e inscripciones;
- los incrementos/decrementos de `spots_taken`;
- las cuatro actividades iniciales.

La base sigue en v7 y no hay migración nueva.

---

## Files Changed

| File | Change |
|---|---|
| `data/local/CommunityActivityDao.kt` | **New** — CRUD y siembra de actividades comunitarias |
| `data/local/FifoDatabaseHelper.kt` | **Updated** — expone `communityActivities` y elimina el bloque de actividades |
| `data/FifoDataRepository.kt` | **Updated** — usa `helper.communityActivities` |
| `data/modules/SqliteStorageAdapters.kt` | **Updated** — el adaptador comunitario usa el DAO |
| `docs/README.md` | **Updated** — índice de ADR |
| `data/README.md`, `data/local/README.md` | **Updated** — mapa de archivos |

---

## What Did NOT Change

- `FifoDatabaseHelper` sigue siendo el dueño del esquema global y las migraciones.
- `CommunityModule` y los `StateFlow` de la UI no cambian.
- El contrato `CommunityStorageAdapter` no cambia.
- No se tocaron `VoiceSession`, `VoiceSessionPipeline`, telefonía, memoria durable
  ni el camino legacy.
- No se agregó una segunda fuente de datos ni una caché paralela.

---

## Consequences

**Positive:**

- El helper central pierde ~210 líneas de SQL de un dominio específico.
- Las actividades comunitarias tienen un límite de módulo claro y un único dueño.
- El DAO es independiente de `Context`; se puede probar con dobles de
  `SQLiteDatabase` o instrumentación sin arrastrar el helper completo.
- Cambios futuros al agregado quedan contenidos en un archivo.

**Negative / Trade-offs:**

- El helper sigue conociendo la existencia del DAO y lo construye.
- Hay una indirección más para llegar al SQL de actividades.
- La creación de las tablas sigue viviendo en `FifoDatabaseHelper.onCreate`,
  porque el esquema global se mantiene centralizado a propósito.

---

## Related

- [ADR-0002](0002-data-repository-modularization.md) — modularización del repositorio.
- [ADR-0015](0015-conversation-journal-owns-sql.md) — el diario durable como dueño de su SQL.
