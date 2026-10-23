# ADR-0023: DAO de ubicación del dispositivo

**Status:** Accepted

**Date:** 2026-10-23

**Author:** Architecture Review — Local data ownership

---

## Context

`FifoDatabaseHelper` todavía contenía la lectura y la escritura de
`device_locations`: consulta por `device_id` con valores por defecto y upsert
con `user_id`, estado de conexión, coordenadas, dirección, habitación, RSSI y
estado del beeper.

Era un bloque pequeño y autocontenido, consumido por `FifoDataRepository`,
`FifoApiClient` y la sincronización masiva del helper. No dependía de otras
tablas ni de reglas de negocio externas.

---

## Decision

### 1. `DeviceLocationDao` es dueño del agregado

El nuevo DAO en `data/local/DeviceLocationDao.kt` contiene:

- `getDeviceLocation`;
- `updateDeviceLocation`.

No conoce `Context` ni el ciclo de vida de la base. Recibe funciones que abren
las conexiones de lectura y escritura.

### 2. `FifoDatabaseHelper` compone el DAO

El helper expone:

```kotlin
val deviceLocation = DeviceLocationDao(
    readableDatabase = { readableDatabase },
    writableDatabase = { writableDatabase }
)
```

Conserva el singleton, el esquema y las migraciones; delega el SQL del
agregado. `FifoDataRepository`, `FifoApiClient` y `bulkUpsertSyncBundle` usan
`helper.deviceLocation.*`.

### 3. El comportamiento observable no cambia

Se conservan exactamente:

- el `device_id` por defecto `"FIFO-S3-ESP32"`;
- los valores por defecto al leer una fila incompleta;
- `FifoDeviceLocation()` cuando no existe fila;
- el upsert por `device_id` con `CONFLICT_REPLACE`;
- la persistencia de `user_id`.

La base sigue en v7 y no hay migración nueva.

---

## Files Changed

| File | Change |
|---|---|
| `data/local/DeviceLocationDao.kt` | **New** — SQL de ubicación del dispositivo |
| `data/local/FifoDatabaseHelper.kt` | **Updated** — expone `deviceLocation`, elimina el bloque y usa el DAO en el sync masivo |
| `data/FifoDataRepository.kt` | **Updated** — lectura inicial y actualizaciones vía DAO |
| `data/remote/FifoApiClient.kt` | **Updated** — lectura de ubicación vía DAO |
| `docs/README.md` | **Updated** — índice de ADR |
| `data/README.md`, `data/local/README.md` | **Updated** — mapa de archivos |

---

## What Did NOT Change

- El esquema de `device_locations` no cambia.
- La lógica de actualización de ubicación en el repositorio no cambia.
- No se tocaron `VoiceSession`, `VoiceSessionPipeline`, telefonía, memoria
  durable ni el camino legacy.
- No se agregó una segunda fuente de datos ni una caché paralela.

---

## Consequences

**Positive:**

- El helper pierde otro agregado completo y se acerca a ser solo
  esquema/migraciones + composición.
- La ubicación del dispositivo tiene un único dueño de SQL.
- El DAO no depende de `Context`.

**Negative / Trade-offs:**

- Hay una indirección más para llegar al SQL de ubicación.
- El helper sigue construyendo el DAO y conoce su existencia.
- No se agregó un test JVM específico porque el DAO solo contiene SQLite; la
  validación es de compilación y de la suite existente.

---

## Related

- [ADR-0016](0016-community-activity-dao.md) — patrón de DAO por agregado.
- [ADR-0022](0022-social-post-dao.md) — DAO de publicaciones sociales.
- [ADR-0002](0002-data-repository-modularization.md) — módulos de dominio.
