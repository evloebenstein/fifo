# ADR-0042: Una sola política léxica para invocación, silencio, eco y llamada

**Status:** Accepted

**Date:** 2026-10-26

**Author:** Architecture Review — Interpretación de la locución

---

## Context

Fifo tenía **dos implementaciones de la misma política de lenguaje**, una por
camino:

- `TurnOrchestrator` (camino `SESSION`, el de release) decidía invocación,
  extracción de la consulta, silencio, sueño, eco, pregunta y control de llamada.
- `VoicePipelineManager` (camino `LEGACY`, el de debug y el fallback sin STT de
  nube) tenía copias propias de las mismas reglas, con listas y regex distintas.

Las copias habían derivado:

| Regla | `TurnOrchestrator` | `VoicePipelineManager` |
| :--- | :--- | :--- |
| Wake word | Excluye aliases riesgosos (`tito`, `kiko`, `vivo`, `pico`, `hijo`, `tipo`, `dijo`) | Los aceptaba: una conversación ajena podía despertar a Fifo |
| Silencio | Acepta `espera`, `alto`, `un momento`, `para un momento` | No los aceptaba |
| Silencio | No incluía `corta ya`, `cortala`, `fifo calma`, `no te estoy hablando` | Sí los incluía |
| `apágate` | Orden de sueño | Orden de silencio momentáneo |
| Eco | Colapsa espacios al normalizar | No colapsaba espacios |
| Control de llamada | Exige `Fifo` siempre | Durante el timbrado aceptaba `contesta` o `cuelga` a secas |

La última fila no es solo duplicación: **contradice un invariante documentado**
en `AGENTS.md` ("contestar/colgar por voz exige la palabra clave completa"). El
camino de release cumplía la regla; el de debug no. Eso hace que el modo debug
no sea representativo de la release y que un arreglo en una copia no llegue a la
otra.

Además, las reglas son la parte más delicada del producto (falsos despertares,
cortar una locución por una preposición, contestar una llamada por eco) y vivían
enterradas en dos clases de 500–2.000 líneas, sin una tabla de verdad única.

---

## Decision

Extraer **`UtterancePolicy`** (`pipeline/session/UtterancePolicy.kt`) como la
única autoridad léxica de Fifo, pura y sin Android:

```kotlin
object UtterancePolicy {
    fun normalize(text: String): String
    fun isWakeWord(text: String): Boolean
    fun extractQuery(text: String): String
    fun isGreetingOnly(text: String): Boolean
    fun isSilenceCommand(text: String, whileSpeaking: Boolean = false): Boolean
    fun isSleepCommand(text: String): Boolean
    fun isEcho(spokenSentence: String, text: String, isSpeakingNow: Boolean, awaitingAnswer: Boolean): Boolean
    fun isAskingQuestion(text: String): Boolean
    fun classifyCallAction(text: String, isRinging: Boolean): CallAction?
}
```

`CallAction` (`ANSWER`, `HANGUP`, `CALLER_STATUS`) se mueve junto a la política,
en el mismo paquete, para que el contrato y su clasificador no se separen.

### Reglas de la unificación

1. **El estado conversacional entra como parámetro.** `isEcho` recibe la frase
   hablada y si Fifo espera respuesta. Así la política sigue siendo pura y el
   orquestador y el facade solo aportan su estado (`lastSpokenSentence`,
   `expectFollowUp`, `isWaitingForBargeInResolution`).
2. **Se adopta la versión estricta de la invocación.** Los aliases riesgosos
   dejan de despertar a Fifo en ambos caminos. Es preferible que el usuario
   repita "Fifo" a que Fifo responda a una conversación ajena.
3. **Las listas de silencio se unen.** Se suman a la base del orquestador las
   frases inequívocas que solo tenía el legacy (`corta ya`, `cortala`,
   `no te estoy hablando`, `fifo calma`). La exención de instrucciones reales
   ("busca el botón de silencio", "quiero que me guíes") se conserva.
