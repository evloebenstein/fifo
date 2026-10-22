# ADR-0008: CaptureArbiter y TelephonyPort — sellado de captura y telefonía

**Status:** Accepted  
**Date:** 2026-10-22  
**Author:** Architecture Review — Step 2 (Candidate 01)

---

## Context

ADR-0005 declaró que `VoicePipelineManager` se colapsaría en `VoiceSession` y que la telefonía quedaría fuera de la sesión. El colapso conversacional está en curso (paso 1), pero dos fuentes de regresión seguían vivas:

1. **Captura sin dueño único.** `VoicePipelineManager.ensureCallListeningActive()` llama a `PhoneMicRecorder.switchToCallMode(true)` en cada evento de llamada, aunque la ruta no haya cambiado. `AndroidAudioHardwareAdapter` también podía iniciar su propio `PhoneMicRecorder`. El resultado era un ciclo stop/start del micrófono en timbrado, transiciones y eventos repetidos, con cortes de audio y riesgo de `ERROR_AUDIO`.
2. **Telefonía con efectos globales.** `FifoCallManager` instalaba `FifoDataRepository.onAnswerCallAction`/`onHangupCallAction` en su `init`. Cualquier instancia —incluida la que crea `FifoPhoneCallReceiver` por cada broadcast— pisaba las acciones del manager real. Además, el receiver construía un manager completo solo para resolver un nombre de contacto.

`VoiceSession` sigue sin ser el camino de producción. Este ADR sella las costuras para el camino nuevo sin cambiar el comportamiento legacy.

---

## Decision

### 1. `CaptureArbiter` es el único dueño de la captura

`CaptureArbiter` recibe un `CaptureEnvironment` puro y decide una `CaptureRoute`:

```kotlin
enum class CaptureRoute {
    STOPPED,
    PHONE_NORMAL,
    PHONE_CALL,
    BLE_MIC,
    NATIVE_RECOGNIZER
}
```

Reglas de selección:

| Condición | Ruta |
|---|---|
| `micMuted` | `STOPPED` |
| `callActive` (timbrado o activo) | `PHONE_CALL` |
| Fuente preferida `PHONE` o BLE desconectado | `PHONE_NORMAL` |
| BLE conectado, fuente `ESP32`, con STT nube | `BLE_MIC` |
| BLE conectado, fuente `ESP32`, sin STT nube y reconocedor disponible | `NATIVE_RECOGNIZER` |
| BLE conectado, sin STT nube y sin reconocedor | `BLE_MIC` |

Invariantes:

- **Una reconfiguración por transición efectiva.** Si la ruta calculada es igual a la actual, no se toca hardware. Timbrado → activo comparte `PHONE_CALL`; eventos repetidos de BLE, mute o fuente no reconfiguran.
- **`PHONE_NORMAL` ↔ `PHONE_CALL` no para el grabador en el árbitro.** El puerto de hardware usa `PhoneMicRecorder.switchToCallMode()` para reconfigurar en una sola operación.
- **Nunca `SpeechRecognizer` durante una llamada.** La ruta de llamada es siempre `PHONE_CALL`; el reconocedor nativo se detiene antes de iniciar el micrófono de llamada. Android bloquea el reconocedor con `ERROR_AUDIO` en llamadas.
- **Tramas tardías se descartan.** El callback del micrófono comprueba la ruta actual antes de reenviar; una trama en vuelo tras cambiar a BLE/NATIVE/STOPPED no entra al VAD.
- **BLE durante llamada se acepta** si está conectado (comportamiento legacy); fuera de llamada solo si la fuente preferida es `ESP32`.
- **`shutdown()` detiene solo el hardware de la ruta activa** y deja el árbitro en `STOPPED`.

El hardware se inyecta por `CaptureHardware`; en producción lo implementa `AndroidCaptureHardware` (envuelve `PhoneMicRecorder` y, opcionalmente, `NativeSpeechRecognizer`). En JVM se prueba con un fake que cuenta transiciones.

### 2. `AudioSeam` tiene un solo adaptador de entrada y uno de salida

- `AndroidAudioHardwareInboundAdapter` es el único adaptador de entrada de producción. Traduce eventos Android/BLE a `CaptureEnvironment` y publica las tramas que decide el árbitro.
- `AndroidAudioHardwareAdapter` es **solo salida** (Android TTS). No posee micrófono, no toca `AudioManager` y no enruta llamadas.
- `AudioInboundAdapter`/`AudioOutboundAdapter` siguen siendo las costuras públicas; los adaptadores Android son `internal`.

### 3. `TelephonyPort` mantiene la telefonía fuera de `VoiceSession`

```kotlin
interface TelephonyPort {
    val callState: StateFlow<CallSnapshot>
    fun refresh(): CallSnapshot
    fun answer(): Boolean
    fun hangup(): Boolean
    fun start()
    fun stop()
}
```

