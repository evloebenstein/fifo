# ADR-0036: Borrador de actividad como política pura

**Status:** Accepted

**Date:** 2026-10-24

**Author:** Architecture Review — Activity suggestion seam

---

## Context

`FifoDataRepository.suggestActivityWithFifo()` construía en ~60 líneas el
borrador de una actividad comunitaria a partir de una idea dictada por el
usuario. Mezclaba tres cosas:

1. la lectura del perfil (`userProfile.value.id` y `fullName`);
2. una política de categorización por palabras clave;
3. la composición de un `CommunityActivityItem` con textos, cupos y fecha
   fijos.

La política no tenía pruebas propias. Tres pantallas de `ActivitiesScreen`
dependen de ella, y cualquier cambio en el orden de las ramas alteraba en
silencio la categoría, el título y los gustos sugeridos.

---

## Decision

### 1. `ActivitySuggestionPolicy` es la política pura

Se agrega `data/modules/ActivitySuggestionPolicy.kt`:

```kotlin
internal object ActivitySuggestionPolicy {
    fun draft(
        ideaPrompt: String,
        userTastes: List<String>,
        organizerId: String,
        organizerName: String
    ): CommunityActivityItem
}
```

No conoce `StateFlow`, SQLite, red ni Android.

### 2. El repositorio solo aporta el perfil

`FifoDataRepository.suggestActivityWithFifo()` conserva su firma pública y
delega:

```kotlin
fun suggestActivityWithFifo(ideaPrompt: String, userTastes: List<String>) =
    ActivitySuggestionPolicy.draft(
        ideaPrompt = ideaPrompt,
        userTastes = userTastes,
        organizerId = userProfile.value.id,
        organizerName = userProfile.value.fullName
    )
```

Las tres llamadas de `ActivitiesScreen` no cambian.

### 3. Se preservan las reglas del borrador

- la categoría, el título y el lugar por defecto salen de la primera familia
  de palabras que aparezca: pintura/arte, cocina/recetas, caminata/parque/
  jardín, música/boleros, lectura/historias; si no hay coincidencia se usa el
  encuentro vecinal;
- los gustos detectados en la idea se agregan en orden fijo; si no hay
  ninguno, se usan los dos primeros del perfil;
- una idea de 8 a 55 caracteres (ya recortada) se usa como título; en otro
  caso se usa el título por defecto;
- una idea de más de 20 caracteres se cita en la descripción;
- el organizador conserva id y nombre, y cae a “Vecino” si el nombre está en
  blanco;
- el borrador mantiene `id = "draft_suggested"`, 8 cupos, 1 tomado, sábado
  16:00, 500 m y `isJoined = true`.

---

## Files Changed

| File | Change |
|---|---|
| `data/modules/ActivitySuggestionPolicy.kt` | **New** — categorización, gustos, título, descripción y borrador |
| `data/FifoDataRepository.kt` | **Updated** — `suggestActivityWithFifo()` solo aporta el perfil y delega |
| `test/.../data/modules/ActivitySuggestionPolicyTest.kt` | **New** — 14 pruebas de categorías, gustos, límites de título, descripción y organizador |
| `data/README.md` | **Updated** — política en el mapa de módulos e invariante |
| `GLOSSARY.md` | **Updated** — borrador de actividad |
| `docs/README.md` | **Updated** — índice de ADR |

---

## What Did NOT Change

- No cambia la firma `suggestActivityWithFifo(ideaPrompt, userTastes)` ni el
  modelo `CommunityActivityItem`.
- No cambian las tres llamadas de `ActivitiesScreen`.
- No se agrega generación por IA ni llamadas de red: el borrador sigue siendo
  heurístico y local.
- No se toca la persistencia de actividades, la unión/abandono ni el muro
  social.
- No se agregan dependencias.

---

## Consequences

**Positive:**

- Las reglas del borrador tienen pruebas unitarias rápidas y explícitas.
- `FifoDataRepository` vuelve a delegar en vez de mezclar perfil y contenido.
- Cambiar una categoría o un umbral ahora exige actualizar una prueba.

**Negative / Trade-offs:**

- La coincidencia sigue siendo por subcadena: la rama de lectura incluye
  `"le"`, que también aparece en palabras como “colegio”. Se preserva el
  comportamiento actual; corregirlo es un cambio de producto aparte.
- Los textos, cupos y fecha del borrador siguen fijos; la política no consulta
  disponibilidad real.

---

## Related

- [ADR-0002](0002-data-repository-modularization.md) — módulos de dominio.
- [ADR-0031](0031-relational-graph-multi-source.md) — actividades y grafo
  comunitario.
- [ADR-0035](0035-episodic-context-search-policy.md) — extracción previa de una
  política pura de datos.
