# ADR-0054: Borrado del accessor tras migrar el pipeline

**Status:** Accepted

**Date:** 2026-11-02

**Author:** Architecture Review — Cierre de la migración strangler

---

## Context

ADR-0049 introdujo `AppGraph` como dueño único del estado y dejó
`FifoDataRepository` como un accessor `@Deprecated` que solo delegaba. Las
pasadas de ADR-0050 a ADR-0053 vaciaron la UI, las skills, la memoria, la red,
la ubicación, el audio y la telefonía. Al empezar esta pasada quedaban **42
llamadas**:

| Archivo | Llamadas | Qué hacía |
| :--- | ---: | :--- |
| `VoicePipelineManager` | 41 | escucha continua, llamada, perfil, dispositivo, consolidación y memoria |
| `AppGraph` | 1 | mensaje de error de `requireInstance()` |

El pipeline era el último consumidor. La pregunta:

> ¿Puede el facade legacy leer y escribir los mismos módulos del grafo que ya
> usa el camino nuevo, sin cambiar el comportamiento y sin dejar una API
> paralela que invite a volver?

---

## Decision

### 1. El pipeline habla con los módulos

Cada llamada se sustituye por el módulo que ya poseía el dato:

| Antes | Ahora |
| :--- | :--- |
| `continuousListeningHandler` | `appGraph.listening.handler` |
| `mirrorContinuousListening(active)` | `appGraph.listening.mirror(active)` |
| `isContinuousListening.value` | `appGraph.listening.active.value` |
| `setContinuousListening(false)` | `appGraph.listening.request(false)` |
| `incomingCall.value` | `appGraph.telephony.incomingCall.value` |
| `userProfile.value` | `appGraph.identity.userProfile.value` |
| `deviceLocation` | `appGraph.device.location` |
| `triggerDeviceBeep(false)` | `appGraph.device.triggerBeep(false)` |
| `updateDeviceConnectionStatus(...)` | `appGraph.device.updateConnectionStatus(...)` |
| `addConversationFragment(fragment)` | `appGraph.memory.addConversationFragment(userId, fragment)` |
| `addConversation(pastConv)` | `appGraph.memory.addConversation(userId, pastConv)` |
| `addMemory(...)` | `appGraph.memory.addMemory(userId, ...)` |
| `addTaste(...)` | `appGraph.memory.addTaste(userId, ...)` |
| `updateContactsFromSessionMentions(...)` | `appGraph.relationalGraph.ingestSessionMentions(...)` |
| `updateBio(...)` / `updateInterestTags(...)` | `appGraph.session.updateBio(...)` / `updateInterestTags(...)` |
| `addMemoryDirect(mem)` | `appGraph.memory.addMemoryDirect(userId, mem)` |
| `addTasteStoryDirect(story)` | `appGraph.memory.addTasteStoryDirect(userId, story)` |
| `addReminderDirect(rem)` | `appGraph.memory.addReminderDirect(userId, rem)` |

El `userId` que antes se resolvía dentro del accessor se obtiene una vez en
`consolidateSessionIntoMemory()` desde `appGraph.identity.userProfile.value.id`
y se pasa a cada módulo.

### 2. La consolidación conserva sus dos escrituras

El facade legacy no consolida con `MemoryCoordinator` (eso es del camino
`SESSION`, ADR-0011), pero sí guarda el fragmento y la conversación. Las dos
siguen ocurriendo:

- `addConversationFragment()` escribe local.
- `addConversation()` escribe local y publica con `MemoryRemotePort.postConversation()`.
- `addMemory()` y `addTaste()` escriben local y publican con
  `postMemory()`/`addTaste()`.
- `addMemoryDirect()`, `addTasteStoryDirect()` y `addReminderDirect()` solo
  escriben local porque vienen del backend (ADR-0052).

Ninguna publicación se duplica: el pipeline dejó de llamar a
`FifoBackendClient` para estos datos en pasadas anteriores.

### 3. Se borra el accessor

- Se elimina `data/FifoDataRepository.kt` con `git rm`.
- `FifoApiClient` pierde el import y la dependencia.
- `AppGraph.requireInstance()` deja de mencionarlo:
  `error("AppGraph no instalado: llama a FifoApplication")`.
