# ADR-0015: El diario durable es dueño de su SQL

**Status:** Accepted

**Date:** 2026-10-22

**Author:** Architecture Review — Data layer boundary

---

## Context

ADR-0011 introdujo el diario durable `conversation_journal`, pero dejó el esquema
y el CRUD dentro de `FifoDatabaseHelper`. `SqliteConversationJournal` era un
adaptador fino que solo delegaba en métodos `insertJournalEntry`,
`getPendingJournalEntries`, `markJournalConsolidated`, etc.

Eso tenía dos consecuencias:

1. El helper central conocía la cola de consolidación y la política de
   reparación, que son responsabilidades del módulo de memoria.
2. El límite del puerto `ConversationJournal` era engañoso: el adaptador no era
   el dueño real de sus consultas.

El helper sigue siendo el dueño del ciclo de vida SQLite y del esquema global,
pero no necesita conocer el SQL operativo del diario.

---

## Decision

### 1. `ConversationJournalSchema` es dueño de la tabla y el índice

El objeto `data/local/ConversationJournalSchema.kt` contiene:

- el nombre canónico `conversation_journal`;
- el `CREATE TABLE IF NOT EXISTS`;
- el índice `idx_conversation_journal_pending`.

`FifoDatabaseHelper` lo invoca en `onCreate`, `onUpgrade` y `onOpen`, pero ya no
define el SQL del diario.

### 2. `SqliteConversationJournal` es dueño del CRUD

El adaptador implementa directamente:

- `append` con `CONFLICT_REPLACE`;
- `recent` y `pending` con el mismo orden y límites;
- `markConsolidated`, `markRetry`, `markNeedsRepair` y `markRepaired`;
- el mapeo de cursor a `JournalEntry`.

`FifoDatabaseHelper` deja de exponer métodos `Journal*`.

### 3. El comportamiento observable no cambia

Se conservan exactamente:

- el esquema y la versión de base de datos (v7, sin migración nueva);
- el índice de la cola pendiente;
- las consultas, el orden y el `LIMIT`;
- la escritura con `CONFLICT_REPLACE`;
- el fallback de estado desconocido a `PENDING`;
- la reencolada de una reparación con `attempts = 0` y `next_attempt_at = 0`.

### 4. Lecturas y escrituras conservan su base

El adaptador usa `helper.readableDatabase` para consultas y
`helper.writableDatabase` para inserciones/actualizaciones, igual que el helper
anterior.

---

## Files Changed

| File | Change |
|---|---|
| `data/local/ConversationJournalSchema.kt` | **New** — tabla, columnas e índice del diario |
| `data/local/SqliteConversationJournal.kt` | **Updated** — CRUD SQLite completo y mapeo de cursor |
| `data/local/FifoDatabaseHelper.kt` | **Updated** — delega la creación del diario y elimina el CRUD `Journal*` |
| `docs/adr/0011-durable-memory-journal.md` | **Updated** — nota de evolución |
| `docs/README.md` | **Updated** — índice de ADR |

---

## What Did NOT Change

- `MemoryCoordinator` sigue siendo el dueño de la cola pendiente y del backoff.
- El turno se sigue escribiendo antes de `speakResponse`.
- `VoiceSession` sigue viendo solo `SessionMemory`.
- `VoiceSessionPipeline` sigue siendo el único compositor de la memoria durable.
- El camino legacy y `VoicePipelineManager` no se tocaron.
- La versión de la base de datos no cambia porque el SQL es idéntico.

---

## Consequences

**Positive:**

- El módulo de memoria es dueño de su esquema operativo y su CRUD.
- `FifoDatabaseHelper` pierde conocimiento de la cola de consolidación.
- El adaptador `SqliteConversationJournal` cumple de verdad el puerto
  `ConversationJournal`.
- Cambios futuros al diario quedan contenidos en dos archivos del módulo.

**Negative / Trade-offs:**

- La creación de la tabla sigue dependiendo de que el helper invoque
  `ConversationJournalSchema.create`; es una dependencia explícita y acotada.
- Hay una indirección más para encontrar el SQL del diario, a cambio de un límite
  de módulo más claro.

---

## Related

- [ADR-0011](0011-durable-memory-journal.md) — diario durable y cola de consolidación.
- [`AGENTS.md`](../../AGENTS.md) — memoria antes de hablar y dueño único de la cola.
