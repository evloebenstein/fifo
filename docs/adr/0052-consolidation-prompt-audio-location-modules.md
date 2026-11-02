# ADR-0052: Consolidador, prompt, audio y ubicación fuera del accessor

**Status:** Accepted

**Date:** 2026-11-02

**Author:** Architecture Review — Datos, red y pipeline

---

## Context

ADR-0051 dejó las skills en cero llamadas, pero el contador seguía en **91**:

| Capa | Llamadas |
| :--- | ---: |
| `pipeline` | 47 |
| `telephony` | 24 |
| `data/memory` | 11 |
| `network` | 3 |
| `audio` | 3 |
| `location` | 2 |
| `AppGraph` | 1 |

Fuera del pipeline y la telefonía quedaban cinco consumidores pequeños pero
importantes:

1. `RepositorySessionMemoryConsolidator` escribía recuerdos, gustos,
   recordatorios, rutinas, conversaciones, biografía y etiquetas por el accessor.
2. `GroqDialogClient` leía `conversations`, `userProfile` y el contexto compacto
   para armar el system prompt.
3. `PhoneMicRecorder` consultaba `incomingCall` para decidir el modo de captura.
4. `FifoLocationHelper` leía ciudad y dirección del perfil para el fallback GPS.
5. `VoiceSessionPipeline` leía el perfil y los contactos para anunciar llamadas.

La pregunta de esta pasada:

> ¿Cómo escriben y leen estos adaptadores sin conocer el accessor?

---

## Decision

### 1. El consolidador recibe los módulos dueños

`RepositorySessionMemoryConsolidator` construye ahora con
`UserIdentityModule`, `EpisodicMemoryModule`, `RelationalGraphModule` y
`UserSession`. El `userId` se resuelve una vez por lote desde la identidad.

Las inserciones que llegan del backend (`memoriesAdded`, `tasteStoriesAdded`,
`remindersAdded`) dejaron de vivir en el accessor y pasaron al módulo de memoria
como `addMemoryDirect()`, `addTasteStoryDirect()` y `addReminderDirect()`.
Mantienen la deduplicación por título (y por título + hora en recordatorios) y
**no republican**, para no crear un bucle backend → módulo → backend.

### 2. Las conversaciones también se publican desde el módulo

`MemoryRemotePort` gana `postConversation()`.
`EpisodicMemoryModule.addConversation()` guarda local y publica; así el
consolidador no necesita el accessor ni `FifoBackendClient` para esa escritura.

### 3. El prompt recibe identidad y memoria por costura

`CloudApiClient` expone `identity` y `memory`; `GroqDialogClient` los recibe como
providers. Si faltan (pruebas o cliente sin grafo), compone un contexto mínimo
con `UserProfileData()` vacío en vez de fallar. El contexto compacto se sigue
calculando en `EpisodicMemoryModule.buildCompactContextWindow()`.

### 4. El audio de llamada entra por el coordinador

`PhoneMicRecorder` recibe `CallStateCoordinator?`. La decisión de modo llamada y
el enrutamiento Bluetooth se basan en `callState.incomingCall`, no en el
accessor. `AndroidAudioHardwareInboundAdapter` y `VoicePipelineManager` pasan
`graph.telephony`.

### 5. La ubicación recibe el perfil

`FifoLocationHelper` ya no lee el accessor: `getCurrentLocation()`,
`getFreshLocation()`, `getSpokenRouteGuidance()` y `searchNearbyPlaces()` reciben
`UserProfileData`. `AndroidLocationAdapter` recibe un `profileProvider` y
`FifoSkillRegistry` lo construye con `graph.identity.userProfile`.
`CurrentLocationSkill` y `WebSearchSkill` consumen el adapter, no el helper
estático.

### 6. `VoiceSessionPipeline` recibe el grafo

El pipeline del camino nuevo recibe `AppGraph` y cablea con él el contexto de
llamante, la memoria durable, el audio entrante y la resolución de contactos
locales. Sigue siendo el único lugar que construye esas piezas; el facade legacy
no construye ninguna.

