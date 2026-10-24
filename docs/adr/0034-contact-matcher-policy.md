# ADR-0034: Resolución de contactos como política pura

**Status:** Accepted

**Date:** 2026-10-23

**Author:** Architecture Review — Contact resolution seam

---

## Context

`FifoDataRepository.findContactByRoleOrName()` resolvía una persona del grafo
relacional a partir de lo que decía el usuario ("mi hija", "la doctora",
"Rosa", "mami"). Es una operación crítica: la usan las skills de llamada,
mensajería y recordatorios, además del pipeline de sesión para resolver el
llamante local.

Aun así, la política vivía dentro de la fachada de datos:

- repetía la normalización de acentos y mayúsculas dos veces por contacto
  (una en la pasada exacta y otra en la parcial);
- no tenía pruebas propias, pese a decidir a quién se llama;
- mezclaba la regla de coincidencia con el acceso a `relationalContacts.value`;
- una consulta vacía podía resolver el primer contacto con `alias == null`,
  porque la cadena vacía coincidía con el apodo vacío. Las skills ya suelen
  validar, pero la política no se defendía sola.

El proyecto ya tiene el patrón de políticas puras probadas en JVM
(`UsernameRecommender`, `PhoneHintExtractor`, `RelationalGraphCoordinator`).

---

## Decision

### 1. `ContactMatcher` es el único dueño de la coincidencia

Se agrega `data/contacts/ContactMatcher.kt`:

```kotlin
object ContactMatcher {
    fun find(query: String, contacts: List<RelationalContact>): RelationalContact?
}
```

No conoce SQLite, `StateFlow`, Android ni la UI. Recibe la lista actual y
devuelve el contacto o `null`.

### 2. Se conserva el orden de las reglas existentes

1. Se normalizan mayúsculas y acentos.
2. Se busca una coincidencia **exacta** de nombre, rol o apodo en el orden
   actual del grafo.
3. Si no hay exacta, se acepta una coincidencia **parcial o por tokens**,
   ordenada por `closenessScore` descendente.
4. Los tokens de menos de 3 caracteres se ignoran.

La normalización y la tokenización se calculan una sola vez por contacto, en
lugar de repetirse en cada pasada.

### 3. Una consulta vacía no resuelve a nadie

`find()` devuelve `null` cuando la consulta queda vacía después de recortar
espacios. Es la única corrección de comportamiento: evita que una skill que
recibe un argumento vacío llame, escriba o adjunte un recordatorio a la primera
persona del grafo.

Para consultas no vacías, el resultado es el mismo que antes.

### 4. El repositorio delega

```kotlin
fun findContactByRoleOrName(query: String): RelationalContact? =
    ContactMatcher.find(query, relationalContacts.value)
```

La API pública no cambia: las siete llamadas existentes siguen igual.

---

## Files Changed

| File | Change |
|---|---|
| `data/contacts/ContactMatcher.kt` | **New** — política pura de coincidencia exacta/parcial por nombre, rol, apodo y tokens |
| `data/FifoDataRepository.kt` | **Updated** — `findContactByRoleOrName()` delega en `ContactMatcher` |
| `test/.../data/contacts/ContactMatcherTest.kt` | **New** — 11 pruebas de acentos, roles, apodos, tokens, cercanía y consulta vacía |
| `data/README.md` | **Updated** — `ContactMatcher` en el mapa de la capa de datos |
| `skills/README.md` | **Updated** — las skills resuelven personas con la política compartida |
| `GLOSSARY.md` | **Updated** — “resolución de contacto” |
| `docs/README.md` | **Updated** — índice de ADR |

---

## What Did NOT Change

- No cambia el grafo relacional, su fusión, la procedencia ni la cola de
  teléfonos mencionados.
- No cambia `FifoDataRepository` más allá de la delegación; sus `StateFlow` y
  firmas públicas siguen igual.
- No cambia ninguna skill, el pipeline de sesión, la telefonía, el barge-in ni
  la memoria durable.
- No se agregan dependencias ni se toca SQLite.
- No se modifica el camino legacy.

---

## Consequences

**Positive:**

- La regla que decide a quién se llama tiene pruebas propias y vive en un solo
  lugar.
- Las skills y el pipeline comparten la misma resolución; ya no hay dos copias
  de la normalización dentro del repositorio.
- Una consulta vacía deja de resolver el primer contacto por accidente.
- La política se puede probar sin emulador, SQLite ni red.

**Negative / Trade-offs:**

- La coincidencia sigue siendo heurística: "hija" puede resolver a una persona
  distinta si hay varias con ese rol y la cercanía no basta.
- Se mantiene la regla de que una coincidencia exacta gana sobre una parcial más
  cercana, incluso si la parcial sería semánticamente mejor.
- La normalización usa el locale por defecto de la JVM, igual que el código
  anterior; no se introduce un locale fijo en esta extracción.

---

## Related

- [ADR-0018](0018-username-recommender-policy.md) — política pura con
  normalización y pruebas JVM.
- [ADR-0027](0027-relational-contact-dao.md) — persistencia de contactos.
- [ADR-0031](0031-relational-graph-multi-source.md) — grafo multi-fuente.
- [ADR-0032](0032-phone-hint-confirmation.md) — confianza telefónica.
