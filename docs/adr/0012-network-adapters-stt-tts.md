# ADR-0012: Adaptadores de red para STT y TTS

**Status:** Accepted

**Date:** 2026-10-22

**Author:** Architecture Review — Cloud client decomposition

---

## Context

`CloudApiClient` había acumulado cuatro responsabilidades distintas: transcripción
Whisper, diálogo con Groq, síntesis de voz con OpenAI y armado de contexto/memoria.
Además creaba su propio `OkHttpClient` y contenía un resampler PCM privado.

Ese tamaño impedía probar las piezas puras en JVM y obligaba a que cualquier cambio
en TTS o STT tocara la clase que también orquesta herramientas y memoria. El objetivo
es reducir el monolito sin cambiar el contrato que consumen `VoiceSession` y el
camino legacy.

---

## Decision

### 1. `CloudApiClient` queda como fachada

Mantiene el contrato histórico: `transcribe()`, `textToSpeech()`, `chat()`,
`chatStreaming()`, extracción de memoria e historial. No se movió el diálogo porque
comparte estado con el catálogo de skills y la construcción de contexto.

### 2. Adaptadores especializados

| Componente | Responsabilidad |
|---|---|
| `GroqSpeechToTextClient` | Multipart a Groq/OpenAI Whisper y fallback explícito de proveedor. |
| `OpenAiTextToSpeechClient` | Request TTS, lectura de PCM y conversión a 16 kHz. |
| `PcmResampler` | Resampling puro 24 kHz → 16 kHz. |
| `WhisperHallucinationFilter` | Filtro puro de alucinaciones de Whisper. |
| `FifoHttpClient` | Pool OkHttp compartido y `Call.executeSuspend()`. |

`CloudApiClient.isWhisperHallucination()` se conserva como API de compatibilidad y
delega en `WhisperHallucinationFilter`.

### 3. Dependencias observadas, no copiadas

Los adaptadores reciben lambdas para leer las claves actuales (`groqApiKey`,
`openAiApiKey`). Así el usuario puede cambiar una clave en la UI sin reconstruir el
cliente ni el pipeline.

### 4. Sin fallback recursivo al mismo modelo

El antiguo “failover” de Groq apuntaba al mismo modelo que el principal. Ante un
error de red podía recursar indefinidamente. Se eliminó ese camino; el reintento
explícito de HTTP 429 se mantiene dentro de `chatWithGroq`.

---

## Files Changed

| File | Change |
|---|---|
| `network/FifoHttpClient.kt` | **New** — cliente compartido y extensión suspendible |
| `network/GroqSpeechToTextClient.kt` | **New** — adaptador STT |
| `network/OpenAiTextToSpeechClient.kt` | **New** — adaptador TTS |
| `network/PcmResampler.kt` | **New** — resampling puro |
| `network/WhisperHallucinationFilter.kt` | **New** — filtro puro |
| `network/CloudApiClient.kt` | **Updated** — fachada, delegación y eliminación de HTTP/resampling privados |
| `test/.../PcmResamplerTest.kt` | **New** — forma de onda y audio vacío |

---

## What Did NOT Change

- `VoiceSession` y `VoiceSessionPipeline` conservan su contrato y composición.
- El camino legacy sigue usando `CloudApiClient` como fachada.
- El control de llamadas, el eco, la memoria durable y la reparación no se tocaron.
- El flag `VOICE_SESSION_ENABLED` sigue igual.
- No se agregó un segundo cliente HTTP ni un segundo dueño de TTS.

---

## Consequences

**Positive:**

- STT, TTS, HTTP y resampling se pueden testear sin Android ni el pipeline completo.
- Un cambio de proveedor de TTS ya no obliga a navegar la lógica de herramientas.
- El pool de conexiones es único para todas las llamadas de red.
- El filtro de alucinaciones tiene una costura pura y una API de compatibilidad.

**Negative / Trade-offs:**

- `CloudApiClient` conserva la extracción de memoria de sesión; el diálogo se
  extrajo después en ADR-0013.
- La delegación agrega una indirección mínima en `transcribe()` y `textToSpeech()`.
- `GROQ_WHISPER_MODEL` vive ahora en el adaptador STT; el facade ya no expone ese
  detalle de implementación.