4. **`apágate` es dormir, no callarse un momento.** Sale de las frases de
   silencio. El camino de release ya lo trataba así; el legacy ahora también
   habla la despedida y apaga la escucha continua.
5. **El control de llamada exige `Fifo` en ambos caminos.** `contesta`,
   `cuelga` o `¿quién llama?` aislados no son órdenes. El escudo de eco sigue
   evaluándose antes en `VoiceSession` y en el facade.
6. **Cada camino conserva su API pública.** `TurnOrchestrator.isWakeWord()`,
   `isSilenceCommand()`, etc. y el `isSilenceCommand()` del facade delegan; no
   se obliga a los llamadores a conocer la política.

### Lo que la política no hace

No redacta respuestas, no locuta, no conoce TTS ni el pipeline. Solo clasifica.
La respuesta hablada sigue siendo responsabilidad del LLM (ADR-0039 y ADR-0040).

---

## Consequences

**Positive:**

- Una sola tabla de verdad para wake word, silencio, sueño, eco, pregunta y
  llamada: el modo debug se comporta como la release.
- `TurnOrchestrator` pasa de ~530 a ~350 líneas y `VoicePipelineManager` pierde
  ~200 líneas de lógica duplicada; ambos quedan como orquestadores, no como
  diccionarios.
- `UtterancePolicyTest` (15 tests) fija las reglas en JVM. `TurnOrchestratorTest`
  y `VoiceSessionTest` siguen cubriendo la integración.
- La regla de llamada de `AGENTS.md` se cumple también en el camino legacy.

**Negative / Trade-offs:**

- El wake word es más estricto que antes en debug: una transcripción que suena
  como "tito" ya no despierta a Fifo. Se acepta a cambio de eliminar falsos
  despertares.
- El legacy cambia en detalles observables (frases de silencio adicionales,
  `apágate` como sueño, `contesta` sin `Fifo` inválido). Es el camino de debug y
  la dirección del cambio es la del camino de release.
- La política es léxica y específica del español: las listas siguen siendo
  mantenimiento manual, pero ahora en un solo archivo.

---

## Files Changed

| File | Change |
| :--- | :--- |
| `pipeline/session/UtterancePolicy.kt` | **New** — política léxica pura y `CallAction`. |
| `pipeline/session/TurnOrchestrator.kt` | **Updated** — delega las reglas y conserva solo el estado conversacional; `CallAction` sale del archivo. |
| `pipeline/VoicePipelineManager.kt` | **Updated** — el legacy delega invocación, consulta, silencio, sueño, eco, pregunta y llamada. |
| `test/.../session/UtterancePolicyTest.kt` | **New** — tabla de verdad de las ocho reglas. |

---

## Verification

- JVM: `testDebugUnitTest`, `testReleaseUnitTest`, `compileReleaseKotlin` en
  verde.
- `UtterancePolicyTest` (15 tests):
  - invocación con variantes fonéticas y rechazo de aliases riesgosos;
  - extracción de la consulta en cualquier posición y saludo puro;
  - silencio explícito, preposición `para`, instrucciones reales y palabras
    cortas solo durante la locución;
  - sueño, incluido `apágate`;
  - eco durante la locución, repetición completa fuera de ella y excepción de
    follow-up;
  - preguntas sin signo de interrogación;
  - control de llamada estricto: `Fifo contesta`, `Fifo cuelga` y estado del
    llamante; `contesta` aislado inválido.
- `TurnOrchestratorTest` (39 tests) y `VoiceSessionTest` (17 tests) siguen en
  verde sin cambios: la delegación no altera el comportamiento del camino
  `SESSION`.
- Emulador (debug, camino `LEGACY`): "Fifo qué hora es" se transcribió, extrajo
  la consulta y respondió por el fast-path de hora; "Fifo cállate" se clasificó
  como silencio y cortó la locución.
