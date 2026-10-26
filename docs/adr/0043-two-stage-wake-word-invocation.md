# ADR-0043: Invocación en dos etapas — candidato acústico y confirmación léxica

**Status:** Accepted

**Date:** 2026-10-26

**Author:** Architecture Review — Detección de la invocación en ruido

---

## Context

Hoy la palabra de activación se detecta **solo después del STT**. `VoiceSession`
transcribe el segmento que cierra `SpeechSegmenter` y `UtterancePolicy.isWakeWord()`
decide si el texto contiene "Fifo". Eso funciona en silencio y falla exactamente
donde el producto se usa: con la televisión puesta, gente alrededor o el usuario
a dos metros del teléfono.

El modo de fallo es concreto y frecuente:

1. El usuario dice "Fifo, ¿qué hora es?".
2. El ruido enmascara la palabra (dos fricativas débiles, `/f/ … /f/`) y Whisper
   transcribe solo "¿qué hora es?".
3. `resolveWake` ve una transcripción sin invocación y la descarta como audio
   ambiental.
4. El usuario repite, más fuerte. Fifo parece sordo.

El coste de un falso negativo es alto (el usuario grita y desiste); el de un
falso positivo es bajo (Fifo despierta, no habla y se vuelve a dormir a los 25 s).
La asimetría pide una etapa acústica barata **antes** del STT.

Se estudió expresamente la idea de reutilizar la huella acústica de Shazam
(Wang, 2003) como detector. **Se descarta**, por una razón de fondo: Shazam
responde "¿es esta la misma grabación?" y un wake word pregunta "¿esta persona
acaba de decir esta palabra?".

| Propiedad | Shazam (música) | "Fifo" hablado |
| :--- | :--- | :--- |
| Referencia | Una grabación maestra fija | Cambia en cada emisión (tono, duración, formantes) |
| Duración útil | 3–10 s | ~0,4–0,6 s |
| Hashes | Cientos, votación robusta | 10–50, votación débil y colisiones |
| Ruido | Los picos falsos no votan | Con 5 dB SNR sobreviven pocos picos reales |
| Variación de locutor | Ninguna | Es el eje del problema |

Un fingerprint no encuentra la canción cuando no hay canción.

---

## Decision

La invocación se resuelve en **dos etapas**: un detector acústico propone un
candidato y el léxico confirma o rescata el turno. Ninguna etapa decide sola.

```
CaptureArbiter ──► VoiceSession.onAudioFrameReceived
                        │
                        ├──► SpeechSegmenter (VAD + pre-roll) ──► STT ──►
                        │
                        └──► WakeWordDetector (solo si SLEEPING)
                                     │ candidato (score, instante)
                                     ▼
                          UtterancePolicy.resolveWake(texto, candidato)
                                     │
              ┌──────────────────────┼───────────────────────┐
              ▼                      ▼                       ▼
        CONFIRMED              ACOUSTIC_ONLY               RETRY
     (el léxico manda)     (rescata la consulta)    (despierta sin hablar)
```

### 1. `WakeWordDetector` es un generador de candidatos, no una autoridad

`pipeline/session/WakeWordDetector.kt` define:

```kotlin
interface WakeWordDetector {
    val modelId: String
    fun accept(frame: ByteArray, nowMillis: Long): WakeWordDetection?
    fun reset()
}

data class WakeWordDetection(val score: Double, val detectedAtMillis: Long, val modelId: String)
```

Reglas de la costura:

- **No abre micrófono.** `VoiceSession` le entrega las mismas tramas que ya
  captura `CaptureArbiter`. La costura de audio no cambia.
- **No tiene VAD ni pre-roll.** `SpeechSegmenter` sigue siendo el dueño único.
- **No habla, no despierta por sí solo y no autoriza acciones.** Solo devuelve
  evidencia.
- **Solo se evalúa mientras Fifo duerme.** Es el único momento en que hace falta
  y evita que la propia locución ("Fifo contesta", "Fifo cuelga") se auto-dispare.
  Esto resuelve el eco de raíz, sin AEC y sin tocar el barge-in.
- **Se reinicia al dormir, silenciar o reiniciar.** Un candidato viejo no puede
  despertar un turno futuro.

### 2. La fusión es de `UtterancePolicy`, no del detector

ADR-0042 dejó una sola autoridad léxica. La cascada no crea otra:

