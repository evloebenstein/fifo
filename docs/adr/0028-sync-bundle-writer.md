# ADR-0028: Escritor transaccional de bundles remotos

**Status:** Accepted

**Date:** 2026-10-23

**Author:** Architecture Review — Local sync ownership

---

## Context

Después de extraer los DAOs por agregado, `FifoDatabaseHelper` conservaba una
última responsabilidad operativa: `bulkUpsertSyncBundle`. Ese método abría la
transacción, escribía directamente `users` y `user_profiles`, y luego orquestaba
once pasos sobre los DAOs y nombres de tabla.

Era el único lugar del helper que todavía mezclaba:

- acceso directo a SQLite para identidad;
- orden de escritura del bundle;
- manejo de la transacción y del log de fallos;
- conocimiento de todos los agregados.

---

## Decision

### 1. `SyncBundleWriter` orquesta la escritura

El nuevo `data/local/SyncBundleWriter.kt` recibe el bundle ya descargado y los
DAOs que compone el helper. Su método `upsertBundle()`:

- abre una sola transacción;
- escribe el perfil;
- recorre gustos, historias, recordatorios, recuerdos, posts, conversaciones,
  fragmentos, ubicación, contactos y rutinas en el mismo orden que antes;
- marca la transacción como exitosa o la revierte si algo falla;
- conserva el log de éxito y error.

No conoce `Context`, HTTP, la UI ni el ciclo de vida de la base.

### 2. `UserProfileDao` es dueño del upsert de identidad

El DAO de identidad ahora expone `upsertSyncedProfile(profile)`. Escribe
`users` y `user_profiles` con `CONFLICT_REPLACE`, exactamente los mismos campos
que antes estaban en el helper, incluido `interest_tags` serializado con `Gson`.

Así el escritor no contiene SQL y el helper no necesita conocer columnas.

### 3. `FifoDatabaseHelper` queda como esquema y composición

El helper expone:

```kotlin
val syncBundleWriter = SyncBundleWriter(
    writableDatabase = { writableDatabase },
    userProfile = userProfile,
    tastes = tastes,
    // ...resto de DAOs
)
```

`FifoDataRepository` llama a `helper.syncBundleWriter.upsertBundle(...)`.

El helper ya no tiene SQL operativo de dominio ni métodos de sincronización:
solo singleton, esquema, migraciones, `onOpen` y composición de DAOs.

### 4. El comportamiento observable no cambia

Se conservan exactamente:

- una única transacción para todo el bundle;
- el orden de escritura de los once pasos;
- el filtro `taste.isNotBlank()`;
- el tratamiento opcional de `deviceLocation`, `relationalContacts` y
  `dailyRoutines`;
- el `device_id` fijo `"FIFO-S3-ESP32"`;
- los `CONFLICT_REPLACE` de cada DAO;
- el rollback ante excepción y el log correspondiente.

La base sigue en v7 y no hay migración nueva.

---

## Files Changed

| File | Change |
|---|---|
| `data/local/SyncBundleWriter.kt` | **New** — orquestación transaccional del bundle |
| `data/local/UserProfileDao.kt` | **Updated** — upsert de perfil sincronizado |
| `data/local/FifoDatabaseHelper.kt` | **Updated** — compone el writer y elimina la sincronización |
| `data/FifoDataRepository.kt` | **Updated** — usa `syncBundleWriter` |
| `docs/README.md` | **Updated** — índice de ADR |
| `data/README.md`, `data/local/README.md` | **Updated** — mapa de archivos |

---

## What Did NOT Change

- El esquema, las migraciones y la versión SQLite no cambian.
- El contrato de `SyncedUserBundle` no cambia.
- Los DAOs por agregado conservan sus firmas y su comportamiento.
- `FifoDataRepository.syncFromBackend` sigue actualizando los módulos reactivos
  después de persistir.
- No se tocaron `VoiceSession`, `VoiceSessionPipeline`, telefonía, memoria
  durable ni el camino legacy.

---

## Consequences

**Positive:**

- `FifoDatabaseHelper` queda reducido a esquema/migraciones + composición.
- La transacción del bundle tiene un único dueño y es fácil de localizar.
- El upsert de identidad vuelve al DAO que ya era dueño de `users` y
  `user_profiles`.
- Cambios futuros en el orden de sincronización quedan contenidos en un archivo.

**Negative / Trade-offs:**

- `SyncBundleWriter` tiene un constructor largo porque explicita todos los
  agregados que participan.
- Sigue dependiendo de DAOs concretos; un test JVM de la orquestación requeriría
  interfaces o una base SQLite de prueba.
- El `TAG` del log cambia de `FifoDatabaseHelper` a `SyncBundleWriter`.

---

## Related

- [ADR-0002](0002-data-repository-modularization.md) — módulos de dominio.
- [ADR-0016](0016-community-activity-dao.md) — patrón de DAO por agregado.
- [ADR-0027](0027-relational-contact-dao.md) — último DAO extraído del helper.
