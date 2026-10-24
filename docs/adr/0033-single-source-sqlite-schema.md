# ADR-0033: Una sola fuente para el esquema SQLite

**Status:** Accepted

**Date:** 2026-10-23

**Author:** Architecture Review — Schema consolidation

---

## Context

`FifoDatabaseHelper` era el dueño de todo el DDL local, pero lo repetía en tres
rutas: `onCreate`, `onUpgrade` y `onOpen`. La auditoría encontró:

- `contact_phone_hints`, `conversation_fragments`, `user_contacts_relational`,
  `contact_appearances` y `user_daily_routines` definidas **tres veces**;
- `activity_participants`, `contact_conversation_mentions` y
  `community_activities`, **dos veces**;
- 611 líneas de helper, de las cuales más de 500 eran SQL.

La duplicación ya había producido una deriva real: `onUpgrade` v3 y `onOpen`
creaban `user_contacts_relational` **sin** la columna
`linked_conversation_ids`, que sí existía en `onCreate` y en la migración v4.
Una base reparada por `onOpen` podía quedar con un esquema distinto al de una
base nueva, y el helper seguía siendo el único lugar donde descubrirlo.

Las versiones v7 (diario durable) y v8/v9 (procedencia e indicios) ya habían
mostrado que el esquema crece por bloques. Sin una fuente única, cada bloque
nuevo multiplica las copias y las oportunidades de divergencia.

---

## Decision

### 1. `FifoSchema` es la única fuente del DDL local

Se agrega `data/local/FifoSchema.kt`:

```kotlin
internal object FifoSchema {
    const val VERSION = 9

    val createStatements: List<SchemaStatement>      // base nueva
    val ensureStatements: List<SchemaStatement>      // reparación en cada apertura
    fun upgradeStatements(oldVersion: Int): List<SchemaStatement>
}
```

Cada tabla, columna e índice se declara una sola vez como `private val` y las
tres composiciones reutilizan exactamente la misma cadena. `FifoDatabaseHelper`
solo compone y ejecuta; no queda SQL de esquema en el helper.

### 2. Las sentencias tolerantes se marcan, no se adivinan

```kotlin
internal data class SchemaStatement(
    val sql: String,
    val tolerant: Boolean = false
)
```

`tolerant = true` se usa únicamente para los `ALTER TABLE ... ADD COLUMN`, que
fallan cuando la columna ya existe. El helper los ejecuta con `try/catch`; el
resto de sentencias falla de forma visible, como antes. Así no se silencian
errores de `CREATE TABLE` o `CREATE INDEX`.

### 3. La reparación de apertura conserva su forma

`ensureStatements` mantiene la reparación idempotente de `onOpen`:

- `conversation_fragments`;
- `user_contacts_relational` (con `linked_conversation_ids`);
- `contact_appearances` y su índice;
- `contact_phone_hints` y su índice;
- `user_daily_routines`;
- los `ALTER TABLE` tolerantes de `interest_tags` y `username`.

La diferencia es que ahora la tabla de contactos usa el DDL completo, lo que
corrige la deriva en bases antiguas.

### 4. La migración sigue siendo incremental

`upgradeStatements(oldVersion)` reproduce los bloques existentes:

| Versión | Agrega |
| :--- | :--- |
| v2 | `conversation_fragments` |
| v3 | `user_contacts_relational` completo, `user_daily_routines` |
| v4 | `linked_conversation_ids` (tolerante), `contact_conversation_mentions` |
| v5 | `users.username` (tolerante) |
| v6 | `community_activities`, `activity_participants` |
| v8 | `contact_appearances` e índice |
| v9 | `contact_phone_hints` e índice |

El diario durable **no** entra en `FifoSchema`: `ConversationJournalSchema` sigue
siendo el dueño de su tabla y ahora expone
`INTRODUCED_VERSION = 7` para que el helper decida cuándo crearla.

### 5. Un test estructural vigila la divergencia

No hay Robolectric en el proyecto, así que `FifoSchemaTest` no abre SQLite: lee
las sentencias y verifica que

- la versión sea 9 y cada tabla aparezca una sola vez en `createStatements`;
- `ensureStatements` cubra las tablas posteriores a v1 y sus índices;
- cada `upgradeStatements(oldVersion)` agregue exactamente las tablas de su
  bloque;
- `user_contacts_relational` incluya `linked_conversation_ids` en creación,
  reparación y migración v3;
- el DDL normalizado de cada tabla sea idéntico entre crear, reparar y migrar;
- solo los `ALTER TABLE` estén marcados como tolerantes.

---

## Files Changed

| File | Change |
|---|---|
| `data/local/FifoSchema.kt` | **New** — DDL único, versión, composiciones y `SchemaStatement` |
| `data/local/FifoDatabaseHelper.kt` | **Updated** — solo ejecuta sentencias; sin SQL de esquema |
| `data/local/ConversationJournalSchema.kt` | **Updated** — `INTRODUCED_VERSION = 7` |
| `test/.../data/local/FifoSchemaTest.kt` | **New** — 13 comprobaciones estructurales |
| `data/local/README.md` | **Updated** — mapa de archivos y regla de fuente única |
| `data/README.md` | **Updated** — `FifoSchema` en la capa local |
| `FIFO_DATABASE_MAP.md` | **Updated** — dueño único del esquema v9 |
| `docs/README.md` | **Updated** — índice de ADR |

---

## What Did NOT Change

- No cambia ninguna tabla, columna, default, índice ni versión de esquema: el
  DDL es el mismo que estaba en `onCreate`.
- No cambia el orden de creación de tablas.
- No cambia `conversation_journal` ni su índice; su dueño sigue siendo
  `ConversationJournalSchema`.
- No cambia ningún DAO, repositorio, flujo de sincronización, voz, telefonía ni
  memoria durable.
- No se agregan dependencias ni se introduce Robolectric.
- No se modifica el camino legacy.

---

## Consequences

**Positive:**

- Una tabla nueva se declara una vez y las tres rutas la heredan.
- La deriva de `linked_conversation_ids` queda corregida y vigilada por test.
- El helper baja de 611 a ~200 líneas y vuelve a ser composición, no un archivo
  de SQL.
- Las migraciones son legibles como tabla de versiones y se pueden auditar sin
  comparar bloques casi idénticos.
- Un fallo de `CREATE` ya no se puede confundir con un `ALTER` repetido.

**Negative / Trade-offs:**

- El test es estructural, no ejecuta SQLite: no detecta un error de sintaxis SQL
  que solo aparecería en un dispositivo.
- `FifoSchema` y `ConversationJournalSchema` son dos fuentes de esquema. Es
  deliberado: el diario pertenece al módulo de memoria, pero la versión del
  diario debe coordinarse manualmente con `FifoSchema.VERSION`.
- Las sentencias `ALTER` siguen siendo parte del historial; una base nueva
  recibe la columna en el `CREATE`, mientras una base antigua pasa por el
  `ALTER` tolerante.

---

## Related

- [ADR-0015](0015-conversation-journal-owns-sql.md) — el diario durable es dueño
  de su esquema y CRUD.
- [ADR-0016](0016-community-activity-dao.md) — DAOs por agregado fuera del
  helper.
- [ADR-0031](0031-relational-graph-multi-source.md) — `contact_appearances`
  (v8).
- [ADR-0032](0032-phone-hint-confirmation.md) — `contact_phone_hints` (v9).
