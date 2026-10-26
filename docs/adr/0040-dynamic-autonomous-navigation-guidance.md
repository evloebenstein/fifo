# ADR-0040: Guía autónoma redactada por el LLM y memoria conversacional de la ruta

**Status:** Accepted

**Date:** 2026-10-25

**Author:** Architecture Review — Acompañamiento de navegación

**Enmienda:** la persistencia de la sesión de ruta y la cola de avisos sin
deadline se deciden en [ADR-0041](0041-durable-navigation-session-and-notice-coalescing.md).
Este ADR conserva el resto de la decisión.

---

## Context

ADR-0039 retiró las plantillas de ubicación y orientación del fast-path, pero
dejó una deuda explícita: la guía autónoma (`onAutonomousGuidance`) seguía
locutando las variantes de `NavigationGuidanceReport.naturalGuidanceSpoken`.

Ese diseño era incompatible con la regla 1 (“nada de mensajes enlatados”) y con
el uso real del acompañante. Mientras Fifo guía, el usuario:

- se distrae antes de salir y pide que le recuerden tomar el bus;
- anota una compra o un trámite entre medio de una indicación;
- pide una pausa, más tiempo o dice que está conversando con alguien;
- corrige la ruta porque Fifo se equivocó;
- necesita que Fifo retome el resumen cuando dice “ya estoy listo”;
- requiere avisos de desvío, demora o incidencia en tiempo real.

Nada de eso cabe en una frase fija elegida por el sensor. La locución depende
del contexto conversacional, del perfil, de lo que la persona dijo hace dos
minutos y de lo que está pasando en la calle. El costo por turno adicional es
aceptado por diseño; el lenguaje estático queda reservado a fallbacks.

---

## Decision

### 1. El motor de navegación emite hechos, no frases

`FifoNavigationManager` publica avisos estructurados:

```kotlin
enum class NavigationNoticeKind {
    ARRIVED, OFF_COURSE, APPROACHING_100M, APPROACHING_50M, STALLED
}

data class NavigationNotice(
    val kind: NavigationNoticeKind,
    val destinationName: String,
    val destinationAddress: String,
    val streetName: String,
    val distanceMeters: Int,
    val blocks: Int,
    val walkingMinutes: Int,
    val relativeInstruction: String,
    val cardinalDirection: String,
    val isFacingTarget: Boolean,
    val stalledSeconds: Int = 0,
    val plannedStops: List<String> = emptyList(),
    val pauseReason: String = ""
)
```

`onAutonomousGuidance` pasa a ser `((NavigationNotice) -> Unit)?`. Se elimina
`NavigationGuidanceReport.naturalGuidanceSpoken` y con él las plantillas
`.random()` de desvío, quietud, proximidad y llegada.

`NavigationNotice.fallbackSpeech()` es el **único** texto estático del camino y
solo se locuta cuando el LLM no responde. Cumple el fallback permitido por la
regla 1.

El despacho del aviso se hace por `noticeDispatch`, que en producción usa el
`Handler` principal y en pruebas JVM entrega de forma síncrona.

### 2. `NavigationSessionMemory` es el dueño del estado conversacional

`location/NavigationSessionMemory.kt` (puro-JVM) guarda:

- **Encargos** (`PlannedStop`): `add_stop`, duplicados ignorados por etiqueta,
  `complete_stops` los cierra.
- **Pausas** (`Pause`): con o sin plazo; una pausa con plazo expira sola.
- **Corrección** (`lastCorrection`): la última corrección del usuario sobre la
  ruta, con su antigüedad.
- **`describeForPrompt()`**: bloque de hechos para el system prompt. Devuelve
  cadena vacía cuando no hay nada que contar.

`FifoNavigationManager` la reinicia en `startNavigation()`,
`confirmPendingDestination()` y `stopNavigation()`: la memoria pertenece a la
ruta, no al proceso.

Durante una pausa consciente se suprimen los avisos de desvío, quietud e hitos
(y se mantiene la distancia de referencia para no disparar un falso desvío al
reanudar). La llegada (`ARRIVED`) siempre se anuncia: es el objetivo del viaje.

### 3. La herramienta `manage_navigation_session` deja el estado al LLM

`skills/NavigationSessionSkill.kt` expone `manage_navigation_session` con
acciones:

| Acción | Efecto |
|---|---|
| `add_stop` | Anota un encargo en el camino (con `label`). |
| `complete_stops` | Marca los encargos como realizados. |
| `pause` | Pausa el acompañamiento (`reason`, `minutes` opcional). |
| `resume` | Reanuda cuando el usuario está listo. |
| `correct` | Registra una corrección de ruta (`correction`). |
| `status` | Devuelve el estado actual de la ruta y de la sesión. |

Devuelve datos (`planned_stops`, `paused`, `pause_reason`, `pause_minutes`,
`relative_instruction`, `distance_meters`, …) y un `spokenFeedback` de
emergencia. No redacta la respuesta final.

Se registra en `FifoSkillRegistry` (14 herramientas) y su esquema compacto vive
en `GroqToolCatalog`.

### 4. El pipeline pide la locución y usa fallback solo si falla

`GroqDialogClient.narrateNavigationNotice(notice)` compone un prompt con el
evento, el destino, la calle, la distancia, la indicación relativa, el rumbo,
los segundos detenido, los encargos pendientes y la pausa. No usa herramientas
ni altera el historial conversacional, y tiene un timeout de 6 s. Devuelve
`null` si no hay clave, si falla o si el modelo responde con el prefijo de
error.

`CloudApiClient.narrateNavigationNotice()` expone esa costura.

