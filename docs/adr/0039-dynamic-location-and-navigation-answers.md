# ADR-0039: Consultas informativas se responden con el LLM, no con plantillas locales

**Status:** Accepted

**Date:** 2026-10-24

**Author:** Architecture Review — Regla 1 (respuestas dinámicas)

---

## Context

La regla 1 del proyecto —“nada de mensajes hardcodeados”— exige que toda
respuesta hablada se componga con enriquecimiento y contexto conversacional. Solo
cuando ese contexto no existe se admite un fallback genérico.

`VoiceIntentRouter` la incumplía en dos lugares:

1. **Conciencia espacial.** “Fifo, ¿dónde estoy?” se resolvía en el fast-path con
   `FifoNavigationManager.getSpatialAwarenessDescription()`, que devolvía una
   plantilla fija:

   > “Te encuentras en `$addr`, en la comuna de `$city`. Tu teléfono apunta
   > físicamente hacia `$heading` (`$degrees`°).”

   La frase se hablaba tal cual, sin pasar por el LLM. No usaba la ruta activa,
   el destino pendiente, el perfil ni el historial; incluso repetía la comuna
   cuando la dirección ya la contenía.

2. **Orientación durante una ruta activa.** “¿Dónde voy?”, “¿cuánto falta?”,
   “me desvié”, “¿y ahora?” devolvían otra plantilla:

   > “`$relativeInstruction` hacia `$destinationName` (`$distText`).”

Además el router arrastraba `Context`, `FifoLocationHelper`, `CurrentLocationInfo`
y `Locale` solo para armar esas dos frases, lo que lo hacía dependiente de
Android y más difícil de probar.

El contexto necesario para responder bien ya existía, pero no se usaba:
`FifoNavigationManager.buildSystemPromptContext()` inyecta calle, comuna,
orientación de la brújula, destino, distancia restante, cuadras, indicación
relativa, estado de desvío y destino pendiente; `FifoLocationHelper` cae a la
dirección del perfil cuando no hay GPS.

---

## Decision

### 1. El fast-path solo resuelve acciones mecánicas

`VoiceIntentRouter` deja de responder consultas informativas:

- se elimina la rama de conciencia espacial (“¿dónde estoy?”, “¿en qué calle
  estoy?”, “¿dónde estamos?”);
- se elimina el bloque `isNavQuery` (“¿dónde voy?”, “¿por dónde sigo?”,
  “¿cuánto falta?”, “me desvié”, “voy bien”, “¿y ahora?”);
- se conserva **solo** la cancelación de ruta (“cancela la ruta”, “deja de
  guiarme”), porque es una acción mecánica sin redacción posible, junto con la
  confirmación de destino pendiente.

Todas esas consultas retornan `null` y siguen el camino normal: el LLM recibe el
bloque de conciencia espacial y compone la respuesta.

### 2. Se borra el generador de frases enlatadas

`FifoNavigationManager.getSpatialAwarenessDescription()` se elimina: ya no tiene
consumidores y su única función era producir la plantilla. El contexto espacial
queda en un solo lugar, `buildSystemPromptContext()`, que alimenta tanto a
`DefaultConversationContextProvider` como a `GroqDialogClient`.

### 3. El router deja de depender de Android

`VoiceIntentRouter(navigationManager)` —sin `Context`— queda puro-JVM. Es
testeable con `InMemoryLocationAdapter` y `locationManagerProvider = { null }`,
sin singleton ni orden de inicialización (ver ADR-0038).

### 4. El contexto del LLM se completa con los datos de ubicación

- `GroqDialogClient` incluye `address`, `city`, `gps_active`, `latitude` y
  `longitude` en `compactData` cuando el LLM invoca `get_current_location`.
- La regla 7 del system prompt cubre ubicación **y** orientación: usar calle,
  comuna y brújula; con ruta activa, decir destino, indicación relativa y
  distancia o cuadras; sin ruta, ofrecer una referencia del entorno o del
  domicilio; si no hay ubicación, decirlo con honestidad y nunca inventar una
  calle. Se permiten 2 o 3 oraciones breves en este caso.

