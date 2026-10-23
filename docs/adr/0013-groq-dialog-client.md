# ADR-0013: Motor de diálogo Groq separado de la fachada

**Status:** Accepted

**Date:** 2026-10-22

**Author:** Architecture Review — Cloud client decomposition

---

## Context

Después de extraer STT y TTS (ADR-0012), `CloudApiClient` seguía mezclando la
fachada pública con el motor conversacional: historial, prompt de sistema,
contexto temporal/geográfico, orquestación de herramientas, streaming SSE,
prueba de conexión y extracción de memoria.

Eso hacía que la fachada fuera difícil de leer y que cualquier cambio en el
diálogo tocara la misma clase que expone el contrato a `VoiceSession` y al
camino legacy.

---

## Decision

### 1. `GroqDialogClient` es el dueño del diálogo

El nuevo adaptador interno posee:

- historial de conversación (máximo 8 mensajes);
- prompt de sistema y contexto enriquecido;
- `chat()` y `chatStreaming()`;
- orquestación de `tools` y follow-up de Groq;
- reanudación natural tras un barge-in;
- prueba de conexión de la clave Groq.

Recibe lambdas para leer la clave, el `ConversationContextProvider` y el
`Context` de Android. Así la fachada puede cambiar esos valores sin reconstruir
el motor.

### 2. `CloudApiClient` queda como fachada delgada

Mantiene el contrato público y delega:

| Responsabilidad | Implementación |
|---|---|
| STT | `GroqSpeechToTextClient` |
| Diálogo, historial y streaming | `GroqDialogClient` |
| TTS | `OpenAiTextToSpeechClient` |
| Extracción de memoria | `CloudApiClient` (contrato de consolidación) |

La extracción de memoria se mantiene en la fachada porque
`RepositorySessionMemoryConsolidator` la consume como contrato de datos, no como
parte de la conversación en tiempo real.

### 3. Sin cambios en el comportamiento observable

Se conservan los mismos prompts, límites de historial, reintento de HTTP 429,
fallbacks hablados y frases de error. La extracción es mecánica: no se rediseñó
la conversación.

---

## Files Changed

| File | Change |
|---|---|
| `network/GroqDialogClient.kt` | **New** — historial, contexto, tools, streaming y reanudación |
| `network/CloudApiClient.kt` | **Updated** — fachada delgada; solo STT/TTS/memoria y delegación |

---

## What Did NOT Change

- `VoiceSession`, `VoiceSessionPipeline` y `VoicePipelineManager` conservan su comportamiento.
- `CaptureArbiter`, telefonía, eco, memoria durable y reparación no se tocaron.
- El flag `VOICE_SESSION_ENABLED` sigue igual.
- No se agregó un segundo cliente HTTP ni un segundo dueño del historial.

---

## Consequences

**Positive:**

- La fachada se puede leer como un mapa de responsabilidades.
- El diálogo se puede cambiar sin tocar el contrato de sesión.
- El historial y el streaming tienen un solo dueño explícito.
- La extracción de memoria queda aislada como contrato de consolidación.

**Negative / Trade-offs:**

- Hay una indirección adicional entre `CloudApiClient` y el motor de diálogo.
- Las pruebas de red del diálogo siguen dependiendo de OkHttp; no se agregó un
  transporte falso en esta iteración.
