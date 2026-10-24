# ADR-0031: Grafo relacional multi-fuente y procedencia de personas

**Status:** Accepted; ampliado por ADR-0032 (SQLite v9 e indicios pendientes de confirmación).

**Date:** 2026-10-23

**Author:** Architecture Review — Relational graph sources

---

## Context

Hasta ahora el grafo relacional de Fifo se alimentaba de dos fuentes pobres y
desconectadas entre sí:

- la **libreta del teléfono**, ingerida por
  `FifoDataRepository.syncDeviceContactsToRelationalGraph()` con una fusión
  manual escrita dentro de la fachada;
- las **personas mencionadas en conversaciones**, ingeridas por
  `updateContactsFromSessionMentions()`.

Ambas fuentes vivían en un bloque de más de 180 líneas dentro de
`FifoDataRepository`, no tenían pruebas directas y no registraban **dónde**
había aparecido cada persona. Tampoco existía una fuente FifoAmigos: los
perfiles recomendados por `FriendsScreen` se mostraban en la UI y se perdían.

El usuario pidió que el grafo no se limite a los contactos del teléfono, sino
que integre también amigos de FifoAmigos y personas citadas en conversaciones,
mapeando para cada persona:

- dónde apareció;
- si hay indicios de contacto telefónico;
- información relevante;
- cómo convive o se relaciona con el usuario.

---

## Decision

### 1. `RelationalGraphCoordinator` es la política pura de fusión

El nuevo `data/contacts/RelationalGraphCoordinator.kt` no conoce Android,
SQLite ni HTTP. Expone tres operaciones que devuelven un
`RelationalGraphMerge`:

```kotlin
data class RelationalGraphMerge(
    val contacts: List<RelationalContact>,      // grafo completo fusionado
    val changed: List<RelationalContact>,       // solo lo que debe persistirse
    val appearances: List<ContactAppearance>    // procedencia nueva
)
```

| Operación | Fuente | Regla de rol |
| :--- | :--- | :--- |
| `mergePhoneContacts` | libreta del teléfono | conserva el rol aprendido; solo adopta el rol de la libreta si el actual es genérico. |
| `mergeFifoFriends` | recomendaciones de FifoAmigos | nuevo contacto con rol `Amigo de FifoAmigos`, `trustTier = 3`, sin teléfono. |
| `mergeSessionMentions` | personas citadas en conversaciones | usa el rol inferido; si no hay rol, `Persona cercana`. |

Ninguna fuente inventa parentesco. Una recomendación de FifoAmigos no se
convierte en “nieta” ni en “conocida”; un nombre citado sin rol no se convierte
en familiar.

Las tres operaciones comparten estas garantías:

- **Preservan la memoria aprendida:** no se sobrescribe `contextualMemory` ni se
  pierde `mentionCount`.
- **Preservan la mejor puntuación:** las recomendaciones de FifoAmigos aportan
  como máximo `0.70`, calculado desde `affinityScore`, y una mención solo sube
  la cercanía.
- **Vinculan conversaciones:** las menciones agregan el `conversationId` a
  `linkedConversationIds`, con un máximo de 20 IDs distintos.
- **No duplican apariciones:** el ID de una aparición es determinístico
  (`app_<hash>` sobre `contactId|source|sourceId`).

### 2. `ContactAppearance` registra la procedencia

Se agregan a `ui/model/FifoModels.kt`:

```kotlin
enum class ContactSource { PHONE_CONTACT, FIFO_FRIEND, CONVERSATION, CALL, MANUAL }

data class ContactAppearance(
    val id: String,
    val contactId: String,
    val source: ContactSource,
    val sourceId: String? = null,   // teléfono, friendId o conversationId
    val snippet: String? = null,    // contexto inmediato, no la charla completa
    val occurredAt: String = ""
)
```

`sourceId` responde “dónde apareció” y `snippet` conserva información relevante
sin duplicar la conversación. `occurredAt` usa ISO-8601 UTC.

### 3. SQLite v8 agrega `contact_appearances` (solo local)

```sql
CREATE TABLE IF NOT EXISTS contact_appearances (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    contact_id TEXT NOT NULL,
    source TEXT NOT NULL,
    source_id TEXT,
    snippet TEXT,
    occurred_at TEXT DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_contact_appearances_user_contact
    ON contact_appearances(user_id, contact_id);
```

