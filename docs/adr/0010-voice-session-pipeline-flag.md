# ADR-0010: VoiceSessionPipeline y el flag de una release

**Status:** Accepted  
**Date:** 2026-10-22  
**Author:** Architecture Review — Step 4

---

## Context

Los pasos 1–3 dejaron piezas profundas y testeables en JVM: `TurnOrchestrator`, `CaptureArbiter`, `TelephonyPort`, `CallerContextProvider` y `CallerContextCoordinator`. Sin embargo, producción seguía usando el camino legacy de `VoicePipelineManager` y el camino nuevo no estaba compuesto. Además, el módulo nuevo conservaba un barge-in por RMS (`AudioInboundFilter`) que podía cortar la locución por energía, y el VAD/pre-roll seguía duplicado en el facade.

Este ADR cierra el paso 4: activa el camino nuevo en release con un flag de una release, lo mantiene apagado en debug, y garantiza que solo se construya un pipeline.

---

## Decision

### 1. Un flag, una selección pura y un solo pipeline

`VOICE_SESSION_ENABLED` se define por `buildType`:

| Variante | Valor |
|---|---|
| `debug` | `false` |
| `release` | `true` |

`VoicePipelineModeSelector.select(flagEnabled, hasCloudStt)` decide el camino efectivo:

- `SESSION` solo si el flag está encendido **y** hay STT en la nube.
- `LEGACY` en cualquier otro caso.

El fallback es deliberado: sin claves de Groq/OpenAI el camino nuevo no puede transcribir y Fifo quedaría sordo. El release enciende el flag; si el operador no configuró claves, se conserva el legacy.

`VoicePipelineManager.start()` evalúa la selección después de crear `CloudApiClient`. Si el modo es `SESSION`, construye `VoiceSessionPipeline` y retorna **antes** de arrancar TTS legacy, `NativeSpeechRecognizer`, `PhoneMicRecorder`, `FifoCallManager` o el VAD del facade. Los recursos legacy que sí son objetos (`AudioPlayer`, `PhoneMicRecorder`) se crean de forma perezosa para que el modo nuevo no los instancie. Si es `LEGACY`, no construye `VoiceSession` ni los adaptadores nuevos. La propiedad perezosa `voiceSession` se reemplazó por un getter nullable sobre la composición activa para que el modo legacy nunca cree una segunda sesión.

`VoicePipelineManager` sigue siendo el facade único de `MainActivity`/`FifoVoiceService`: espeja los `StateFlow` del pipeline nuevo y delega los métodos públicos. No hay dos pipelines vivos.

### 2. `VoiceSessionPipeline` es la raíz de composición del camino nuevo

`VoiceSessionPipeline` es el único lugar que construye:

- `VoiceSession`
- `AndroidAudioHardwareInboundAdapter` (captura)
- `AndroidAudioHardwareAdapter` (TTS)
- `AndroidTelephonyPort`
- `CallerContextCoordinator` + `CallerContextProvider`

También conecta el estado de llamada con la captura (`setCallMode`), despierta a Fifo durante una llamada, anuncia el contexto local de timbrado y enruta las acciones de llamada fuera de la sesión.

### 3. VAD y pre-roll viven en `SpeechSegmenter`

`SpeechSegmenter` posee el VAD, el pre-roll de ~480 ms y la construcción del WAV. `VoiceSession.onAudioFrameReceived()` solo le entrega tramas y usa el RMS para la boca/OLED. Cuando el segmentador cierra una frase, la sesión transcribe y decide el turno.

`AudioInboundFilter` y `InboundFrameResult` se eliminaron: ya no existe una ruta que corte TTS por RMS.

### 4. Barge-in por contenido, nunca por energía

El corte de una locución ocurre después de la transcripción:

- Una nueva invocación “Fifo …” corta y procesa la consulta.
- Una orden de silencio (“Fifo cállate”, “Fifo para”, “Fifo basta”) corta sin responder.
- Un eco de la propia voz se ignora y no corta TTS.
- El RMS solo actualiza la boca/OLED y los niveles de UI.

El escudo de eco se evalúa antes del control de llamada. El anuncio de timbrado contiene las frases “Fifo contesta” y “Fifo cuelga”; sin este orden, el propio TTS podría contestar una llamada. El control de llamada exige además una orden corta y explícita (`isExplicitCallCommand`), con palabra de activación completa.

