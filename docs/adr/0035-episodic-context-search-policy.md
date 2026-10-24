# ADR-0035: Búsqueda de contexto episódico como política pura

**Status:** Accepted

**Date:** 2026-10-24

**Author:** Architecture Review — Episodic context seam

---

## Context

`EpisodicMemoryModule.searchDeepContext()` implementaba la capa 2 de la
memoria: si el backend no respondía, buscaba en contactos, rutinas, recuerdos,
historias de gustos, recordatorios, fragmentos de conversación, charlas pasadas
y gustos simples.

Ese fallback ocupaba ~160 líneas dentro del módulo y mezclaba tres
responsabilidades:

1. la llamada de red (`queryDeepContextFromServer`);
2. la lectura de ocho `StateFlow`;
3. una política de recuperación con stop words, tokenización, puntuación,
   sesgo temporal y síntesis de entidades.

Solo estaba cubierta por una prueba de integración que verificaba que apareciera
algo, no las reglas. Además, la política completa no se podía probar sin
instanciar el módulo ni sus adaptadores.

---

## Decision

### 1. `EpisodicContextSearch` es la política pura

Se agrega `data/modules/EpisodicContextSearch.kt`:

```kotlin
internal data class EpisodicContextSnapshot(
    val fragments: List<ConversationFragment> = emptyList(),
    val memories: List<FifoMemoryItem> = emptyList(),
    val conversations: List<PastConversationItem> = emptyList(),
    val tastes: List<String> = emptyList(),
    val tasteStories: List<FifoTasteStory> = emptyList(),
    val reminders: List<FifoReminderItem> = emptyList(),
    val relationalContacts: List<RelationalContact> = emptyList(),
    val dailyRoutines: List<DailyRoutine> = emptyList()
)

internal object EpisodicContextSearch {
    fun search(query: String, snapshot: EpisodicContextSnapshot): DeepContextResult
}
```

No conoce red, SQLite, Android ni `StateFlow`.

### 2. El módulo conserva la red y compone la instantánea

`EpisodicMemoryModule.searchDeepContext()` sigue siendo el punto de entrada:

1. intenta el backend;
2. si hay extractos, devuelve ese resultado;
3. si no, construye `EpisodicContextSnapshot` desde sus `StateFlow` y delega.

El orden de preferencia no cambia: el backend siempre gana cuando responde con
contenido.

### 3. Se preservan las reglas de recuperación

- minúsculas y `trim`, **sin** eliminar acentos;
- stop words y tokens de menos de 3 caracteres descartados;
- contactos, rutinas, historias y recordatorios coinciden por token;
- recuerdos, historias y recordatorios también aceptan la consulta literal de
  4 o más caracteres;
- los fragmentos puntúan 2 por token y 4 por coincidencia literal;
- una consulta temporal (“ayer”, “semana pasada”, “reciente”…) suma 3 al primer
  fragmento y 1 al segundo;
- se devuelven como máximo 3 fragmentos y 5 entidades en el resumen.

### 4. Se elimina una rama inalcanzable

El fallback temporal anterior (“si no hay extractos y la consulta es temporal,
usa el último fragmento”) nunca podía ejecutarse: cuando la consulta es
temporal y hay fragmentos, el sesgo del índice 0 ya produce una coincidencia.
Se retira para que la política no conserve código muerto.

---

## Files Changed

| File | Change |
|---|---|
| `data/modules/EpisodicContextSearch.kt` | **New** — instantánea, stop words, scoring, síntesis y resultados |
| `data/modules/EpisodicMemoryModule.kt` | **Updated** — `searchDeepContext()` solo resuelve red y delega el fallback |
| `test/.../data/modules/EpisodicContextSearchTest.kt` | **New** — 11 pruebas de contactos, rutinas, recuerdos, gustos, recordatorios, scoring, sesgo temporal, deduplicación y entidades |
| `data/README.md` | **Updated** — `EpisodicContextSearch` en el mapa de módulos |
| `GLOSSARY.md` | **Updated** — búsqueda de contexto episódico |
| `docs/README.md` | **Updated** — índice de ADR |

---

## What Did NOT Change

- No cambia el contrato de `searchDeepContext(userId, query)` ni
  `DeepContextResult`.
- No cambia la llamada al backend ni el fallback offline.
- No cambia la recuperación de fragmentos, la memoria durable, el diario ni la
  consolidación.
- No cambia `buildCompactContextWindow()` (capa 1).
- No se toca voz, telefonía, SQLite ni el camino legacy.
- No se agregan dependencias.

---

## Consequences

**Positive:**

- Las reglas de recuperación tienen pruebas unitarias rápidas y explícitas.
- `EpisodicMemoryModule` vuelve a ser orquestación: red, estado y delegación.
- La política se puede reutilizar o comparar sin instanciar adaptadores.
- El código muerto del fallback temporal deja de confundir la lectura.

**Negative / Trade-offs:**

- La búsqueda sigue siendo sensible a acentos: “orquideas” no encuentra
  “orquídeas”. Se preserva el comportamiento actual; normalizarlo sería un
  cambio de producto aparte.
- La instantánea duplica las listas en una estructura nueva en cada búsqueda;
  es barato para el tamaño típico, pero no es zero-copy.
- Las reglas de puntuación siguen siendo heurísticas y no semánticas.

---

## Related

- [ADR-0002](0002-data-repository-modularization.md) — módulos de dominio.
- [ADR-0003](0003-knowledge-graph-and-intent-router.md) — GraphRAG y
  recuperación de contexto.
- [ADR-0011](0011-durable-memory-journal.md) — memoria durable y consolidación.
- [ADR-0031](0031-relational-graph-multi-source.md) — grafo relacional.