```kotlin
fun resolveWake(transcript: String, hasAcousticCandidate: Boolean): WakeResolution
```

| Acústica | Transcripción | Resultado | Efecto |
| :--- | :--- | :--- | :--- |
| — | contiene "Fifo" | `CONFIRMED` | Turno normal, consulta completa |
| — | sin "Fifo" | `IGNORE` | Audio ambiental (comportamiento anterior) |
| candidato | texto utilizable sin "Fifo" | `ACOUSTIC_ONLY` | La transcripción es la consulta |
| candidato | vacío / ininteligible | `RETRY` | Despierta sin hablar; repetir lo pide el LLM |

El candidato caduca a los **8 s** y se consume una sola vez: cubre una pregunta
larga dicha de un tirón sin arrastrar detecciones a un turno posterior.

### 3. La acústica nunca autoriza telefonía

`classifyCallAction()` sigue exigiendo "Fifo" **en la transcripción**. Un
candidato acústico con el texto "contesta" produce `ACOUSTIC_ONLY`, no
`CallAction.ANSWER`. Es la diferencia entre "despertar de más" y "contestar una
llamada por un eco de la televisión".

### 4. El barge-in sigue siendo por contenido

`effectiveBargeIn` solo se activa con `CONFIRMED`. Durante `SPEAKING` el detector
ni siquiera se evalúa, así que no puede cortar una locución. El invariante "el
RMS no corta locuciones" queda intacto.

---

## Elección de motor

| Motor | Licencia / coste | Modelo | Español | Veredicto |
| :--- | :--- | :--- | :--- | :--- |
| **openWakeWord** | Apache-2.0, sin clave ni cuota | ONNX/TFLite ~400 KB, embedding + clasificador | Entrenamiento propio; el repo oficial solo publica modelos en inglés | **Elegido** |
| **microWakeWord** | Open source (Open Home Foundation) | INT8 TFLite Micro, `sliding_window_size` 5, `probability_cutoff` ~0,97 | Entrenamiento propio | **Elegido para el ESP32-S3** |
| Porcupine | Free tier discontinuado (30 jun 2026); `AccessKey` validada con internet; plan comercial de pago | `.ppn` <25 KB, el más preciso | Sí, nativo | **Descartado** |
| Huella tipo Shazam / DTW | — | — | — | Descartado (ver Context) |

**Por qué openWakeWord en el teléfono:** no requiere clave ni red, el modelo cabe
en assets y corre en CPU con latencia de milisegundos por trama. Su punto débil
es que el entrenamiento oficial es inglés-céntrico; el modelo español hay que
entrenarlo.

**Evidencia de que hay que entrenarlo con voces reales:** los modelos públicos en
español entrenados solo con voz sintética reportan recobrados muy dispares en
ruido (desde ~1 % en un modelo de 405 KB hasta ~49 % en otro de 812 KB) y entre
0,6 y 2 falsos positivos por hora. Un wake word corto como "Fifo" necesita
grabaciones reales de los usuarios objetivo, no solo TTS.

**Por qué microWakeWord para el satélite:** Fifo ya habla con un ESP32-S3 por BLE.
microWakeWord está hecho para ese chip: INT8, TFLite Micro, PSRAM recomendada y
componente de ESPHome. Detecta en el satélite y manda solo el trigger: descarga
la batería del teléfono y elimina el problema del foreground service.

**Por qué Porcupine queda fuera:** técnicamente es la mejor opción (el modelo más
pequeño, sensibilidad ajustable y español nativo), pero Picovoice discontinuó el
plan gratuito el 30 de junio de 2026 y su `AccessKey` se valida con conectividad
intermitente. Un wake word que depende de la red y de una cuota no sirve para un
compañero que debe escuchar siempre.

---

## Cómo se resuelven los riesgos

