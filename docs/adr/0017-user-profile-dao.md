# ADR-0017: DAO de identidad local fuera del helper central

**Status:** Accepted

**Date:** 2026-10-23

**Author:** Architecture Review — Local data ownership

---

## Context

Después de ADR-0015 y ADR-0016, `FifoDatabaseHelper` seguía siendo dueño del
SQL de `users` y `user_profiles`: lectura del perfil, búsqueda por
identificador, actualización de nombres/demografía/bio/intereses,
verificación de username y recomendación de usernames disponibles.

Ese bloque tenía un límite claro de agregado y ~280 líneas. Además, la
recomendación de usernames mezclaba una política de producto (generar
sugerencias) con el acceso a SQLite, aunque su única dependencia de datos era
`isUsernameTaken`.

---

## Decision

### 1. `UserProfileDao` es dueño del SQL de identidad

El nuevo DAO en `data/local/UserProfileDao.kt` contiene:

- `getUserProfile`;
- `findUserIdByIdentifier`;
- `updateUserProfileNames`;
- `isUsernameTaken`;
- `recommendAvailableUsernames`;
- `updateDemographics`, `updateBio` y `updateInterestTags`.

No conoce `Context` ni el ciclo de vida de la base. Recibe funciones que abren
las conexiones de lectura y escritura.

### 2. `FifoDatabaseHelper` compone el DAO

El helper expone:

```kotlin
val userProfile = UserProfileDao(
    readableDatabase = { readableDatabase },
    writableDatabase = { writableDatabase },
    gson = gson
)
```

Conserva el singleton, el esquema y las migraciones; delega el SQL del
agregado. Los adaptadores y la UI usan `helper.userProfile.*`.

### 3. El comportamiento observable no cambia

Se conservan exactamente:

- la consulta con `LEFT JOIN user_profiles`;
- la búsqueda por ID, username, email y nombre;
- la actualización parcial de demografía, bio e intereses;
- la verificación de username;
- los cinco buckets de recomendación, el `batchIndex`, los filtros de contenido
  y la rotación de sufijos;
- el fallback a `UserProfileData()` cuando la consulta falla.

La base sigue en v7 y no hay migración nueva.

---

## Files Changed

| File | Change |
|---|---|
| `data/local/UserProfileDao.kt` | **New** — SQL de identidad y recomendación de usernames |
| `data/local/FifoDatabaseHelper.kt` | **Updated** — expone `userProfile` y elimina el bloque de usuario |
| `data/FifoDataRepository.kt` | **Updated** — usa `dbHelper.userProfile` |
| `data/modules/SqliteStorageAdapters.kt` | **Updated** — el adaptador de identidad usa el DAO |
| `data/remote/FifoApiClient.kt` | **Updated** — lee el perfil desde el DAO |
| `ui/screens/OnboardingScreen.kt` | **Updated** — recomendación y verificación de username vía el DAO |
| `docs/README.md` | **Updated** — índice de ADR |
| `data/README.md`, `data/local/README.md` | **Updated** — mapa de archivos |

---

## What Did NOT Change

- El esquema de `users` y `user_profiles` no cambia.
- `UserIdentityModule`, `UserStorageAdapter` y sus `StateFlow` no cambian.
- La lógica de recomendación de usernames se movió sin alterar sus reglas.
- No se tocaron `VoiceSession`, `VoiceSessionPipeline`, telefonía, memoria
  durable ni el camino legacy.
- No se agregó una segunda fuente de datos ni una caché paralela.

---

## Consequences

**Positive:**

- El helper central pierde ~280 líneas de SQL de identidad.
- El agregado `users` + `user_profiles` tiene un único dueño explícito.
- La UI y los adaptadores ya no dependen de métodos dispersos del helper.
- El DAO no depende de `Context`; se puede probar con instrumentación o dobles
  de base sin arrastrar el helper completo.

**Negative / Trade-offs:**

- El helper sigue conociendo la existencia del DAO y lo construye.
- Hay una indirección más para llegar al SQL de identidad.
- `parseStringList` se duplica en el DAO para `interest_tags`; el helper lo
  conserva para contactos y fragmentos. Se puede unificar en una iteración
  posterior si aparece una tercera necesidad.

---

## Related

- [ADR-0002](0002-data-repository-modularization.md) — modularización del repositorio.
- [ADR-0016](0016-community-activity-dao.md) — patrón de DAO por agregado.
- [ADR-0015](0015-conversation-journal-owns-sql.md) — el diario durable como dueño de su SQL.