### 5. Telefonía fuera de la sesión, con una costura de acción

`VoiceSession` recibe:

- `callState: StateFlow<CallSnapshot>?` para clasificar acciones y saber si hay timbrado/llamada.
- `onCallAction: ((CallAction) -> Boolean)?` para entregar la acción al exterior.

No llama a `answer()`/`hangup()` ni toca `AudioManager`. `VoiceSessionPipeline.handleCallAction()` es quien habla con `AndroidTelephonyPort`:

- `ANSWER` solo si está timbrando.
- `HANGUP` si hay llamada.
- `CALLER_STATUS` locuta el contexto de llamante.

`AndroidTelephonyPort` sigue siendo el único dueño de `FifoCallManager` en el camino nuevo.

### 6. El camino legacy no cambia

Con el flag apagado, `VoicePipelineManager` ejecuta exactamente el camino anterior. No se tocaron las reglas de VAD, barge-in, telefonía ni captura legacy. Las pruebas del camino legacy siguen verdes.

---

## Files Changed

| File | Change |
|---|---|
| `app/build.gradle.kts` | `VOICE_SESSION_ENABLED`: debug `false`, release `true` |
| `pipeline/VoicePipelineMode.kt` | **New** — selector puro `LEGACY`/`SESSION` |
| `pipeline/VoicePipelineManager.kt` | **Refactored** — selección por flag, composición nueva, espejo de flujos y delegación; sin `VoiceSession` perezoso |
| `pipeline/session/VoiceSessionPipeline.kt` | **New** — raíz de composición del camino nuevo |
| `pipeline/session/SpeechSegmenter.kt` | **New** — VAD, pre-roll y WAV |
| `pipeline/session/VoiceSession.kt` | **Updated** — segmentador, barge-in por transcripción, acciones de llamada, mute idempotente, sin corte por RMS |
| `pipeline/session/AudioInboundFilter.kt` | **Deleted** — reemplazado por `SpeechSegmenter` |
| `audio/VadProcessor.kt` | **Updated** — reloj inyectable para pruebas deterministas |
| `test/.../SpeechSegmenterTest.kt` | **New** — pre-roll, cierre de frase y WAV |
| `test/.../VoicePipelineModeSelectorTest.kt` | **New** — flag y fallback |
| `test/.../VoiceSessionTest.kt` | **Updated** — barge-in por contenido, eco de timbrado y acciones de llamada |

---

## What Did NOT Change

- `VoiceSession` no contesta ni cuelga llamadas; solo clasifica y entrega la acción.
- `CallerContextProvider` sigue anunciando local primero y enriqueciendo en paralelo.
- `CaptureArbiter` sigue siendo el dueño único de la ruta de captura.
- La memoria durable y la reparación conversacional son el paso 5.
- El camino legacy sigue siendo el que corre en debug y el que se usa si no hay STT de nube.

---

## Consequences

**Positive:**

- Release usa el camino nuevo; debug conserva el legacy para iterar sin riesgo.
- Solo se construye un pipeline y un dueño de telefonía/captura por proceso.
- El barge-in ya no depende del volumen; un ruido fuerte no puede cortar a Fifo.
- El eco del anuncio de timbrado no puede contestar una llamada.
- `SpeechSegmenter`, `VoiceSession`, el selector y las acciones de llamada son testeables en JVM.

**Negative / Trade-offs:**

- Durante una release conviven dos caminos en el código. El borrado del legacy y del flag es la release siguiente.
- El camino nuevo exige STT de nube para activarse; sin claves se usa el legacy.
- El anuncio de timbrado puede retrasar una orden de llamada hasta que termine el eco; se prioriza no auto-contestar.
- La memoria conversacional del camino nuevo todavía no consolida turnos; llega en el paso 5.

---

## Migration Path

1. Esta release: `VOICE_SESSION_ENABLED` apagado en debug y encendido en release; verificar llamadas, timbrado, contestar/colgar y OLED.
2. Release siguiente: borrar el camino legacy, `VoicePipelineManager` y el flag; `VoiceSessionPipeline` pasa a ser la única raíz de composición.
3. Paso 5: memoria durable, cola de pendientes y reparación con contexto conversacional.

---

## Related

- [ADR-0005](0005-collapse-voice-pipeline-manager-into-voice-session.md)
- [ADR-0008](0008-capture-arbiter-telephony-port.md)
- [ADR-0009](0009-caller-context-provider.md)
