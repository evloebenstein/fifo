# ADR-0011: Diario durable, cola de consolidación y reparación conversacional

**Status:** Accepted

**Date:** 2026-10-22

**Author:** Architecture Review — Step 5

---

## Context

El camino nuevo ya tenía una sesión profunda (`VoiceSession`), captura sellada por
`CaptureArbiter`, telefonía fuera de la sesión y contexto de llamante en dos fases.
Faltaba que la conversación sobreviviera a un cierre de la app y que un error del
agente pudiera corregirse sin perder el hilo.

La memoria anterior vivía en `CloudApiClient.conversationHistory`, un búfer en RAM
de solo 8 mensajes. Si la app moría después de hablar, el turno desaparecía. Si la
consolidación fallaba, no había forma de reintentarla. Y una corrección del usuario
solo funcionaba si Fifo había hecho una pregunta y estaba en modo follow-up.

Este ADR cierra el paso 5: cada turno se escribe en disco antes de la locución, la
consolidación fallida queda en una cola durable con backoff, y una corrección
adyacente se reconoce dentro de una ventana corta.

---

## Decision

### 1. El turno se journaliza antes de hablar

`VoiceSession` genera la respuesta y, antes de llamar a `speakResponse`, registra el
par usuario/Fifo en `SessionMemory.recordTurn()`. La implementación de producción es
`MemoryCoordinator` + `SqliteConversationJournal`; las pruebas usan
`InMemoryConversationJournal`.

El diario (`conversation_journal`, DB v7) guarda:

- `id`, `user_id`, `session_id`, `user_text`, `assistant_text`, `created_at`
- `status` (`PENDING` / `CONSOLIDATED`), `attempts`, `next_attempt_at`, `last_error`
- `needs_repair`, `repair_text`, `repaired_at`

`recordTurn` es síncrono en el diario. La sesión corre en un scope de IO, así que la
escritura SQLite no bloquea la UI ni el hilo de audio. Si el diario falla, la
locución continúa: la memoria nunca puede tumbar la conversación.

### 2. La cola pendiente vive en disco, no en RAM

`MemoryCoordinator` es el dueño de la cola de consolidación. Un turno nace
`PENDING`; solo pasa a `CONSOLIDATED` cuando el consolidador devuelve `true`. Si el
consolidador lanza una excepción o devuelve `false`, el turno conserva
`attempts` y recibe `nextAttemptAt = now + backoff`.

El backoff es exponencial desde 30 s hasta 15 min. El coordinador arranca un bucle
de reintento al iniciar la sesión y drena al dormir, al reiniciar la sesión o al
cerrar la pipeline. Como la cola está en SQLite, un reinicio de la app recupera los
turnos pendientes de la sesión anterior.

### 3. El consolidador no borra el error original

`RepositorySessionMemoryConsolidator` convierte un lote de `JournalEntry` en las
mutaciones locales que ya usaba el camino legacy (fragmento, conversación, recuerdo,
gusto y contactos) y en la consolidación del backend. Los IDs de fragmento y
conversación se derivan del primer `JournalEntry.id`, de modo que un reintento no
cree duplicados lógicos.

`CloudApiClient.extractSessionMemoryChecked()` distingue un fallo recuperable de red
o parseo (excepción, que provoca reintento) de “no había nada que recordar” (`null`).
Esto evita que un corte de red descarte turnos en silencio.

### 4. Reparación de ±1 turno con ventana de ~10 s

`ConversationRepairPolicy` es una política pura, sin Android ni LLM. Reconoce una
corrección explícita (“te equivocaste”, “eso está mal”, “no era…”, “en realidad…”).
Un “No” aislado solo cuenta como corrección si el turno anterior quedó marcado
`needsRepair`; así no se confunde con la respuesta natural a una pregunta de Fifo.

La reparación usa el turno anterior y la corrección actual. Si el turno anterior
sigue dentro de los 10 s, `VoiceSession` detiene cualquier locución en curso y
locuta la respuesta reparada. Si la corrección llega tarde, se guarda en el diario
y se muestra en OLED, pero no se habla: no se interrumpe al usuario con una
corrección fuera de contexto. La corrección también se registra como turno nuevo
para que la consolidación futura sepa qué se corrigió.

`generateRepairTurn()` se agregó a `SpeechCognitionAdapter`; el adaptador de pruebas
registra la petición y el adaptador de Groq delega en el LLM real.

### 5. El contexto se restaura desde el diario

Al arrancar `VoiceSessionPipeline`, se limpia el historial en RAM de
`CloudApiClient` y se repueblan los últimos 4 turnos del diario (8 mensajes). El
diario es la fuente de continuidad; el historial del LLM es solo una caché de
contexto.

