# ADR-0030: Estado de llamada fuera de `FifoDataRepository`

**Status:** Accepted

**Date:** 2026-10-23

**Author:** Architecture Review — Telephony state ownership

---

## Context

ADR-0002 dejó la telefonía **en la fachada** `FifoDataRepository`: la fachada
guardaba `_incomingCall`, `lastActiveCall`, los callbacks de contestar/colgar y
las mutaciones `setIncomingCall`, `answerCurrentCall` y `hangupCurrentCall`.

Ese estado es consumido por:

- la UI (`HomeScreen`, `VoiceInteractionSheet`);
- el camino legacy (`VoicePipelineManager`);
- el camino nuevo (`AndroidTelephonyPort`, `PhoneCallSkill`);
- el receptor (`FifoPhoneCallReceiver`) y `FifoCallManager`.

La lógica es pequeña pero sensible: `answerCurrentCall` solo descolga si el
callback devuelve `true`, `hangupCurrentCall` limpia la llamada aunque el
callback falle y `lastActiveCall` conserva la última llamada vista. Al vivir
dentro de un repositorio de 1000+ líneas, cualquier cambio de telefonía
obligaba a leer un archivo de dominio ajeno y no había tests directos del
contenedor.

La regla vigente del proyecto sigue siendo que **la telefonía vive fuera de
`VoiceSession`** y que **`FifoCallManager` es el único manager**. Este ADR no
cambia ninguna de las dos.

---

## Decision

### 1. `CallStateCoordinator` es el dueño del estado

El nuevo `telephony/CallStateCoordinator.kt` contiene exactamente:

- `incomingCall: StateFlow<IncomingCallInfo?>`;
- `lastActiveCall`;
- `onAnswerCallAction` / `onHangupCallAction`;
- `setIncomingCall`, `answerCurrentCall`, `hangupCurrentCall`.

Es un contenedor puro: no contesta, no cuelga, no toca `AudioManager` y no
construye `FifoCallManager`.

### 2. `FifoDataRepository` delega y conserva la API

La fachada mantiene los mismos nombres y firmas:

```kotlin
private val callState = CallStateCoordinator()

val incomingCall: StateFlow<IncomingCallInfo?> = callState.incomingCall

var onAnswerCallAction: (() -> Boolean)?
    get() = callState.onAnswerCallAction
    set(value) { callState.onAnswerCallAction = value }

val lastActiveCall: IncomingCallInfo?
    get() = callState.lastActiveCall

fun setIncomingCall(call: IncomingCallInfo?) = callState.setIncomingCall(call)
fun answerCurrentCall(): Boolean = callState.answerCurrentCall()
fun hangupCurrentCall(): Boolean = callState.hangupCurrentCall()
```

Esto preserva a todos los consumidores, incluido `FifoCallManager`, que sigue
enlazando y desenlazando callbacks con comparación de identidad (`===`).

### 3. El comportamiento observable no cambia

Se conservan:

- `setIncomingCall(call)` actualiza `lastActiveCall` y el `StateFlow`;
- `setIncomingCall(null)` limpia solo el `StateFlow`;
- `answerCurrentCall` solo cambia `isRinging` si el callback devuelve `true`;
- `answerCurrentCall` sin callback devuelve `false` y no toca el estado;
- `hangupCurrentCall` guarda la última llamada, invoca el callback y limpia el
  estado aunque el callback devuelva `false`;
- el mismo `StateFlow` que observan la UI y el pipeline.

### 4. Se agregan pruebas JVM

`CallStateCoordinatorTest` cubre:

- llamada nula y conservación de la última activa;
- contestar con callback exitoso y fallido;
- contestar sin callback;
- colgar con y sin callback;
- desenlace del callback de colgar.

No requiere Android ni Robolectric.

---

## Files Changed

| File | Change |
|---|---|
| `telephony/CallStateCoordinator.kt` | **New** — estado puro de la llamada |
| `data/FifoDataRepository.kt` | **Updated** — delega el bloque de llamadas |
| `test/.../telephony/CallStateCoordinatorTest.kt` | **New** — pruebas JVM |
| `telephony/README.md` | **Updated** — mapa y flujo |
| `data/README.md` | **Updated** — invariante de telefonía |
| `docs/README.md` | **Updated** — índice de ADR |

---

## Relationship with ADR-0002

ADR-0002 pedía que la telefonía permaneciera en la fachada para no dispersarla
en módulos de dominio. Este ADR mantiene la fachada como **punto de acceso
único**, pero mueve el estado mutable a un contenedor de telefonía. No hay un
segundo manager, no se creó un módulo de dominio nuevo y ningún llamador cambió.

En la práctica: la letra de la invariante «el estado vive físicamente en
`FifoDataRepository`» se reemplaza por «la API vive en `FifoDataRepository` y el
estado en `CallStateCoordinator`».

---

## What Did NOT Change

- No cambian `FifoCallManager`, `AndroidTelephonyPort`, `VoiceSession`,
  `VoiceSessionPipeline`, `PhoneCallSkill`, `FifoPhoneCallReceiver` ni la UI.
- No cambia ningún endpoint, skill ni flujo de contestar/colgar.
- No se agregó un segundo manager ni una segunda fuente de llamadas.
- No se tocó el camino legacy.
- No se agregaron dependencias.

---

## Consequences

**Positive:**

- El estado de llamada tiene un dueño pequeño, puro y testeable en JVM.
- `FifoDataRepository` deja de mezclar estado de telefonía con memoria,
  comunidad y dispositivo.
- La fachada conserva compatibilidad total.

**Negative / Trade-offs:**

- Hay una indirección más entre la fachada y el `StateFlow`.
- `CallStateCoordinator` sigue siendo un objeto mutable global a través de la
  fachada; no se volvió un servicio inyectable.
- La documentación de ADR-0002 queda superada en este punto por ADR-0030.

---

## Related

- [ADR-0002](0002-data-repository-modularization.md) — fachada de datos.
- [ADR-0008](0008-capture-arbiter-telephony-port.md) — costura de telefonía.
- [ADR-0009](0009-caller-context-provider.md) — contexto de llamante.