---

## Files Changed

| File | Change |
|---|---|
| `skills/VoiceIntentRouter.kt` | **Updated** — sin rama espacial ni `isNavQuery`; sin `Context`, `FifoLocationHelper`, `CurrentLocationInfo`, `Locale` |
| `location/FifoNavigationManager.kt` | **Updated** — se elimina `getSpatialAwarenessDescription()` |
| `network/GroqDialogClient.kt` | **Updated** — datos de ubicación en `compactData` y regla 7 ampliada a orientación |
| `skills/FifoSkillRegistry.kt` | **Updated** — construye `VoiceIntentRouter(navigationManager)` |
| `test/.../skills/VoiceIntentRouterTest.kt` | **Updated** — prueba que ubicación y orientación activa se delegan al LLM |

---

## What Did NOT Change

- No cambia el estado de navegación ni la guía autónoma (desvíos, hitos de
  proximidad, detención prolongada).
- No cambia `FifoLocationHelper`, `LocationAdapter` ni `AndroidLocationAdapter`.
- No cambia `CurrentLocationSkill`: sigue existiendo como herramienta que el LLM
  puede invocar y su `spokenFeedback` es solo payload/fallback.
- No cambia la telefonía, la memoria durable ni la captura de audio.
- No se agregan dependencias ni claves nuevas.

---

## Consequences

**Positive:**

- La respuesta a “¿dónde estoy?” y a las preguntas de ruta usa el contexto real
  (calle, comuna, orientación, destino, distancia, desvío y perfil) y puede
  adaptar el tono y el nivel de detalle.
- El router queda puro-JVM y más simple: solo acciones mecánicas.
- Una sola fuente de contexto espacial para el prompt (`buildSystemPromptContext`).
- Se elimina una clase entera de plantillas que se desincronizaban del estado
  real de navegación.

**Negative / Trade-offs:**

- Las consultas de ubicación ahora dependen de la red y del LLM: hay latencia y
  un costo por turno. Si la API falla, el pipeline usa el fallback genérico de
  error, que es el caso permitido por la regla 1.
- La guía autónoma proactiva (`onAutonomousGuidance`) sigue usando las variantes
  de `NavigationGuidanceReport.naturalGuidanceSpoken` para hablar sin ida y
  vuelta al LLM. Es una locución originada por el sensor, no por una consulta
  del usuario, y se mantiene como deuda explícita para una iteración posterior.

---

## Verification

- JVM: `testDebugUnitTest`, `testReleaseUnitTest`, `compileReleaseKotlin` en
  verde.
- `VoiceIntentRouterTest`:
  - `testSpatialQueriesAreDelegatedToLlm` — “¿dónde estoy?”, “¿en qué calle
    estoy?”, “¿dónde estamos?” no ejecutan ninguna skill local.
  - `testActiveNavigationQueriesAreDelegatedToLlm` — con ruta activa, “¿dónde
    voy?”, “¿cuánto falta?”, “me desvié” y “¿y ahora?” se delegan y la ruta
    sigue activa.
- Emulador Android (`Pixel_9_Pro`, API 36): consulta de texto “Fifo donde
  estoy” en el pipeline legacy. El log **no** muestra “Skill ejecutada
  directamente por intención de voz”; la respuesta llega por el LLM y se
  reproduce por TTS, sin `FATAL EXCEPTION`.

---

## Related

- [ADR-0004](0004-dynamic-contextual-skills-telephony-navigation.md) — regla de
  respuestas dinámicas y skills contextuales.
- [ADR-0007](0007-geointentseam-decouple-geo-conversational-cluster.md) — seam
  geográfico.
- [ADR-0038](0038-navigation-manager-injection.md) — retiro del singleton de
  navegación.
