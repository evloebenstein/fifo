# ADR-0032: Confirmación explícita de teléfonos mencionados

**Status:** Accepted

**Date:** 2026-10-23

**Author:** Architecture Review — Phone hint trust loop

---

## Context

ADR-0031 agregó `PhoneHintExtractor` para reconocer un teléfono cuando la
persona lo menciona explícitamente en una conversación. En esa primera versión,
el número extraído se escribía directamente en `RelationalContact.phoneNumber`.

Eso mezclaba dos cosas distintas:

- un **teléfono de la libreta**, verificado por el sistema operativo;
- un **teléfono mencionado**, que puede ser de un tercero, estar mal dictado o
  pertenecer a otra persona con el mismo nombre.

El campo `phoneNumber` no es inocuo: se persiste en
`user_contacts_relational`, se sincroniza con el backend y puede alimentar
acciones de llamada. Además, el backend no permite borrar un teléfono enviando
`null` (`new_phone = c_phone or existing["phone_number"]`), así que un indicio
equivocado sería difícil de revertir de forma remota.

La conversación también necesita un cierre de confianza: el usuario debe poder
decir “sí, ese es su teléfono” o “no, descártalo”, sin que Fifo llame ni
sincronice nada antes de esa decisión.

---

## Decision

### 1. Un indicio es un dato local, no un teléfono del contacto

Se agregan a `ui/model/FifoModels.kt`:

```kotlin
enum class PhoneHintStatus { PENDING, DISCARDED }

data class ContactPhoneHint(
    val id: String,
    val contactId: String,
    val phoneNumber: String,
    val snippet: String? = null,
    val createdAt: String = "",
    val status: PhoneHintStatus = PhoneHintStatus.PENDING
)
```

Mientras el estado sea `PENDING`:

- el número **no** se copia a `RelationalContact.phoneNumber`;
- no se escribe en la tabla que se sincroniza con el backend;
- no se usa para llamar ni para ninguna acción de telefonía.

`RelationalGraphCoordinator` deja de modificar `phoneNumber` en
`mergeSessionMentions`. En su lugar devuelve los indicios en
`RelationalGraphMerge.phoneHints`, sin tocar el contacto.

### 2. SQLite v9 agrega `contact_phone_hints` (solo local)

```sql
CREATE TABLE IF NOT EXISTS contact_phone_hints (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    contact_id TEXT NOT NULL,
    phone_number TEXT NOT NULL,
    snippet TEXT,
    status TEXT NOT NULL DEFAULT 'PENDING',
    created_at TEXT DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_contact_phone_hints_user_contact
    ON contact_phone_hints(user_id, contact_id, status);
```

- La migración es `if (oldVersion < 9)` y no toca tablas existentes.
- El DAO es `local/ContactPhoneHintDao.kt`, compuesto por
  `FifoDatabaseHelper`; no conoce `Context`.
- El `id` es determinístico (`hint_<hash>` sobre `contactId|phoneNumber`) y la
  inserción usa `CONFLICT_IGNORE`, para que el mismo indicio no se duplique.
- La tabla **no tiene clave foránea** y **no se envía al backend**. Sobrevive a
  los reemplazos de `user_contacts_relational`.

### 3. La generación sigue siendo conservadora

`PhoneHintExtractor` mantiene sus reglas de ADR-0031:

- solo actúa si el contacto **no tiene** teléfono;
- solo en el camino nuevo, cuando la mención trae `conversationId`;
- exige 8–15 dígitos y una palabra clave o un prefijo internacional;
- rechaza años, fechas `YYYYMMDD`, números repetidos y longitudes imposibles;
- nunca reemplaza un teléfono de la libreta.

La diferencia es el destino: ahora produce un `ContactPhoneHint` pendiente en
lugar de un `phoneNumber`.

### 4. Confirmar o descartar son decisiones explícitas

`FifoDataRepository` expone:

```kotlin
fun getPendingPhoneHintsByContact(): Map<String, List<ContactPhoneHint>>
fun confirmPhoneHint(hintId: String): Boolean
fun discardPhoneHint(hintId: String)
```

**Confirmar:**

1. lee el indicio `PENDING`;
2. copia el número a `RelationalContact.phoneNumber`;
3. actualiza `EpisodicMemoryModule` y `RelationalContactDao`;
4. elimina todos los indicios del contacto;
5. sincroniza el contacto con el backend en segundo plano.

A partir de ese momento el número deja de ser un indicio y sigue el camino
normal de un teléfono confirmado.

**Descartar:**

1. marca el indicio como `DISCARDED`;
2. no toca el contacto, el backend ni la telefonía.

El estado `DISCARDED` se conserva para no volver a preguntar por el mismo
número en la misma conversación.