`VoicePipelineManager` cablea ambos caminos (legacy y `VoiceSessionPipeline`)
con `handleNavigationNotice(notice, speak)`:

1. encola el aviso y coalesce al más reciente;
2. espera a que el pipeline no esté en `PROCESSING` ni `SPEAKING` (sin plazo,
   ADR-0041);
3. pide la narración al LLM;
4. locuta la respuesta o, si es nula, `notice.fallbackSpeech()`.

El aviso nunca interrumpe un turno en curso: es preferible llegar tarde que
encimarse a una respuesta.

### 5. El prompt conoce la ruta y las cosas entre medio

`FifoNavigationManager.buildSystemPromptContext()` añade el bloque de sesión y
reglas explícitas para:

- responder preguntas de ruta con la orientación y la distancia reales;
- anotar encargos y recordarlos cuando vengan al caso;
- respetar pausas sin apurar al usuario;
- reconocer y registrar correcciones, investigando con `web_search` si falta
  contexto del lugar antes de afirmar una calle;
- seguir una conversación lateral sin perder la ruta y retomarla después.

`GroqDialogClient` añade la regla 8 (“ruta activa y cosas entre medio”) y acepta
las claves nuevas de sesión en `compactData`.

---

## Files Changed

| File | Change |
|---|---|
| `location/FifoNavigationManager.kt` | **Updated** — `NavigationNotice`/`NavigationNoticeKind`, sin plantillas, supresión en pausa, `sessionMemory`, `noticeDispatch`, contexto de sesión en el prompt. |
| `location/NavigationSessionMemory.kt` | **New** — encargos, pausas, correcciones y `describeForPrompt()`. |
| `skills/NavigationSessionSkill.kt` | **New** — `manage_navigation_session`. |
| `skills/FifoSkillRegistry.kt` | **Updated** — registra la skill (14 herramientas). |
| `skills/GroqToolCatalog.kt` | **Updated** — esquema compacto de la herramienta. |
| `network/GroqDialogClient.kt` | **Updated** — `narrateNavigationNotice()`, regla 8, `compactData`. |
| `network/CloudApiClient.kt` | **Updated** — fachada de narración. |
| `pipeline/VoicePipelineManager.kt` | **Updated** — narración por LLM, espera a estado libre y fallback. |
| `test/.../location/NavigationSessionMemoryTest.kt` | **New** — memoria de sesión y avisos estructurados. |

---

## What Did NOT Change

- No cambia el hardware de ubicación ni `LocationAdapter` /
  `AndroidLocationAdapter` / `FifoLocationHelper`.
- No cambia la confirmación de destino (`pendingDestination`,
  `confirmPendingDestination`) ni el resto de las skills de navegación.
- No cambia la telefonía, la captura de audio, el barge-in ni la memoria durable.
- No cambia `VoiceSession`: la telefonía y la navegación siguen fuera de ella.
- No se agregan dependencias ni claves nuevas.
- El fast-path sigue haciendo solo acciones mecánicas.

---

## Consequences

**Positive:**

- La guía autónoma suena a una persona que camina al lado: usa el contexto de la
  ruta, la conversación y lo que el usuario anotó.
- La ruta sobrevive a distracciones, compras, pausas y correcciones sin perder
  el destino.
- El usuario puede conversar de otro tema sin que Fifo olvide a dónde va.
- El sensor no redacta: no hay plantillas que se desincronicen del estado real.
- Los tests pueden fijar encargos, pausas y correcciones sin emulador.

**Negative / Trade-offs:**

- Cada aviso autónomo cuesta un turno de LLM y añade latencia. Es el trade-off
  aceptado por diseño; el fallback cubre la falta de red.
- La locución de un aviso espera al turno en curso. La cola, la persistencia y
  el descarte por cambio de destino se resuelven en ADR-0041.
- Los hitos de 100 m y 50 m exigen antigüedad mínima entre locuciones, así que
  en tramos muy cortos puede anunciarse solo uno.

---

## Verification

- JVM: `testDebugUnitTest`, `testReleaseUnitTest`, `compileReleaseKotlin` en
  verde.
- `NavigationSessionMemoryTest`:
  - `addStop` ignora etiquetas vacías y duplicados;
  - `completeStops` devuelve y limpia los encargos;
  - pausa indefinida, pausa con plazo, expiración automática y `resume`;
  - corrección registrada, ignorada si viene vacía y limpiada con `reset()`;
  - `describeForPrompt()` vacío sin contexto y con encargos, pausa y corrección;
  - llegada emite `ARRIVED` estructurado con los encargos pendientes;
  - iniciar o detener una ruta limpia la sesión.
- Emulador Android (`Pixel_9_Pro`, API 36): con una ruta activa el motor emitió
  `STALLED` estructurado; el aviso se locutó y, cuando la llamada al LLM superó
  el timeout de 6 s, se usó `fallbackSpeech()` sin perder la ruta.
- El registro se inicializa con 14 herramientas y el arranque en frío no muestra
  `FATAL EXCEPTION` atribuible a este cambio.

---

## Related

- [ADR-0041](0041-durable-navigation-session-and-notice-coalescing.md) — sesión
  de ruta durable y avisos que no se pierden por esperar.
- [ADR-0039](0039-dynamic-location-and-navigation-answers.md) — ubicación y
  orientación delegadas al LLM; deuda de la guía autónoma que este ADR cierra.
- [ADR-0038](0038-navigation-manager-injection.md) — el manager se inyecta y no
  es un singleton.
- [ADR-0007](0007-geointentseam-decouple-geo-conversational-cluster.md) — seam
  geográfico.
- [`AGENTS.md`](../../AGENTS.md) — regla de respuestas dinámicas.
