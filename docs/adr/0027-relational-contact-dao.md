# ADR-0027: DAO de contactos relacionales

**Status:** Accepted

**Date:** 2026-10-23

**Author:** Architecture Review — Local data ownership

---

## Context

`FifoDatabaseHelper` todavía contenía todo el grafo social local:
lectura de `user_contacts_relational`, fusión por nombre o teléfono, merge de
puntuaciones y memoria contextual, serialización de conversaciones vinculadas e
ingesta atómica de contactos del dispositivo.

Era el último bloque operativo grande del helper y mezclaba SQL con reglas de
fusión. Lo consumían `FifoDataRepository`, `SqliteMemoryStorageAdapter` y la
sincronización masiva del helper.

---

## Decision

### 1. `RelationalContactDao` es dueño del agregado

El nuevo DAO en `data/local/RelationalContactDao.kt` contiene:

- `getRelationalContacts`;
- `addOrUpdateRelationalContact`;
- `syncDeviceContactsBatch`.

No conoce `Context` ni el ciclo de vida de la base. Recibe funciones que abren
las conexiones y comparte el `Gson` del helper para
`linked_conversation_ids`.

### 2. La fusión vive en el DAO

El DAO conserva la búsqueda de un contacto existente por nombre
(case-insensitive) o teléfono, y aplica las mismas reglas de merge:

- conserva la puntuación anterior si la nueva es `<= 0.5` y la anterior es
  mayor;
- conserva las menciones anteriores si la nueva es `0` y la anterior es mayor;
- conserva la memoria contextual anterior si la nueva viene vacía;
- une los IDs de conversación y elimina duplicados;
- reutiliza el `id` existente o genera `ctc_rel_*`.

También conserva el fallback de lectura cuando la columna
`linked_conversation_ids` aún se está migrando.

### 3. La ingesta por lotes es atómica

`syncDeviceContactsBatch` abre una transacción y reutiliza la misma conexión
para cada upsert. Si falla, registra el error y no deja la transacción abierta.
El resto del comportamiento (incluido el `TAG` de log) queda contenido en el
DAO.

### 4. `FifoDatabaseHelper` compone el DAO

El helper expone:

```kotlin
val relationalContacts = RelationalContactDao(
    readableDatabase = { readableDatabase },
    writableDatabase = { writableDatabase },
    gson = gson
)
```

Conserva el singleton, el esquema y las migraciones. `FifoDataRepository`,
`SqliteMemoryStorageAdapter` y `bulkUpsertSyncBundle` usan
`helper.relationalContacts.*`. La sección de contactos del helper desaparece.

La base sigue en v7 y no hay migración nueva.

---

## Files Changed

| File | Change |
|---|---|
| `data/local/RelationalContactDao.kt` | **New** — SQL, fusión y batch de contactos |
| `data/local/FifoDatabaseHelper.kt` | **Updated** — expone `relationalContacts`, elimina el bloque y usa el DAO en el sync masivo |
| `data/FifoDataRepository.kt` | **Updated** — batch e upsert vía DAO |
| `data/modules/SqliteStorageAdapters.kt` | **Updated** — el adaptador episódico usa el DAO |
| `docs/README.md` | **Updated** — índice de ADR |
| `data/README.md`, `data/local/README.md` | **Updated** — mapa de archivos |

---

## What Did NOT Change

- El esquema de `user_contacts_relational` no cambia.
- Las reglas de fusión y los valores por defecto no cambian.
- `EpisodicMemoryModule` y la costura `MemoryStorageAdapter` no cambian.
- No se tocaron `VoiceSession`, `VoiceSessionPipeline`, telefonía, memoria
  durable ni el camino legacy.
- No se agregó una segunda fuente de datos ni una caché paralela.

---

## Consequences

**Positive:**

- El helper queda sin SQL operativo de contactos y se acerca a ser solo
  esquema/migraciones + composición.
- La fusión de contactos y su transacción tienen un único dueño.
- El DAO no depende de `Context`.

**Negative / Trade-offs:**

- Hay una indirección más para llegar al SQL de contactos.
- La lógica de fusión sigue siendo código de persistencia, no una política pura;
  extraerla requeriría un modelo intermedio y tests adicionales.
- El DAO usa `android.util.Log` para conservar el reporte de fallos del batch.
- No se agregó un test JVM específico porque el DAO solo contiene SQLite; la
  validación es de compilación y de la suite existente.

---

## Related

- [ADR-0016](0016-community-activity-dao.md) — patrón de DAO por agregado.
- [ADR-0026](0026-daily-routine-dao.md) — DAO de rutinas diarias.
- [ADR-0002](0002-data-repository-modularization.md) — módulos de dominio.