### 7. Contador

Tras esta pasada el accessor baja de **91 a 66** llamadas:

| Capa | Llamadas |
| :--- | ---: |
| `pipeline` (`VoicePipelineManager`) | 41 |
| `telephony` | 24 |
| `AppGraph` (mensaje de error) | 1 |

`data/memory`, `network`, `audio` y `location` quedan en cero.

---

## Consequences

### Lo que mejora

- **El consolidador es testeable sin composition root.** Recibe módulos y un
  `userIdProvider`; una prueba puede usar los `InMemory*`.
- **El prompt no depende de un objeto global.** El cliente de red funciona con
  módulos inyectados o con un contexto mínimo.
- **La captura de llamada tiene una sola fuente.** `PhoneMicRecorder` consulta
  el coordinador que ya publica la llamada.
- **La ubicación no conoce el accessor.** El perfil entra por parámetro y las
  skills usan `LocationAdapter`.

### Lo que cuesta

- **Más parámetros de composición.** `CloudApiClient`, `VoiceSessionPipeline` y
  el adaptador de ubicación crecen en dependencias explícitas.
- **Dos APIs de memoria directa.** `addMemoryDirect()` y `addMemory()` publican
  distinto a propósito: la primera viene del backend y no debe rebotar.
- **El accessor sigue vivo.** Quedan pipeline y telefonía; no se borra hasta
  llegar a cero.

### Lo que **no** resuelve

- **El facade legacy y la telefonía siguen usando el accessor.** Es la última
  capa antes de borrarlo.
- **La consolidación sigue llamando a `FifoBackendClient`.** Es el adaptador de
  infraestructura; moverlo a un puerto es una decisión aparte.
- **El borrado remoto no existe.** Esta pasada no toca backend.

---

## Migration plan

| Paso | Alcance | Estado |
| :--- | :--- | :--- |
| 1 | Consolidador y memoria directa con módulos | Hecho |
| 2 | `postConversation` en `MemoryRemotePort` | Hecho |
| 3 | Prompt con identidad/memoria inyectadas | Hecho |
| 4 | Audio de llamada por `CallStateCoordinator` | Hecho |
| 5 | Ubicación con perfil inyectado | Hecho |
| 6 | `VoiceSessionPipeline` recibe `AppGraph` | Hecho |
| 7 | Telefonía fuera del accessor | Pendiente |
| 8 | `VoicePipelineManager` fuera del accessor | Pendiente |
| 9 | Borrar el accessor a cero | Pendiente |

Contador al cerrar esta pasada: **66 llamadas** (antes 91).

---

## Verification

| Prueba | Qué fija |
| :--- | :--- |
| `ContentPublicationTest` | conversaciones se guardan y publican una vez; inserciones directas se deduplican y no republican |
| `DomainModulesTest` | los módulos siguen sin red con `Noop` |
| `VoiceSessionMemoryTest` | el journal sigue escribiéndose antes de hablar |
| `FifoStateHoldersTest` | la UI sigue proyectando módulos |

Suite completa: **459 tests debug / 459 release, 0 fallos, 6 omitidos**, más
`compileReleaseKotlin`.

---

## Invariants

1. **El consolidador recibe módulos.** No lee ni escribe el accessor.
2. **Lo que viene del backend no se republica.** Las inserciones `*Direct`
   actualizan estado y almacenamiento, nunca la red.
3. **La publicación sigue siendo del módulo.** Conversaciones, recuerdos,
   gustos, historias, recordatorios y rutinas salen por `MemoryRemotePort`.
4. **La captura consulta al coordinador de llamada.** `PhoneMicRecorder` no
   conoce el accessor.
5. **La ubicación recibe el perfil.** El helper no lee estado global; las skills
   usan `LocationAdapter`.
6. **El pipeline nuevo cablea, el facade no.** `VoiceSessionPipeline` es el
   único que construye consolidador, audio entrante y contexto de llamante.
