# ADR-0050: State holders de pantalla y publicación en los módulos

**Status:** Accepted

**Date:** 2026-10-31

**Author:** Architecture Review — Datos, UI y composición

---

## Context

ADR-0049 dejó un composition root (`AppGraph`) y siete módulos con dueño, pero
la migración strangler se detuvo antes de la UI. Quedaban dos síntomas:

1. **La UI leía un objeto global.** `MainActivity`, `HomeScreen`,
   `ProfileScreen`, `FriendsScreen`, `ActivitiesScreen`,
   `VoiceInteractionSheet`, `GoogleSignInDialog` y `FifoDeviceFinderDialog`
   llamaban directamente a `FifoDataRepository` para leer `StateFlow` y para
   escribir comandos. La pantalla no tenía una superficie propia: dependía de
   que el accessor expusiera cada `StateFlow` y cada mutación.
2. **La publicación en red vivía en el accessor o en la pantalla.**
   `addMemory`, `toggleJoinActivity` y `createCommunityActivity` guardaban local
   y luego llamaban a `FifoBackendClient`. Eso mezclaba persistencia con
   transporte y dejaba a la UI con la responsabilidad de "avisar al backend".

El contador de llamadas al accessor era **196** al aprobar ADR-0049. Tras migrar
`data`, `red` y parte del pipeline, quedaban **137**, todas fuera de la UI:

| Capa | Llamadas |
| :--- | ---: |
| `pipeline` | 47 |
| `skills` | 46 |
| `telephony` | 24 |
| `data` | 12 |
| `network` | 3 |
| `audio` | 3 |
| `location` | 2 |

La pregunta de esta pasada no era "¿cómo ordeno las pantallas?", sino:

> ¿Quién proyecta el estado hacia la UI y quién es dueño de publicar un cambio?

---

## Decision

### 1. State holders de pantalla como proyecciones de solo lectura

`ui/state/FifoStateHolders.kt` define un holder por superficie:

| Holder | Pantalla | Proyecta |
| :--- | :--- | :--- |
| `SessionStateHolder` | login, diálogo de Google | perfil activo, búsqueda de usuario, login y cambio de perfil |
| `VoiceUiStateHolder` | `HomeScreen`, `VoiceInteractionSheet` | recuerdos, recordatorios, conversaciones, llamada entrante y escucha continua |
| `ActivitiesStateHolder` | `ActivitiesScreen` | actividades, perfil, gustos, contactos, apariciones e indicios |
| `ProfileStateHolder` | `ProfileScreen` | perfil, gustos, historias y publicaciones |
| `FriendsStateHolder` | `FriendsScreen` | perfil, publicaciones y sincronización de amigos |
| `DeviceUiStateHolder` | buscador del robot | ubicación del dispositivo y beeper |

Un holder **no posee estado**: expone referencias a los `StateFlow` del módulo y
comandos que llaman al dueño. Por eso se crea con:

```kotlin
val voiceState = fifoStateHolder { graph ->
    VoiceUiStateHolder(
        memory = graph.memory,
        identity = graph.identity,
        telephony = graph.telephony,
        listening = graph.listening
    )
}
```

`fifoStateHolder` es `remember { create(AppGraph.requireInstance()) }`. No se usa
`ViewModel`: el estado vive en los módulos, así que recrear la actividad no
pierde nada y no hace falta una segunda copia con ciclo de vida propio. Un
`ViewModel` aquí habría sido un contenedor de contenedores.

Regla: **la pantalla no crea `MutableStateFlow` de dominio.** Su estado local es
solo de presentación (diálogo abierto, texto de búsqueda, filtro elegido).

### 2. La publicación pertenece al módulo, por costura inyectable

`MemoryRemotePort` y `CommunityRemotePort` son las dos costuras nuevas:

| Costura | Producción | Pruebas / sin red |
| :--- | :--- | :--- |
| `MemoryRemotePort` | `BackendMemoryRemote` | `NoopMemoryRemote` |
| `CommunityRemotePort` | `BackendCommunityRemote` | `NoopCommunityRemote` |

`EpisodicMemoryModule.addMemory()` y `CommunityModule.toggleJoinActivity()` /
`createActivity()` guardan local y luego publican por su puerto. `AppGraph`
inyecta los adaptadores de producción; las pruebas inyectan grabadores o no-ops.

Tres reglas acompañan la costura:

- Los métodos son `suspend` y corren en el `scope` del módulo, así que la
  pantalla no espera a la red.
- El adaptador de producción **absorbe el fallo** con log: si el backend cae, la
  escritura local ya ocurrió y no se pierde.
- La UI no conoce `FifoBackendClient` para estas acciones. Publicar no es un
  gesto de pantalla.

### 3. Telefonía y escucha continua entran al grafo

`AppGraph` expone dos contenedores más:

| Dueño | Qué es | Quién manda |
| :--- | :--- | :--- |
| `telephony` (`CallStateCoordinator`) | llamada visible, última activa y callbacks | `FifoCallManager` / `AndroidTelephonyPort` ejecutan; el contenedor solo publica |
| `listening` (`ContinuousListeningState`) | proyección de escucha continua | `TurnOrchestrator` decide; el contenedor refleja y encamina |

`ContinuousListeningState` separa dos verbos:

- `request(active)`: la UI pide; actualiza la proyección e invoca el `handler`
  instalado por `VoicePipelineManager` (solo en `SESSION`).
- `mirror(active)`: el dueño publica su estado real.

Sin orquestador (`LEGACY` o pipeline detenido) el `handler` es `null` y
`request()` solo mueve la proyección: **no hay un segundo booleano que pueda
contradecir al dueño**.

