# ADR-0022: DAO de publicaciones sociales

**Status:** Accepted

**Date:** 2026-10-23

**Author:** Architecture Review — Local data ownership

---

## Context

`FifoDatabaseHelper` todavía contenía el CRUD de `social_posts`: listado global
ordenado por fecha de creación, alta con ID generado y borrado por ID.

Era un bloque pequeño y autocontenido, consumido por
`SqliteCommunityStorageAdapter` y por la sincronización masiva del helper. No
dependía de otras tablas ni de reglas de negocio externas.

---

## Decision

### 1. `SocialPostDao` es dueño del agregado

El nuevo DAO en `data/local/SocialPostDao.kt` contiene:

- `getSocialPosts`;
- `addSocialPost`;
- `deleteSocialPost`.

No conoce `Context` ni el ciclo de vida de la base. Recibe funciones que abren
las conexiones de lectura y escritura.

### 2. `FifoDatabaseHelper` compone el DAO

El helper expone:

```kotlin
val socialPosts = SocialPostDao(
    readableDatabase = { readableDatabase },
    writableDatabase = { writableDatabase }
)
```

Conserva el singleton, el esquema y las migraciones; delega el SQL del
agregado. `SqliteCommunityStorageAdapter` y `bulkUpsertSyncBundle` usan
`helper.socialPosts.*`.

### 3. El comportamiento observable no cambia

Se conservan exactamente:

- el orden `created_at DESC`;
- el listado global, sin filtro por usuario;
- los IDs `post_*` generados con `UUID`;
- `CONFLICT_REPLACE` en las altas;
- el borrado por `id`, sin filtro adicional por usuario.

La base sigue en v7 y no hay migración nueva.

---

## Files Changed

| File | Change |
|---|---|
| `data/local/SocialPostDao.kt` | **New** — SQL de publicaciones sociales |
| `data/local/FifoDatabaseHelper.kt` | **Updated** — expone `socialPosts`, elimina el bloque y usa el DAO en el sync masivo |
| `data/modules/SqliteStorageAdapters.kt` | **Updated** — el adaptador comunitario usa el DAO |
| `docs/README.md` | **Updated** — índice de ADR |
| `data/README.md`, `data/local/README.md` | **Updated** — mapa de archivos |

---

## What Did NOT Change

- El esquema de `social_posts` no cambia.
- La costura `CommunityStorageAdapter` no cambia.
- La lógica de negocio de publicaciones sigue fuera del DAO.
- No se tocaron `VoiceSession`, `VoiceSessionPipeline`, telefonía, memoria
  durable ni el camino legacy.
- No se agregó una segunda fuente de datos ni una caché paralela.

---

## Consequences

**Positive:**

- El helper pierde otro agregado completo y se acerca a ser solo
  esquema/migraciones + composición.
- Publicaciones sociales tiene un único dueño de SQL.
- El DAO no depende de `Context`.

**Negative / Trade-offs:**

- Hay una indirección más para llegar al SQL de publicaciones.
- El helper sigue construyendo el DAO y conoce su existencia.
- El borrado sigue siendo global por ID; no se cambió a un alcance por usuario
  para no alterar el contrato actual.
- No se agregó un test JVM específico porque el DAO solo contiene SQLite; la
  validación es de compilación y de la suite existente.

---

## Related

- [ADR-0016](0016-community-activity-dao.md) — patrón de DAO por agregado.
- [ADR-0021](0021-memory-dao.md) — DAO de recuerdos.
- [ADR-0002](0002-data-repository-modularization.md) — módulos de dominio.
