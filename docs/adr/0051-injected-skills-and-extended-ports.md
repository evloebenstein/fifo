# ADR-0051: Skills inyectadas y puertos extendidos de publicación

**Status:** Accepted

**Date:** 2026-11-02

**Author:** Architecture Review — Datos y skills

---

## Context

ADR-0050 dejó la UI en cero llamadas a `FifoDataRepository`, pero las skills
seguían siendo el mayor consumidor del accessor: **46 llamadas** repartidas en
siete clases. Cada skill leía `userProfile`, `relationalContacts`, `reminders`,
`deviceLocation` o `lastActiveCall` de un objeto global, y varias mutaban el
estado llamando al accessor aunque el dueño ya vivía en un módulo.

El contador al cerrar ADR-0050 era **137**:

| Capa | Llamadas |
| :--- | ---: |
| `pipeline` | 47 |
| `skills` | 46 |
| `telephony` | 24 |
| `data` | 12 |
| `network` | 3 |
| `audio` | 3 |
| `location` | 2 |

Además, la publicación en los módulos cubría solo recuerdos, inscripciones y
actividades. Gustos, historias, recordatorios, rutinas y publicaciones sociales
seguían guardando local y llamando a `FifoBackendClient` desde el accessor. Las
skills de llamadas también hablaban con la red directamente
(`identifyContact`, `logCallEvent`).

La pregunta de esta pasada:

> ¿Cómo usa una skill el estado sin conocer el accessor ni la red?

---

## Decision

### 1. Las skills reciben módulos, no un objeto global

`FifoSkillRegistry` recibe el `AppGraph` y construye cada skill con **solo los
módulos que usa**:

| Skill | Dependencias inyectadas |
| :--- | :--- |
| `ContactsSkill` | `DeviceContactsSync`, `RelationalGraphModule`, `UserIdentityModule` |
| `FindFifoDeviceSkill` | `DeviceState` |
| `PhoneCallSkill` | `CallStateCoordinator`, `UserIdentityModule`, `RelationalGraphModule`, `CallRemotePort` |
| `PhoneCommunicationSkill` | `UserIdentityModule`, `RelationalGraphModule` |
| `PhoneDeviceControlSkill` | `DeviceState` |
| `ProfileDatabaseSkill` | `UserSession`, `UserIdentityModule`, `EpisodicMemoryModule`, `CommunityModule` |
| `ReminderSkill` | `UserIdentityModule`, `EpisodicMemoryModule`, `RelationalGraphModule` |

`ProfileDatabaseSkill` y `ReminderSkill` ya no reciben `Context`: no tocan
Android, solo módulos. `VoicePipelineManager` pasa el grafo explícitamente al
registro; `FifoSkillRegistry` tiene `AppGraph.requireInstance()` como valor por
defecto para no cambiar el resto de llamadas.

Regla: **una skill no lee ni escribe el accessor**. Si necesita estado, el
registro le entrega el módulo dueño.

### 2. La publicación se extiende en los módulos

`MemoryRemotePort` y `CommunityRemotePort` crecen para cubrir las mutaciones que
antes hacía el accessor:

| Puerto | Métodos |
| :--- | :--- |
| `MemoryRemotePort` | `postMemory`, `addTaste`, `removeTaste`, `postTasteStory`, `postReminder`, `completeReminder`, `deleteReminder`, `postDailyRoutine` |
| `CommunityRemotePort` | `toggleJoinActivity`, `createCommunityActivity`, `postSocialPost` |

`EpisodicMemoryModule` publica gustos, historias, recordatorios y rutinas;
`CommunityModule.addSocialPost()` publica solo si hay `userId`, para no enviar
contenido huérfano. `FifoDataRepository` queda como delegación pura en esos
métodos: ya no llama a `FifoBackendClient` para publicar.

### 3. La telefonía tiene su propia costura de red

`CallRemotePort` (`telephony/CallRemotePort.kt`) expone:

- `identifyContact(userId, callerName, phoneNumber)`;
- `logCallEvent(userId, action, callerName, phoneNumber, durationSeconds, notes)`.

Producción usa `BackendCallRemote`, que absorbe los fallos con log; las pruebas
pueden usar `NoopCallRemote` o un grabador. `PhoneCallSkill` sigue pidiendo la
acción a `CallStateCoordinator` (`answer`/`hangup`); la telefonía real continúa
en `FifoCallManager`/`AndroidTelephonyPort`.

### 4. El router pide la escucha, no la escribe en el accessor