`FifoDataRepository` conserva `isContinuousListening`, `setContinuousListening()`
y `mirrorContinuousListening()` como delegación al grafo, porque
`VoiceIntentRouter` sigue usándolos hasta su propia tarjeta.

### 4. Fallback sin composition root, solo para pruebas JVM

El accessor mantiene un `detachedListening` privado. Se usa **únicamente** cuando
`AppGraph.instanceOrNull()` es `null`, es decir, en pruebas JVM que ejercitan el
router sin Android (`VoiceIntentRouterTest`). En producción el grafo siempre
existe y esa instancia nunca se lee. No es un segundo dueño: es el mismo patrón
de `instanceOrNull()` que ADR-0049 dejó para costuras que deben tolerar ausencia.

### 5. El facade limpia su puente al detenerse

`VoicePipelineManager.stop()` pone `continuousListeningHandler = null` y luego
`mirrorContinuousListening(false)` antes de detener el pipeline, en ambos
caminos. Sin eso, una `request` tardía de la UI podría invocar un orquestador ya
detenido.

---

## Consequences

### Lo que mejora

- **La UI tiene una superficie explícita.** Cada pantalla declara qué estado
  proyecta y qué comandos puede ejecutar. Cero referencias a
  `FifoDataRepository` en `ui/` y `MainActivity`.
- **La publicación se prueba sin red.** `ContentPublicationTest` verifica que el
  módulo guarda y publica una vez, que borrar no publica y que crear una
  actividad sin usuario no inscribe a nadie.
- **La pantalla no espera al backend.** El comando devuelve después de la
  escritura local; la red corre en el scope del módulo.
- **Telefonía y escucha tienen un lugar único.** La UI y el facade leen el mismo
  `StateFlow`; no hay copias por pantalla.
- **El grafo sigue exponiendo módulos.** No se convirtió en una fachada de
  cuarenta métodos; sumó dos contenedores con dueño claro.

### Lo que cuesta

- **Wiring repetido en composables.** Cada pantalla llama a `fifoStateHolder`.
  Es barato (`remember`) pero visible; un helper por pantalla lo escondería y
  volvería a crear una fachada de UI.
- **Dos APIs durante la migración.** El accessor sigue vivo con 137 llamadas
  fuera de la UI; las advertencias de deprecación son esperadas.
- **`detachedListening` es deuda de pruebas.** Existe por el router; se borra
  cuando `VoiceIntentRouter` migre al grafo.

### Lo que **no** resuelve

- **El pipeline y la telefonía siguen usando el accessor.** Las skills ya salieron
  en ADR-0051; el accessor no se borra hasta llegar a cero.
- **El borrado remoto sigue sin existir.** Esta pasada no toca backend.
- **No hay `ViewModel` ni state holder con ciclo de vida.** Si en el futuro una
  pantalla necesita sobrevivir a un proceso, el estado debe mudarse al módulo,
  no al holder.

---

## Migration plan

| Paso | Alcance | Estado |
| :--- | :--- | :--- |
| 1 | Modelos puros en `data/model`; `ui/model` con typealiases e iconos | Hecho |
| 2 | `AppGraph` + `FifoApplication`; actividad y servicio dejan de inicializar | Hecho |
| 3 | Módulos `RelationalGraphModule`, `DeviceState`, `UserSession`, `ConversationContext` | Hecho |
| 4 | Accessor `@Deprecated` y migración de datos/red | Hecho |
| 5 | Pipeline: escucha continua al `TurnOrchestrator` y contexto al grafo | Hecho |
| 6 | Skills: `ContextRecallSkill` por costura y payload | Hecho |
| 7 | UI: state holders, publicación en módulos, telefonía y escucha en el grafo | Hecho |
| 8 | Pipeline, skills y telefonía fuera del accessor; borrar el accessor a cero | Parcial: skills en cero (ADR-0051), memoria/red/audio/ubicación en cero (ADR-0052); restan 66 llamadas |

Contador de llamadas al accessor al cerrar esta pasada: **137**; tras ADR-0051
(skills fuera y puertos extendidos): **91**; tras ADR-0052 (consolidador, prompt,
audio y ubicación): **66**.

---

## Verification

| Prueba | Qué fija |
| :--- | :--- |
| `ContentPublicationTest` | el módulo guarda y publica una vez; borrar no publica; crear actividad inscribe solo con organizador |
| `FifoStateHoldersTest` | proyección de solo lectura, comandos al dueño, escucha por el puente y telefonía por el coordinador |
| `VoiceIntentRouterTest` | el router sigue funcionando sin grafo instalado (`detachedListening`) |
| `DomainModulesTest` | los módulos siguen pasando sin red con `Noop` |

Suite completa: **451 tests debug / 451 release, 0 fallos, 6 omitidos**, más
`compileReleaseKotlin`.

---

## Invariants

1. **Un holder no posee estado.** Proyecta `StateFlow` del módulo y delega
   comandos; su `remember` no guarda dominio.
2. **La pantalla no publica.** Guardar y publicar ocurre en el módulo, por su
   `*RemotePort`.
3. **Un fallo de red no pierde la escritura local.** El adaptador de producción
   absorbe el error.
4. **La escucha continua sigue siendo del `TurnOrchestrator`.**
   `ContinuousListeningState` refleja y encamina; `mirror()` es la única escritura
   legítima desde el dueño.
5. **La telefonía visible vive en `graph.telephony`.** Las acciones siguen en
   `FifoCallManager`; el contenedor no contesta ni cuelga por su cuenta.
6. **`detachedListening` es solo para pruebas sin grafo.** No lo uses como
   respaldo de producción ni agregues un segundo booleano en la UI.
7. **El accessor no crece.** Toda acción nueva va al módulo o al grafo.
8. **El borrado remoto sigue siendo un hueco conocido.**
