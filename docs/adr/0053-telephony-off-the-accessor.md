# ADR-0053: Telefonía fuera del accessor

**Status:** Accepted

**Date:** 2026-11-02

**Author:** Architecture Review — Telefonía y pipeline legacy

---

## Context

Tras ADR-0052 el contador quedó en **66** llamadas y la telefonía aportaba **24**
de ellas, repartidas en tres archivos:

| Archivo | Llamadas | Qué leía o escribía |
| :--- | ---: | :--- |
| `FifoCallManager` | 15 | perfil de emergencia, `incomingCall`, `setIncomingCall`, callbacks de contestar/colgar |
| `AndroidTelephonyPort` | 5 | `incomingCall`, `answerCurrentCall()`, `hangupCurrentCall()` |
| `FifoPhoneCallReceiver` | 4 | `setIncomingCall`, `incomingCall` |

El estado visible de la llamada ya tenía un dueño en `AppGraph.telephony`
(`CallStateCoordinator`), pero la telefonía seguía entrando por el accessor.
La pregunta de esta pasada:

> ¿Puede la telefonía entera hablar con `CallStateCoordinator` y con el perfil
> inyectado, sin que exista un segundo estado de llamada en el accessor?

---

## Decision

### 1. `FifoCallManager` recibe el coordinador y el perfil

El constructor pasa de depender del accessor a recibir:

```kotlin
FifoCallManager(
    context = context,
    callState = graph.telephony,
    profileProvider = { graph.identity.userProfile.value },
    onAnnounceCall = ...,
    onCommunicationModeChanged = ...
)
```

`isCommunicationModeActive()`, `handleAudioModeChanged()`, `handleCallState()`,
`bindRepositoryActions()` y `unbindRepositoryActions()` operan sobre
`CallStateCoordinator`. El nombre de los dos últimos se conserva para no romper
el contrato del legacy ni la invariante de `AGENTS.md`.

`resolveContactName()` deja de ser una función que lee el perfil global:

```kotlin
FifoCallManager.resolveContactName(context, phoneNumber, profile)
```

El contacto de emergencia solo se consulta cuando el permiso de contactos no
está concedido, con exactamente la misma regla que antes (últimos 8 dígitos).

### 2. `AndroidTelephonyPort` recibe el coordinador

El puerto construye el manager con `graph.telephony` y un `profileProvider`.
Su propiedad pública `callState: StateFlow<CallSnapshot>` no cambia; el
coordinador entra como dependencia privada (`coordinator`) para no chocar con
el nombre del contrato. `answer()` y `hangup()` llaman a
`coordinator.answerCurrentCall()` y `coordinator.hangupCurrentCall()`.

`VoiceSessionPipeline` es quien cablea:

```kotlin
AndroidTelephonyPort(
    context = context,
    scope = scope,
    coordinator = graph.telephony,
    profileProvider = { graph.identity.userProfile.value }
)
```

### 3. El receptor toma el grafo

`FifoPhoneCallReceiver` ya no escribe en el accessor: obtiene
`AppGraph.instanceOrNull()`, usa `graph.telephony` para publicar el timbrado y
`graph.identity.userProfile` para resolver el nombre. `Application.onCreate()`
instala el grafo antes de que se entregue cualquier broadcast; si no está
instalado, el receptor registra el aviso y no inventa estado.

### 4. El facade legacy también cablea el coordinador

`VoicePipelineManager` construye su `FifoCallManager` con
`appGraph.telephony` y `appGraph.identity.userProfile`. El legacy sigue usando
`callManager?.bindRepositoryActions()` y `startListening()`, así que timbrado,
contestado, colgado y VoIP conservan el mismo flujo.

### 5. Contador

La telefonía baja de **24 a 0** llamadas y el total de **66 a 42**:

| Capa | Llamadas |
| :--- | ---: |
| `pipeline` (`VoicePipelineManager`) | 41 |
| `AppGraph` (mensaje de error) | 1 |
| `telephony` | 0 |

---

## Consequences

### Lo que mejora

- **Un solo estado de llamada.** `CallStateCoordinator` es el único dueño de
  `incomingCall`; la telefonía, la UI y el pipeline leen el mismo `StateFlow`.
- **El receptor no crea managers.** Sigue resolviendo el nombre con la función
  estática, pero recibe el perfil y publica en el coordinador del grafo.
- **El contacto de emergencia es explícito.** La regla vive en
  `resolveContactName(context, number, profile)` y no depende de un singleton.

### Lo que cuesta

- **Más parámetros en el manager.** El constructor crece en dos dependencias,
  pero el manager sigue siendo `internal` y solo lo construyen el puerto y el
  facade legacy.
- **El receptor depende del grafo.** Si el proceso despierta sin
  `FifoApplication`, el evento se ignora en vez de escribir en un accessor que
  ya no posee estado.

### Lo que **no** resuelve

- **`VoicePipelineManager` sigue leyendo `incomingCall` por el accessor.** Es
  parte de las 41 llamadas del pipeline y se migra en la siguiente pasada.
- **El accessor conserva métodos de telefonía.** Se borran junto con el objeto
  cuando el pipeline llegue a cero.
- **No hay prueba JVM del flujo Android completo.** `FifoCallManager` depende de
  `TelephonyManager`, `AudioManager` y `TelecomManager`; la tabla de verdad
  sigue siendo `CallStateCoordinatorTest` más la suite de llamadas existente.

---

## Migration plan

| Paso | Alcance | Estado |
| :--- | :--- | :--- |
| 1 | `FifoCallManager` con coordinador y perfil | Hecho |
| 2 | `AndroidTelephonyPort` con coordinador | Hecho |
| 3 | `FifoPhoneCallReceiver` sobre `AppGraph` | Hecho |
| 4 | Facade legacy cablea `appGraph.telephony` | Hecho |
| 5 | `VoicePipelineManager` fuera del accessor | Pendiente |
| 6 | Borrar el accessor a cero | Pendiente |

Contador al cerrar esta pasada: **42 llamadas** (antes 66).

---

## Verification

| Prueba | Qué fija |
| :--- | :--- |
| `CallStateCoordinatorTest` | contestar/colgar actualizan el estado y la última llamada activa |
| `CallerContextCoordinatorTest` | el timbrado anuncia local y descarta llamadas terminadas |
| `VoiceSessionTest` | la sesión delega la acción de llamada sin tocar hardware |
| `FifoStateHoldersTest` | la UI sigue leyendo `graph.telephony` |

Suite completa: **459 tests debug / 459 release, 0 fallos, 6 omitidos**, más
`compileReleaseKotlin`.

---

## Invariants

1. **Un solo dueño del estado de llamada.** `CallStateCoordinator`; nadie crea
   un segundo `incomingCall`.
2. **La telefonía recibe lo que usa.** `FifoCallManager` recibe coordinador y
   perfil; no lee el accessor.
3. **El receptor publica en el grafo.** No construye un manager ni escribe
   estado global fuera de `AppGraph.telephony`.
4. **El legacy conserva su flujo.** `bindRepositoryActions()` sigue enlazando
   contestar/colgar; el cambio es de origen de estado, no de comportamiento.
5. **Contestar/colgar por voz sigue exigiendo “Fifo contesta”/“Fifo cuelga”.**
   La migración no toca `UtterancePolicy.classifyCallAction()`.