- `CallSnapshot` contiene `CallPhase` (`NONE`, `RINGING`, `ACTIVE`), `callerName` y `phoneNumber`.
- `AndroidTelephonyPort` es la única clase del camino nuevo que construye `FifoCallManager`. Lo hace una vez, enlaza las acciones del repositorio explícitamente en `start()` y las desenlaza en `stop()`.
- `IncomingCallInfo?.toCallSnapshot()` es una proyección pura y testeable.
- `FifoCallManager` pasa a `internal`. Ya no tiene efectos globales en el constructor; expone `bindRepositoryActions()`/`unbindRepositoryActions()` con acciones estables para no pisar a otro manager.
- `FifoPhoneCallReceiver` usa `FifoCallManager.resolveContactName(context, number)` en el companion; ya no construye un manager desechable por broadcast.
- `VoicePipelineManager` (camino legacy) llama explícitamente a `callManager?.bindRepositoryActions()` después de construir el manager. El comportamiento de contestar/colgar por voz se conserva.

---

## Files Changed

| File | Change |
|---|---|
| `pipeline/session/CaptureArbiter.kt` | **New** — árbitro puro, entorno, rutas y puerto `CaptureHardware` |
| `pipeline/session/AndroidCaptureHardware.kt` | **New** — adaptador Android de `PhoneMicRecorder`/reconocedor nativo |
| `pipeline/session/AndroidAudioHardwareInboundAdapter.kt` | **Refactored** — delega toda decisión en `CaptureArbiter` |
| `pipeline/session/AndroidAudioHardwareAdapter.kt` | **Refactored** — solo TTS/salida |
| `pipeline/session/AudioSeam.kt` | **Updated** — documenta el dueño único |
| `telephony/TelephonyPort.kt` | **New** — costura, `CallSnapshot`, `InMemoryTelephonyPort` |
| `telephony/AndroidTelephonyPort.kt` | **New** — implementación Android |
| `telephony/FifoCallManager.kt` | **Refactored** — `internal`, bind/unbind explícito, resolver estático |
| `telephony/FifoPhoneCallReceiver.kt` | **Updated** — sin manager desechable |
| `pipeline/VoicePipelineManager.kt` | **Updated** — enlaza acciones del repositorio explícitamente |
| `audio/PhoneMicRecorder.kt` | **Updated** — `internal` |
| `test/.../CaptureArbiterTest.kt` | **New** — tabla de transiciones y tramas tardías |
| `test/.../TelephonyPortTest.kt` | **New** — proyección y transiciones en memoria |

---

## What Did NOT Change

- `VoicePipelineManager` sigue siendo el único camino de producción. No se cambió la selección efectiva de fuente, el VAD, el barge-in ni el enrutamiento de llamadas legacy.
- `VoiceSession`/`TurnOrchestrator` siguen sin ser usados por producción. El flag de una release (`VOICE_SESSION_ENABLED`) y el cableado del árbitro llegan en el paso 4.
- La identificación de llamantes contra el backend (`CallerContextProvider`, conocido vs desconocido) es el paso 3; este ADR solo expone `CallSnapshot`.
- `FifoDataRepository` sigue siendo la fuente de verdad de la llamada entrante y de las acciones contestar/colgar.

---

## Consequences

**Positive:**

- El micrófono ya no se reconfigura en cada evento de llamada; la tabla de transiciones es testeable en JVM.
- Una trama tardía no puede colarse después de cambiar de fuente.
- El reconocedor nativo no puede iniciarse durante una llamada.
- La telefonía tiene una costura explícita y un solo dueño; el receiver ya no pisa callbacks globales.
- `AndroidTelephonyPort` y `CaptureArbiter` están listos para el cableado de `VoiceSession` sin arrastrar Android a las pruebas.

**Negative / Trade-offs:**

- Durante la migración conviven el camino legacy (`VoicePipelineManager` + `FifoCallManager` directo) y el camino nuevo (`VoiceSession` + `CaptureArbiter` + `TelephonyPort`). El flag debe garantizar que solo uno construya hardware/telefonía.
- `FifoCallManager` sigue siendo `internal` pero visible en todo el módulo; la protección real es la regla de agente y la revisión de código, no el compilador.
- `CaptureArbiter` acepta BLE durante llamadas para preservar el comportamiento legacy; si en el futuro se decide usar solo el micrófono del celular, es un cambio de política con su propia prueba.

---

## Supersedes

Este ADR **reemplaza las afirmaciones de audio/telefonía de ADR-0005** que daban por implementada la separación. La decisión de colapsar `VoicePipelineManager` en `VoiceSession` sigue vigente; lo que cambia es que la captura y la telefonía quedan detrás de `CaptureArbiter` y `TelephonyPort`, respectivamente.

---

## Migration Path

1. Paso 3: `CallerContextProvider`/`CallerContextCoordinator` consumen `TelephonyPort.callState` y enriquecen conocido/desconocido contra el backend (ver [ADR-0009](0009-caller-context-provider.md)).
2. Paso 4: `VoiceSession` usa `CaptureArbiter` para entrada y `TelephonyPort` como observador externo; `VoicePipelineManager` delega con `VOICE_SESSION_ENABLED`.
3. Release siguiente: borrar el camino legacy y el flag; `FifoCallManager` deja de ser visible fuera de `telephony`.