| Riesgo | Resolución |
| :--- | :--- |
| **Palabra corta y confundibles** (`fino`, `hijo`, `vivo`, `FIFA`) | Negativos difíciles reales en el entrenamiento (esas palabras + conversación española de fondo), umbral doble (entrada/salida) y ventana deslizante de 5 tramas. Antes de cambiar a "oye Fifo" hay que medir: el coste es fricción de uso. |
| **Fricativas frágiles** (`/f/` inicial y final) | El modelo se entrena con augmentación de ruido a 0/5/10/15 dB, no con voz limpia. La información robusta es la transición vocálica `/i/ → /o/`. |
| **Doble autoridad de decisión** | Una sola función pura, `resolveWake`, con un enum cerrado de 4 valores. El detector no conoce `TurnOrchestrator`. |
| **Auto-disparo por el TTS** | El detector solo se evalúa en `SLEEPING`. Cuando Fifo habla, no corre. Cero AEC, cero riesgo de "Fifo contesta" por eco. |
| **Falso acepto abre una llamada** | `classifyCallAction` exige la palabra transcrita. La acústica sola no puede producir `ANSWER`/`HANGUP`. |
| **Candidato viejo** | Ventana de 8 s (cubre una pregunta larga), consumo único y reinicio al dormir/silenciar. Probado en `WakeWordCascadeTest`. |
| **Batería / política de Android** | Solo corre dormido, ~80 ms por inferencia y sin red. `SoundTrigger` no está disponible para terceros: el límite real es el foreground service `microphone`, y la salida definitiva es el ESP32-S3. |
| **Deriva del modelo** | `modelId` viaja en cada detección y la regresión se fija con un *golden set* de WAVs. Cambiar modelo sin cambiar el id invalida la medición. |

---

## Consequences

**Positive:**

- El fallo típico en ruido (STT que se come "Fifo") deja de cerrar el turno: la
  consulta transcrita se acepta y se responde.
- La sesión no gana un segundo VAD, un segundo dueño de micrófono ni un segundo
  camino: el detector es un consumidor de tramas y un enum de decisión.
- El comportamiento por defecto no cambia (`NoopWakeWordDetector`): el léxico
  sigue despertando a Fifo hasta que exista un modelo medido.
- La decisión queda aislada en JVM: `UtterancePolicyTest` (tabla de fusión) y
  `WakeWordCascadeTest` (integración con detector falso).

**Negative / Trade-offs:**

- Una etapa más que mantener y un modelo que entrenar, versionar y medir.
- Un falso positivo ya no es solo ruido descartado: abre una ventana de escucha
  silenciosa. Es barato, pero debe medirse (falsos aceptos por hora).
- La calidad final depende de datos: sin grabaciones reales de los usuarios
  (voces mayores, a 1–3 m, con TV), el modelo no mejora al de la tabla pública.
- "Fifo" sigue siendo corto. Si las métricas no alcanzan, la palanca es alargar
  la invocación, no cambiar de motor.

---

## Files Changed

| File | Change |
| :--- | :--- |
| `pipeline/session/WakeWordDetector.kt` | **New** — costura del detector, `WakeWordDetection` y `NoopWakeWordDetector`. |
| `pipeline/session/UtterancePolicy.kt` | **Updated** — `WakeResolution` y `resolveWake()`; la política sigue clasificando, no autorizando. |
| `pipeline/session/VoiceSession.kt` | **Updated** — alimenta el detector solo dormido, guarda/consume el candidato con ventana de 3 s y lo reinicia al dormir/silenciar. |
| `pipeline/session/VoiceSessionPipeline.kt` | **Updated** — construye el detector detrás de la costura (`Noop` por ahora). |
| `test/.../session/UtterancePolicyTest.kt` | **Updated** — 5 pruebas de la tabla de fusión. |
| `test/.../session/WakeWordCascadeTest.kt` | **New** — despertar sin hablar, rescate de consulta, caducidad, eco y no barge-in. |

---

## Verification

- JVM: `testDebugUnitTest`, `testReleaseUnitTest`, `compileReleaseKotlin` en verde.
- `UtterancePolicyTest` 20/20 (15 previas + 5 de la cascada),
  `WakeWordCascadeTest` 5/5, `VoiceSessionTest` 17/17, `TurnOrchestratorTest`
  39/39.
- La cascada se prueba con un detector falso: ninguna prueba depende de un modelo
  descargado.

## Siguiente paso (fuera de este ADR)

1. Grabar un corpus propio: 10–15 hablantes, 20 repeticiones, 4 condiciones
   (silencio, TV, calle, 1–3 m) y negativos difíciles.
2. Entrenar openWakeWord español con ese corpus + augmentación y fijar umbrales
   con la tabla de métricas: FRR a 0/5/10/15 dB, falsos aceptos por hora,
   latencia p50/p95 y mAh/h.
3. Solo si el teléfono no alcanza, mover la detección al ESP32-S3 con
   microWakeWord y dejar el teléfono como consumidor del trigger.