Si un teléfono llega por otra vía —libreta del teléfono o
`addOrUpdateRelationalContact()`—, los indicios pendientes de ese contacto se
eliminan y dejan de mostrarse: el teléfono conocido tiene prioridad.

### 5. La UI distingue el origen del teléfono

En la tarjeta de persona de `ActivitiesScreen`:

- si el contacto tiene teléfono y aparece la fuente `PHONE_CONTACT`, se muestra
  **“Teléfono de la libreta: …”**;
- si el teléfono fue confirmado desde una mención, se muestra
  **“Teléfono confirmado: …”**;
- si hay indicios pendientes, se muestra una tarjeta ámbar con
  **“Teléfono mencionado: …”**, la advertencia “Sin confirmar. No se usa para
  llamar ni se sincroniza.” y los botones **Confirmar** / **Descartar**.

La UI no llama a telefonía ni escribe contactos por su cuenta: solo invoca los
métodos del repositorio.

---

## Files Changed

| File | Change |
|---|---|
| `data/contacts/RelationalGraphCoordinator.kt` | **Updated** — `RelationalGraphMerge.phoneHints`; ya no escribe `phoneNumber` |
| `data/local/ContactPhoneHintDao.kt` | **New** — cola local de indicios y estados |
| `ui/model/FifoModels.kt` | **Updated** — `ContactPhoneHint` y `PhoneHintStatus` |
| `data/local/FifoDatabaseHelper.kt` | **Updated** — SQLite v9, tabla, índice, migración y DAO |
| `data/FifoDataRepository.kt` | **Updated** — persiste indicios y expone confirmar/descartar/lectura |
| `ui/screens/ActivitiesScreen.kt` | **Updated** — origen del teléfono y acciones Confirmar/Descartar |
| `test/.../data/contacts/RelationalGraphCoordinatorTest.kt` | **Updated** — los indicios ya no modifican `phoneNumber` |
| `data/README.md` | **Updated** — cola local y flujo de confirmación |
| `data/local/README.md` | **Updated** — SQLite v9 y `contact_phone_hints` |
| `docs/README.md` | **Updated** — índice de ADR |
| `GLOSSARY.md` | **Updated** — indicio telefónico pendiente |
| `FIFO_DATABASE_MAP.md` | **Updated** — esquema v9 |

---

## What Did NOT Change

- No cambian voz, `VoiceSession`, `VoiceSessionPipeline`, `CaptureArbiter`,
  `SpeechSegmenter`, barge-in, eco, telefonía ni memoria durable.
- No se agrega funcionalidad al camino legacy: sus menciones no traen
  `conversationId` y siguen sin generar indicios.
- No se modifica `FifoCallManager`, `AndroidTelephonyPort` ni ningún flujo de
  llamada.
- No se envía `ContactPhoneHint` al backend. `postRelationalContact` y
  `syncRelationalContactsBatch` siguen serializando solo `RelationalContact`.
- No se agregan dependencias nuevas.

---

## Consequences

**Positive:**

- Un teléfono mencionado no se sincroniza ni se usa para llamar hasta que el
  usuario lo confirme.
- El usuario ve el origen del teléfono y puede corregir un indicio equivocado.
- Descartar es reversible a nivel de datos: el contacto y su teléfono de la
  libreta quedan intactos.
- La cola es local y sobrevive a reinicios; confirmar sigue funcionando offline
  para el estado local y sincroniza cuando hay red.
- El indicio es idempotente: repetir la misma mención no crea filas nuevas.

**Negative / Trade-offs:**

- Hay dos escrituras locales (contacto + indicio) que no comparten una única
  transacción; un fallo entre ambas deja el contacto sin el indicio hasta la
  próxima mención.
- Confirmar un teléfono elimina todos los indicios del contacto, incluidos los
  que el usuario todavía no había visto.
- La tabla crece con indicios `DISCARDED` y no tiene todavía política de poda.
- La asociación sigue siendo por nombre/fragmento; un número dictado para un
  tercero puede quedar asociado a la persona mencionada hasta que el usuario lo
  descarte.
- La confirmación no verifica que el número exista ni que pertenezca a esa
  persona: valida la intención del usuario, no la identidad.

---

## Related

- [ADR-0031](0031-relational-graph-multi-source.md) — grafo multi-fuente y
  extracción de teléfonos explícitos.
- [ADR-0027](0027-relational-contact-dao.md) — persistencia de contactos.
- [ADR-0011](0011-durable-memory-journal.md) — consolidación de conversaciones.
- [ADR-0009](0009-caller-context-provider.md) — contexto de llamante y
  telefonía fuera de la sesión.