- Los comentarios de `AppGraph`, `UserSession`, `DeviceLocationPolicy`,
  `ActivitySuggestionPolicy`, `FifoBackendClient`, `ContactsSkill` y
  `FifoSkillRegistry` se actualizan para describir el estado actual; las
  referencias en ADR-0002 a ADR-0037 se conservan como historia.

### 4. Contador final

**42 → 0** llamadas y **cero archivos Kotlin** que mencionen
`FifoDataRepository` como API ejecutable. La migración strangler termina: no
queda una segunda puerta al estado del usuario.

---

## Consequences

### Lo que mejora

- **Una sola puerta al estado.** Cualquier consumidor nuevo encuentra
  `AppGraph` y sus módulos; no hay un objeto global que parezca la API
  canónica.
- **El pipeline legacy y el camino nuevo comparten dueño.** Escucha continua,
  llamada, perfil y dispositivo son los mismos `StateFlow` en `LEGACY` y en
  `SESSION`.
- **Menos superficie que mantener.** El accessor era una copia de firmas que
  había que actualizar en cada cambio de módulo.

### Lo que cuesta

- **El facade conoce más nombres.** `VoicePipelineManager` pasa de un objeto a
  siete módulos (`listening`, `telephony`, `identity`, `device`, `memory`,
  `relationalGraph`, `session`), pero cada uno se usa en el bloque que le
  corresponde.
- **El `userId` viaja explícito.** Las escrituras de memoria reciben el id en
  vez de resolverlo por dentro; es el mismo valor y hace visible la dependencia.

### Lo que **no** cambia

- **El comportamiento.** La suite completa sigue verde y los flujos de
  timbrado, contestado, colgado, escucha continua y consolidación hacen las
  mismas llamadas locales y remotas.
- **El flag.** `VOICE_SESSION_ENABLED` sigue apagado en debug y encendido en
  release; `VoicePipelineModeSelector` sigue decidiendo `LEGACY` o `SESSION`.
- **El legacy sigue existiendo.** Se borra en la release siguiente, según
  `AGENTS.md`; este ADR solo elimina su acceso al estado por el accessor.

---

## Migration plan

| Paso | Alcance | Estado |
| :--- | :--- | :--- |
| 1 | `VoicePipelineManager` usa `graph.listening` | Hecho |
| 2 | `VoicePipelineManager` usa `graph.telephony` | Hecho |
| 3 | `VoicePipelineManager` usa `graph.identity`/`graph.device` | Hecho |
| 4 | Consolidación usa `memory`/`relationalGraph`/`session` | Hecho |
| 5 | Borrar `FifoDataRepository.kt` | Hecho |
| 6 | Limpiar import de `FifoApiClient` y mensaje de `AppGraph` | Hecho |
| 7 | Actualizar ADRs, glosario, mapa y READMEs | Hecho |

Contador al cerrar la migración: **0 llamadas**.

---

## Verification

| Prueba | Qué fija |
| :--- | :--- |
| `FifoStateHoldersTest` | la UI lee los mismos `StateFlow` del grafo tras el borrado |
| `DomainModulesTest` | memoria, identidad y dispositivos conservan sus contratos |
| `ContentPublicationTest` | la publicación local/remota sigue en el módulo |
| `CallStateCoordinatorTest` | la llamada visible sigue teniendo un solo dueño |
| `MemoryCoordinatorTest` | la memoria durable del camino `SESSION` no depende del accessor |

Suite completa: **459 tests debug / 459 release, 0 fallos, 6 omitidos**, más
`compileReleaseKotlin`.

---

## Invariants

1. **No se reintroduce un accessor global.** El estado se pide a `AppGraph` y
   a sus módulos.
2. **El facade delega, no duplica.** `VoicePipelineManager` lee y escribe los
   módulos; no crea un segundo VAD, TTS, coordinador ni cola.
3. **La publicación es del módulo.** El pipeline no llama a
   `FifoBackendClient` para memoria, gustos, historias o conversaciones.
4. **Lo que llega del backend no se republica.** `*Direct()` solo escribe
   local.
5. **El borrado no cambia el comportamiento legacy.** Cualquier cambio futuro
   en llamadas exige una prueba de timbrado, contestado, colgado y VoIP.