### 6. Solo hay un dueño de la composición

`VoiceSessionPipeline` es el único lugar que construye `MemoryCoordinator`,
`SqliteConversationJournal` y `RepositorySessionMemoryConsolidator`. `VoiceSession`
solo ve la costura `SessionMemory`; no conoce SQLite, HTTP, el backend ni el reloj
de pared. Esto mantiene el camino nuevo JVM-testeable y evita un segundo dueño de la
memoria en el facade legacy.

El flag `VOICE_SESSION_ENABLED` no cambia: debug sigue en legacy, release usa el
camino nuevo. La memoria durable es funcionalidad del camino nuevo; no se agregó al
legacy.

---

## Files Changed

| File | Change |
|---|---|
| `data/memory/ConversationJournal.kt` | **New** — modelo, estados, puerto durable e implementación en memoria |
| `data/memory/SessionMemoryConsolidator.kt` | **New** — costura del consolidador y fake de pruebas |
| `data/memory/RepositorySessionMemoryConsolidator.kt` | **New** — consolidación local y backend |
| `data/local/SqliteConversationJournal.kt` | **New** — adaptador SQLite |
| `data/local/FifoDatabaseHelper.kt` | **Updated** — DB v7, tabla `conversation_journal`, índice y CRUD |
| `pipeline/session/MemoryCoordinator.kt` | **New** — diario, cola pendiente, reintentos y backoff |
| `pipeline/session/ConversationRepairPolicy.kt` | **New** — detección de corrección y ventana de 10 s |
| `pipeline/session/VoiceSession.kt` | **Updated** — journal antes de TTS, corrección y consolidación al dormir |
| `pipeline/session/VoiceSessionPipeline.kt` | **Updated** — composición de memoria y restauración de contexto |
| `pipeline/session/SpeechCognitionSeam.kt` | **Updated** — `generateRepairTurn()` |
| `network/CloudApiClient.kt` | **Updated** — extracción de memoria con fallo recuperable |
| `test/.../MemoryCoordinatorTest.kt` | **New** — durabilidad, backoff y reinicio |
| `test/.../ConversationRepairPolicyTest.kt` | **New** — corrección, “No” y ventana |
| `test/.../VoiceSessionMemoryTest.kt` | **New** — journal antes de hablar, reparación y consolidación |

---

## What Did NOT Change

- `VoiceSession` no contesta ni cuelga llamadas, no toca `AudioManager` y no captura audio.
- `CaptureArbiter` sigue siendo el único dueño de la ruta de captura.
- El eco se sigue ignorando antes de cualquier acción de llamada.
- El RMS no corta locuciones; solo mueve la boca/OLED.
- `VoicePipelineManager` no construye un segundo `VoiceSession`, VAD, TTS o manager de telefonía.
- El camino legacy conserva su comportamiento y su consolidación actual.

---

## Consequences

**Positive:**

- Un turno ya no se pierde si la app se cierra después de hablar.
- La consolidación fallida se reintenta sin intervención y sobrevive reinicios.
- Una corrección del usuario se acepta sin wake word dentro de la ventana de reparación.
- Las correcciones tardías no interrumpen; quedan en OLED y en memoria.
- La sesión no depende de SQLite ni del backend para compilar o testearse en JVM.

**Negative / Trade-offs:**

- Cada turno agrega una escritura SQLite antes de la locución.
- La ventana de 10 s es una heurística; una corrección muy tardía se guarda, pero no se habla.
- La consolidación puede duplicar trabajo si un reintento ocurre después de una escritura local parcial; los IDs estables reducen el riesgo, no lo eliminan.
- El diario crece con la conversación; la retención/limpieza de turnos consolidados queda para una iteración posterior.

---

## Migration Path

1. La DB sube de v6 a v7 y crea `conversation_journal` sin tocar las tablas existentes.
2. `VoiceSessionPipeline` inyecta SQLite y el consolidador de producción.
3. Las pruebas siguen usando `InMemoryConversationJournal` e
   `InMemorySessionMemoryConsolidator`; no requieren Android.
4. La release siguiente puede borrar el legacy y el flag sin arrastrar la memoria
   nueva: el diario ya es independiente del facade.

---

## Related

- [ADR-0005](0005-collapse-voice-pipeline-manager-into-voice-session.md)
- [ADR-0008](0008-capture-arbiter-telephony-port.md)
- [ADR-0009](0009-caller-context-provider.md)
- [ADR-0010](0010-voice-session-pipeline-flag.md)
