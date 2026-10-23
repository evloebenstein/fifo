# ADR-0014: Extractor de memoria de sesión y modelos de dominio

**Status:** Accepted

**Date:** 2026-10-22

**Author:** Architecture Review — Cloud client decomposition

---

## Context

Después de ADR-0012 y ADR-0013, `CloudApiClient` ya delegaba STT, diálogo y TTS.
Sin embargo, todavía contenía el prompt de consolidación, la llamada HTTP a Groq,
el parseo del JSON y los modelos `SessionMemoryConsolidation` /
`MentionedPersonExtraction`.

Eso tenía dos problemas:

1. La fachada de red seguía siendo el dueño de una tarea de memoria.
2. La capa `data` importaba modelos alojados en `network`, invirtiendo la
   dirección natural de dependencias.

---

## Decision

### 1. `GroqSessionMemoryExtractor` es el dueño de la extracción

El adaptador recibe un proveedor de clave y un `OkHttpClient`, construye el
prompt, ejecuta la llamada y traduce el resultado a dominio.

Conserva la semántica de la cola durable:

- `null` significa “no había nada que recordar”.
- una excepción significa fallo recuperable y provoca reintento cuando
  `failOnError = true`.
- si no hay clave de Groq, devuelve `null` sin lanzar.

### 2. `SessionMemoryParser` es puro y testeable

El parseo JSON se movió a un objeto sin Android ni red. El extractor solo se
encarga del transporte y de la política de error.

### 3. Los modelos viven en `data/memory`

`SessionMemoryConsolidation` y `MentionedPersonExtraction` se movieron a
`data/memory/SessionMemoryModels.kt`. Así `CloudApiClient` y
`RepositorySessionMemoryConsolidator` dependen del dominio, no al revés.

### 4. `CloudApiClient` queda como fachada de composición

Mantiene exactamente el contrato público y delega la extracción al nuevo
adaptador. `RepositorySessionMemoryConsolidator` no cambia su comportamiento.

---

## Files Changed

| File | Change |
|---|---|
| `data/memory/SessionMemoryModels.kt` | **New** — modelos de consolidación y personas |
| `network/GroqSessionMemoryExtractor.kt` | **New** — prompt, HTTP y política de error |
| `network/SessionMemoryParser.kt` | **New** — parser JSON puro |
| `network/CloudApiClient.kt` | **Updated** — delegación de memoria y fachada más delgada |
| `data/memory/RepositorySessionMemoryConsolidator.kt` | **Updated** — importa modelos de dominio |
| `network/FifoBackendClient.kt` | **Updated** — importa el modelo de dominio |
| `data/FifoDataRepository.kt` | **Updated** — referencia el modelo de dominio |
| `test/.../SessionMemoryParserTest.kt` | **New** — JSON completo, nulls y valores por defecto |

---

## What Did NOT Change

- La cola durable, el backoff y la política de reparación no se tocaron.
- `VoiceSession` sigue viendo solo `SessionMemory`.
- El camino legacy sigue usando `CloudApiClient` como fachada.
- El flag `VOICE_SESSION_ENABLED` no cambia.
- No se agregó una segunda llamada de consolidación ni un segundo dueño de la cola.

---

## Consequences

**Positive:**

- `CloudApiClient` queda como composición de adaptadores, sin lógica de parseo.
- La capa de datos deja de depender de `network` para sus modelos.
- El formato JSON del consolidador se prueba en JVM sin red.
- La política de “reintentar vs. no había nada” queda encapsulada.

**Negative / Trade-offs:**

- El prompt de consolidación vive ahora en `network`, no en `data`; es una
  decisión deliberada porque describe el contrato del proveedor LLM.
- Agrega dos archivos pequeños y una indirección de delegación.