- La migración es `if (oldVersion < 8)` y **no toca tablas existentes**.
- El `DAO` es `local/ContactAppearanceDao.kt`, compuesto por
  `FifoDatabaseHelper`; no conoce `Context`.
- La tabla es **append-only** y no usa clave foránea: aunque
  `RelationalContactDao` reemplace el contacto con `CONFLICT_REPLACE`, las
  apariciones sobreviven.
- **No se envía al backend.** `postRelationalContact` y
  `syncRelationalContactsBatch` siguen serializando solo `RelationalContact`.

### 4. La fachada solo compone

`FifoDataRepository` conserva la API pública y delega:

```kotlin
private val relationalGraph = RelationalGraphCoordinator()

fun syncDeviceContactsToRelationalGraph(context: Context): Int
fun syncFifoFriendsToRelationalGraph(friends: List<FriendRecommendation>): Int
fun updateContactsFromSessionMentions(
    mentions: List<MentionedPersonExtraction>,
    conversationId: String? = null
)
```

Cada método:

1. fusiona con el coordinador;
2. actualiza `EpisodicMemoryModule`;
3. persiste contactos en `RelationalContactDao`;
4. persiste apariciones en `ContactAppearanceDao`;
5. sincroniza contactos con el backend en segundo plano.

`updateContactsFromSessionMentions` mantiene el parámetro `conversationId`
opcional para no romper al camino legacy, que lo llama sin ID y por tanto no
crea apariciones.

### 5. Fuentes conectadas

| Fuente | Punto de entrada | Efecto |
| :--- | :--- | :--- |
| Libreta | `syncDeviceContactsToRelationalGraph(context)` | contactos con `phoneNumber` y aparición `PHONE_CONTACT`. |
| FifoAmigos | `FriendsScreen` tras `fetchFriendRecommendations`, en `Dispatchers.IO` | perfiles con rol `Amigo de FifoAmigos` y aparición `FIFO_FRIEND`. |
| Conversaciones | `RepositorySessionMemoryConsolidator`, con `conversationId = "conv_$stableId"` | personas con rol inferido o `Persona cercana`, vínculo de conversación y aparición `CONVERSATION`. |

El camino legacy de consolidación (`VoicePipelineManager`) sigue llamando sin
`conversationId`; no se le agregó funcionalidad nueva.

### 6. Lectura y visualización de la procedencia

La procedencia no se queda en SQLite. `FifoDataRepository` expone dos lecturas
delegadas en `ContactAppearanceDao`:

```kotlin
fun getContactAppearances(contactId: String): List<ContactAppearance>
fun getContactAppearancesByContact(): Map<String, List<ContactAppearance>>
```

La segunda agrupa todas las apariciones del usuario y evita una consulta por
tarjeta. `ActivitiesScreen` la carga en `Dispatchers.IO` dentro de un
`LaunchedEffect(liveContacts)` y muestra bajo cada persona una etiqueta neutral:

```text
Visto en Libreta · FifoAmigos · Conversación
```

La UI solo lee: no crea apariciones, no modifica contactos y no toca telefonía.
Si aparece una fuente nueva para un contacto sin que cambie la lista de
contactos, la etiqueta se actualiza al reabrir el diálogo.

### 7. Indicios telefónicos explícitos

`PhoneHintExtractor` complementa las menciones sin convertirlas en una agenda
inventada:

- solo actúa si el contacto **no tiene** teléfono;
- solo se aplica en el camino nuevo, cuando la mención trae `conversationId`;
  el camino legacy conserva su comportamiento anterior;
- acepta secuencias de 8 a 15 dígitos;
- exige una palabra clave (`teléfono`, `celular`, `número`, `WhatsApp`,
  `llámame`, `márca(me)`) o un prefijo internacional (`+`, `00`, `56`);
- rechaza años, fechas `YYYYMMDD`, números demasiado cortos/largos y dígitos
  repetidos;
- normaliza móviles chilenos de 9 dígitos a `+56...`;
- nunca sobrescribe un teléfono conocido por la libreta.

Un número citado en el mismo fragmento se asocia a la persona mencionada. Si el
fragmento mezcla a esa persona con un tercero (“Marta me pasó el teléfono de su
hija…”), el número puede quedar asociado a Marta: es un **indicio**, no una
identidad verificada, y el usuario puede corregirlo. Desde ADR-0032 el indicio
vive en `contact_phone_hints` hasta que el usuario lo confirma; no se escribe en
`phoneNumber` ni se sincroniza antes.