`VoiceIntentRouter` recibe `ContinuousListeningState?` por constructor. En
producción el registro le pasa `graph.listening`; en pruebas JVM el valor es
`null` y los comandos de escucha solo devuelven el `SkillResult`, sin tocar
estado global. Se elimina así el último uso del accessor desde `skills/`.

### 5. El accessor se reduce a delegación

Tras esta pasada, `FifoDataRepository` baja de 137 a **91 llamadas** y ninguna
vive en `skills/`. Lo que queda es pipeline (47), telefonía (24),
`data/memory` (11), red (3), audio (3) y ubicación (2), además del propio grafo y
el helper de ubicación. El accessor no se borra hasta llegar a cero.

---

## Consequences

### Lo que mejora

- **Las skills son testeables sin composition root.** Reciben módulos y puertos;
  una prueba puede inyectar `InMemory*` o un grabador sin instalar `AppGraph`.
- **Un solo camino de publicación.** Gustos, historias, recordatorios, rutinas y
  publicaciones sociales se guardan local y publican en el módulo, igual que los
  recuerdos.
- **La red está detrás de costuras.** `CallRemotePort` evita que una skill de
  llamadas dependa de `FifoBackendClient`.
- **El router no toca estado global.** La escucha se pide a la proyección del
  grafo o no se toca en pruebas.

### Lo que cuesta

- **Más parámetros en el registro.** `FifoSkillRegistry` construye cada skill con
  named arguments; es explícito, pero crece con cada dependencia nueva.
- **Dos APIs durante la migración.** El accessor sigue vivo con 91 llamadas; sus
  advertencias de deprecación son esperadas.
- **`detachedListening` queda sin uso desde skills.** Se conserva solo como
  proyección de pruebas del accessor hasta que el accessor desaparezca.

### Lo que **no** resuelve

- **El pipeline, la telefonía y la memoria durable siguen usando el accessor.**
  Es la siguiente capa (paso 8 de ADR-0050).
- **El borrado remoto sigue sin existir.** Esta pasada no toca backend.
- **No se autoriza telefonía desde la identidad de locutor.** Las reglas de
  “Fifo contesta”/“Fifo cuelga” y `UtterancePolicy` no cambian.

---

## Migration plan

| Paso | Alcance | Estado |
| :--- | :--- | :--- |
| 1 | `FifoSkillRegistry` recibe `AppGraph` y construye skills con módulos | Hecho |
| 2 | Siete skills fuera del accessor | Hecho |
| 3 | `MemoryRemotePort`/`CommunityRemotePort` extendidos | Hecho |
| 4 | `CallRemotePort` para identificación y eventos de llamada | Hecho |
| 5 | `VoiceIntentRouter` con `ContinuousListeningState?` inyectado | Hecho |
| 6 | Pipeline, telefonía y `data/memory` fuera del accessor | Parcial: `data/memory`, red, audio y ubicación fuera (ADR-0052); restan pipeline y telefonía |
| 7 | Borrar el accessor a cero | Pendiente |

Contador al cerrar esta pasada: **91 llamadas** (antes 137). Tras ADR-0052:
**66**.

---

## Verification

| Prueba | Qué fija |
| :--- | :--- |
| `ContentPublicationTest` | gustos, historias, recordatorios y rutinas se guardan y publican una vez; una publicación social sin usuario queda local |
| `VoiceIntentRouterTest` | el router sigue resolviendo comandos sin grafo instalado y sin tocar estado global |
| `DomainModulesTest` | los módulos siguen pasando sin red con `Noop` |
| `FifoStateHoldersTest` | la UI sigue proyectando módulos y delegando comandos |

Suite completa: **457 tests debug / 457 release, 0 fallos, 6 omitidos**, más
`compileReleaseKotlin`.

---

## Invariants

1. **Una skill recibe módulos, no el accessor.** El registro reparte solo lo que
   cada skill usa.
2. **La skill no habla con `FifoBackendClient`.** La red entra por
   `MemoryRemotePort`, `CommunityRemotePort`, `RelationalRemotePort`,
   `DeviceRemotePort` o `CallRemotePort`.
3. **Publicar es del módulo.** Guardar local y publicar ocurre en el dueño; un
   fallo de red no pierde la escritura local.
4. **La acción de llamada sigue siendo del manager.** `PhoneCallSkill` pide al
   coordinador; `FifoCallManager` ejecuta.
5. **La escucha se pide, no se decreta.** El router usa `graph.listening` o
   `null`; nunca escribe un segundo booleano.
6. **El accessor no crece.** Toda acción nueva va al módulo, al puerto o al grafo.
