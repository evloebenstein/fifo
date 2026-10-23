# ADR-0018: Recomendación de usernames como política pura

**Status:** Accepted

**Date:** 2026-10-23

**Author:** Architecture Review — Testable identity policy

---

## Context

ADR-0017 movió el SQL de identidad a `UserProfileDao`, pero dejó dentro del DAO
la generación de usernames: normalización de nombre, tokens de correo, cinco
buckets estilísticos, filtros de contenido y rotación por `batchIndex`.

Esa lógica solo necesitaba una cosa del DAO: saber si un candidato ya estaba
tomado (`isUsernameTaken`). Al vivir dentro del DAO, no se podía probar sin una
base SQLite real, aunque la mayor parte era una política pura.

---

## Decision

### 1. `UsernameRecommender` es un objeto puro

El nuevo `data/local/UsernameRecommender.kt` no conoce Android, SQLite ni
`Context`. Recibe:

- `fullName`;
- `email`;
- `batchIndex`;
- `isTaken: (String) -> Boolean`;
- `currentYear` (inyectable para tests; por defecto el año actual).

Devuelve hasta cinco handles con prefijo `@`.

### 2. `UserProfileDao` solo aporta la unicidad

El DAO delega:

```kotlin
fun recommendAvailableUsernames(...) =
    UsernameRecommender.recommend(fullName, email, batchIndex, ::isUsernameTaken)
```

Así conserva su contrato y sigue siendo el único lugar que consulta `users`,
mientras la política queda fuera de la capa de persistencia.

### 3. La lógica observable no cambia

Se conservan exactamente:

- la normalización de acentos y separadores;
- la prioridad nombre → apellido → tokens de correo;
- los cinco buckets y su rotación por lote;
- los límites de longitud 3..20;
- `FifoContentFilter.validateUsername`;
- la deduplicación y los sufijos de respaldo.

---

## Tests

`UsernameRecommenderTest` cubre:

- cinco handles únicos, normalizados y con longitud válida;
- omisión de candidatos ya tomados;
- resultado vacío cuando todos están tomados;
- cambio de sugerencias entre lotes;
- fallback al correo cuando el nombre es genérico.

Los tests corren en JVM y no necesitan Android ni SQLite.

---

## Files Changed

| File | Change |
|---|---|
| `data/local/UsernameRecommender.kt` | **New** — política pura de recomendación |
| `data/local/UserProfileDao.kt` | **Updated** — delega la recomendación en el objeto puro |
| `test/.../UsernameRecommenderTest.kt` | **New** — cinco casos de la política |
| `docs/README.md` | **Updated** — índice de ADR |
| `data/README.md`, `data/local/README.md` | **Updated** — mapa de archivos |

---

## What Did NOT Change

- `UserProfileDao.recommendAvailableUsernames` conserva su firma pública.
- La consulta de unicidad sigue en `users` y en el DAO.
- `FifoDatabaseHelper`, `FifoDataRepository`, la UI y los adaptadores no cambian
  su contrato.
- No se tocaron el esquema SQLite, la memoria durable, la voz ni el legacy.

---

## Consequences

**Positive:**

- La política de usernames se prueba sin base de datos.
- El DAO queda más pequeño y con una sola responsabilidad de persistencia.
- El año actual se puede inyectar, lo que elimina una fuente de tests frágiles.
- Cambios estéticos o de buckets quedan contenidos en una pieza pura.

**Negative / Trade-offs:**

- Hay una indirección más entre el DAO y la generación.
- `UsernameRecommender` sigue dependiendo de `FifoContentFilter`, que es otra
  política pura; es una dependencia aceptable y testeable.

---

## Related

- [ADR-0017](0017-user-profile-dao.md) — DAO de identidad local.
- [ADR-0016](0016-community-activity-dao.md) — patrón de DAO por agregado.