---

## Files Changed

| File | Change |
|---|---|
| `data/contacts/RelationalGraphCoordinator.kt` | **New** — fusión multi-fuente y generación de apariciones |
| `data/contacts/PhoneHintExtractor.kt` | **New** — extracción conservadora de teléfonos explícitos en menciones |
| `data/local/ContactAppearanceDao.kt` | **New** — persistencia local append-only y lecturas por contacto |
| `ui/model/FifoModels.kt` | **Updated** — `ContactSource` y `ContactAppearance` |
| `data/FifoDataRepository.kt` | **Updated** — delega la fusión, conecta las tres fuentes y expone lecturas |
| `data/local/FifoDatabaseHelper.kt` | **Updated** — SQLite v8, esquema/migración y DAO |
| `data/memory/RepositorySessionMemoryConsolidator.kt` | **Updated** — pasa `conversationId` |
| `ui/screens/FriendsScreen.kt` | **Updated** — ingesta de recomendaciones en IO |
| `ui/screens/ActivitiesScreen.kt` | **Updated** — etiqueta de procedencia en la tarjeta de contacto |
| `test/.../data/contacts/RelationalGraphCoordinatorTest.kt` | **New** — 15 pruebas JVM |
| `test/.../data/contacts/PhoneHintExtractorTest.kt` | **New** — 14 pruebas JVM de números explícitos |
| `data/README.md` | **Updated** — coordinador, DAO y flujo |
| `data/local/README.md` | **Updated** — SQLite v8 y tabla nueva |
| `docs/README.md` | **Updated** — índice de ADR |

---

## What Did NOT Change

- No cambian voz, `VoiceSession`, `VoiceSessionPipeline`, `CaptureArbiter`,
  `SpeechSegmenter`, barge-in, eco, telefonía ni memoria durable.
- No cambia el camino legacy: no recibe apariciones ni fusiones nuevas, y
  tampoco extrae indicios telefónicos porque sus menciones no traen
  `conversationId`.
- No se agrega funcionalidad nueva a `VoicePipelineManager`.
- No se envía procedencia al backend ni se modifica ningún endpoint.
- No se elimina `contact_conversation_mentions`; queda como tabla histórica sin
  uso.
- No se agregan dependencias nuevas (los tests usan JUnit 4 ya existente).

---

## Consequences

**Positive:**

- El grafo responde “¿quién es?”, “¿dónde apareció?” y “¿cómo se relaciona?”
  con datos de tres fuentes.
- Un teléfono citado explícitamente se asocia sin pedirle al usuario que lo
  transcriba, y la libreta siempre tiene prioridad.
- La procedencia ya es visible en la tarjeta de contacto, sin agregar una
  pantalla nueva.
- La fusión es pura y testeable en JVM; la fachada deja de contener 180 líneas
  de política relacional.
- Las apariciones son idempotentes: reingestar la libreta, FifoAmigos o la misma
  conversación no duplica filas.
- Un contacto del teléfono puede enriquecerse con menciones y con FifoAmigos sin
  perder su historial.

**Negative / Trade-offs:**

- Hay dos escrituras locales (contacto + aparición) que no comparten una única
  transacción; un fallo entre ambas deja el contacto sin su aparición hasta la
  próxima ingesta.
- La tabla `contact_appearances` crece de forma append-only y no tiene todavía
  política de poda.
- Los amigos de FifoAmigos entran con confianza media y sin teléfono; no se
  pueden usar para llamar hasta que el usuario los asocie manualmente.
- La coincidencia de menciones sigue siendo por nombre/rol, no por identidad
  única; dos personas homónimas podrían fusionarse.
- La etiqueta de procedencia se refresca cuando cambia la lista de contactos;
  una aparición aislada sin cambio de contacto aparece al reabrir el diálogo.
- Un teléfono extraído de una mención no está verificado por el usuario ni por
  la libreta; puede requerir corrección manual. Desde ADR-0032 queda como
  indicio local en `contact_phone_hints` y solo pasa a `phoneNumber` cuando el
  usuario lo confirma.

---

## Related

- [ADR-0002](0002-data-repository-modularization.md) — fachada de datos.
- [ADR-0011](0011-durable-memory-journal.md) — consolidación de conversaciones.
- [ADR-0027](0027-relational-contact-dao.md) — DAO del grafo existente.
- [ADR-0030](0030-call-state-coordinator.md) — extracción previa de la fachada.
