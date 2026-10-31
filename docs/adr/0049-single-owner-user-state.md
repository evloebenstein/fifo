# ADR-0049: Un solo dueño del estado del usuario

**Status:** Accepted

**Date:** 2026-10-30

**Author:** Architecture Review — Datos y composición

---

## Context

`FifoDataRepository` nació como un objeto único que mezclaba cinco
responsabilidades:

1. construir y poseer la base SQLite;
2. cargar la sesión del usuario y hablar con el backend;
3. mantener el `StateFlow` de perfil, memoria, comunidad, robot y hardware;
4. ejecutar mutaciones de dominio con efectos de red;
5. servir de estado global para la UI, las skills y el pipeline de voz.

Al crecer, el objeto acumuló más de mil líneas y **196 llamadas** desde la
actividad, el servicio, el pipeline, la red, las skills y las pantallas. Nadie
podía probar una pieza sin arrastrar el resto, y el estado se construía en dos
sitios:

- `FifoDataRepository.initialize()` lo hacía desde `MainActivity` y desde
  `FifoVoiceService`;
- `DefaultConversationContextProvider` recomponía hora, ubicación y memoria;
- `GroqDialogClient` añadía otra capa de contexto temporal por su cuenta.

Eso produjo tres defectos reales:

| Defecto | Consecuencia |
| :--- | :--- |
| `UserProfileDao.upsertSyncedProfile()` no escribía `email` ni `username` | La columna `users.email` es `NOT NULL`; el perfil sincronizado podía fallar o quedar sin identidad local. |
| `SqliteMemoryStorageAdapter.removeMemory()` era un `{}` | La UI decía “recuerdo borrado” y el recuerdo volvía al recargar. |
| Los adaptadores de comunidad devolvían `true` siempre | Un fallo de SQLite se presentaba como éxito. |

La causa no era un método mal escrito: era **un objeto con demasiados dueños**.
La pregunta correcta no era “¿cómo ordeno `FifoDataRepository`?”, sino:

> ¿Quién es el dueño de cada estado y quién lo construye una sola vez?

---

## Decision

### 1. Composition root explícito

`FifoApplication.onCreate()` construye `AppGraph` una vez. La actividad, el
servicio y los receivers leen **la misma instancia**. Ninguno vuelve a crear
almacenamiento.

```kotlin
class FifoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.install(this)
    }
}
```

`AppGraph.install(context)` es idempotente; `requireInstance()` falla con un
mensaje claro si algo se saltó el arranque; `instanceOrNull()` existe solo para
pruebas y para costuras que deben tolerar un grafo ausente. `FifoDataRepository.
initialize()` sigue funcionando como alias durante la migración.

### 2. El grafo expone módulos, no una fachada de cuarenta métodos

| Dueño | Estado / responsabilidad |
| :--- | :--- |
| `identity` (`UserIdentityModule`) | perfil, demografía, bio, intereses |
| `memory` (`EpisodicMemoryModule`) | recuerdos, gustos, recordatorios, conversaciones, fragmentos, rutinas |
| `community` (`CommunityModule`) | actividades y muro social |
| `relationalGraph` (`RelationalGraphModule`) | contactos, apariciones e indicios de teléfono |
| `session` (`UserSession`) | usuario activo, login, cambio de perfil y sincronización |
| `device` (`DeviceState`) | robot, hardware y conexión con el backend |
| `conversationContext` (`ConversationContext`) | ventana de prompt y búsqueda profunda |

Cada consumidor toma **solo el módulo que usa**. No existe un `UserContext` con
cuarenta métodos: eso habría sido mover el problema de sitio.

### 3. `FifoDataRepository` queda como accessor deprecado

El objeto histórico ya no posee estado: delega en `AppGraph` y conserva la misma
API pública para no romper 196 llamadas de golpe. Lleva `@Deprecated` con
`ReplaceWith("AppGraph.requireInstance()")`.

La migración es **strangler por capas**:

```
data → red → pipeline → skills → UI
```

No se añade lógica nueva al accessor y se borra cuando el contador de llamadas
llegue a cero. Mientras exista, la única regla es: **no crear un segundo
almacenamiento, VAD, TTS ni manager de telefonía**.

La telefonía sigue fuera de esta pasada: `CallStateCoordinator` permanece en el
accessor y se migra en su propia tarjeta.

### 4. Modelos puros y presentación aparte

Los modelos de dominio viven en `data/model/FifoModels.kt`:

- no conocen Android ni Compose;
- los consumen datos, red y pruebas;
- el detalle de presentación (iconos) vive en `ui/model/FifoModels.kt` como
  typealiases y extensiones `ImageVector`.

Regla: un modelo nuevo de dominio nace en `data/model`; si necesita un
`ImageVector` o cualquier tipo de Compose, la extensión va en `ui/model`. Nunca
se declara una `data class` nueva en `ui/model`.

`UserProfileData.email` es parte de la identidad local: el upsert de
sincronización debe escribirla siempre (columna `NOT NULL`).

### 5. Memoria de doble capa con una sola ventana y una sola guía

**Capa 1 — ventana compacta, siempre local.** `ConversationContext.
buildContextWindow()` compone, en un solo lugar:

1. hora y fecha reales;
2. ubicación GPS o domicilio estimado;
3. contexto de la ruta activa;
4. memoria episódica compacta (perfil, gustos, contactos, recordatorios, última
   charla).

**Capa 2 — recuperación bajo demanda.** `ConversationContext.searchDeep()`
consulta primero el backend (`DeepContextPort`) y cae al índice local
(`EpisodicMemoryModule.searchDeepLocal()`) si no hay red o no hay coincidencias.
Es `suspend`: ya no bloquea el turno con `runBlocking`. Solo se invoca cuando el
LLM usa `recall_past_context`.

**La política de recuperación es prompt, no locución.** La guía vive en
`ConversationContext.MEMORY_RECOVERY_GUIDE` y tiene cinco disparadores:

1. la pregunta queda abierta o inconclusa y el contexto no trae evidencia;
2. falta un detalle que el usuario espera que Fifo recuerde;
3. Fifo está por afirmar, insinuar o sugerir algo sobre el pasado;
4. el usuario corrige o duda de algo que Fifo dijo;
5. hay una laguna o el usuario pide precisión sobre una charla anterior.

Regla: **buscar antes de afirmar; si no hay evidencia, decirlo; nunca inventar.**
El LLM sigue redactando la respuesta.

**El resultado del skill es payload.** `ContextRecallSkill` devuelve
`context_status`, `synthesized_context`, `excerpts`, `found_entities` y
`conversations_searched` en `data`. `spokenFeedback` va vacío. Cuando no hay
evidencia devuelve `context_status = no_evidence` para que el modelo lo diga con
honestidad, sin una frase enlatada.

### 6. La escucha continua tiene dueño en el orquestador

`TurnOrchestrator` posee `isContinuousListening: StateFlow<Boolean>` y expone
`setContinuousListening()`. `VoiceSession` y `VoiceSessionPipeline` delegan. La
UI sigue leyendo el espejo del facade (`FifoDataRepository.
isContinuousListening`) hasta que existan state holders; `VoicePipelineManager`
instala el puente solo en `SESSION` y publica cada cambio del orquestador.

En `LEGACY` no hay puente: el camino antiguo conserva su propio estado.

### 7. Costuras inyectables en vez de llamadas estáticas

| Costura | Producción | Pruebas |
| :--- | :--- | :--- |
| `ContactSourcePort` | `AndroidContactSource` (libreta) | lista fija, sin permiso |
| `DeviceRemotePort` | `BackendDeviceRemote` | registro de patches |
| `RelationalRemotePort` | `BackendRelationalRemote` | no-op |
| `DeepContextPort` | `BackendDeepContextPort` | `InMemoryDeepContextPort` |

`DeviceContactsSync` solo orquesta lectura + ingesta; `RelationalGraphModule`
posee fusión, procedencia y publicación. Ninguno abre hardware ni habla con la
UI.

### 8. SQLite local como read model

La base local es un read model que la UI y el servicio leen mientras el pipeline
escribe. `FifoDatabaseHelper` habilita WAL para que una lectura no bloquee al
escritor (ni al revés). No hay poda: el detalle completo vive en el backend y se
recupera bajo demanda.

---

## Consequences

### Lo que mejora

- **Un solo dueño por estado.** La actividad y el servicio ya no compiten por
  inicializar la base ni por cargar la sesión.
- **Pruebas JVM reales.** `ConversationContext`, `RelationalGraphModule`,
  `DeviceState` y `ContextRecallSkill` se prueban sin Android, sin red y sin
  SQLite, con dobles inyectados.
- **La ventana de contexto no se duplica.** Hay una sola composición de hora,
  ubicación, ruta y memoria; `DefaultConversationContextProvider` queda como
  envoltorio deprecado.
- **La memoria profunda no bloquea.** `searchDeep()` es `suspend` y el turno
  sigue fluyendo; el backend manda y el índice local es respaldo.
- **La UI no mete Compose en datos.** `data/model` es puro; los iconos viven en
  `ui/model`.
- **Los tres defectos quedan cubiertos por la costura:** `email`/`username` en el
  upsert, borrado real de recuerdos y resultados reales en comunidad.

### Lo que cuesta

- Hay **dos APIs** durante la migración (`AppGraph` y el accessor deprecado). Las
  advertencias de deprecación son esperadas hasta borrar el accessor.
- `AppGraph` concentra el wiring; si crece sin disciplina, puede volverse otro
  objeto dios. Por eso expone módulos y no métodos de negocio.
- La UI sigue leyendo el espejo del facade para escucha continua. Es un puente
  temporal, no el destino.

### Lo que **no** resuelve

- **El borrado remoto no existe.** Borrar una conversación o un nodo en el
  backend no está implementado; el filtro local de `searchDeep()` evita
  resucitarlos desde el índice del dispositivo, pero un nodo remoto borrado
  fuera de la app seguiría apareciendo si el backend lo devuelve. Queda como
  hueco documentado, no como garantía.
- **La telefonía no se migra aquí.** `CallStateCoordinator` sigue en el
  accessor; su tarjeta moverá el estado al grafo.
- **Las pantallas no tienen state holders todavía.** Leen `StateFlow` globales;
  la migración de UI es la última capa.

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
| 7 | UI: state holders y borrado del accessor | Pendiente |

---

## Verification

| Prueba | Qué fija |
| :--- | :--- |
| `ConversationContextTest` | ventana única, guía de cinco disparadores, backend primero, respaldo local y `no evidence` |
| `RelationalGraphModuleTest` | ingesta, fusión, rol genérico→concreto, menciones y publicación por costura |
| `DeviceStateTest` | conexión/desconexión, beeper sin tocar ubicación, hardware parcial e importación remota |
| `ContextRecallSkillTest` | payload, `no_evidence`, consulta vacía y ausencia de frases enlatadas |
| `DomainModulesTest` | módulos de dominio y `searchDeepLocal()` sin `runBlocking` |
| `GeoIntentSeamTest` | la skill de navegación sigue siendo JVM-testable sin grafo instalado |

Suite completa: **442 tests debug / 442 release, 0 fallos, 6 omitidos**, más
`compileReleaseKotlin`.

---

## Invariants

1. **Un solo composition root:** `FifoApplication` construye `AppGraph`; la
   actividad y el servicio solo lo consumen.
2. **Un solo dueño por estado:** identidad, memoria, comunidad, grafo, sesión,
   dispositivo y contexto tienen un módulo dueño. No dupliques `StateFlow` ni
   almacenamiento.
3. **El accessor no crece:** `FifoDataRepository` solo delega y está deprecado.
   No agregues lógica nueva ahí.
4. **La ventana de contexto se compone una vez:** `ConversationContext` es la
   única fuente de hora, ubicación, ruta y memoria compacta para el prompt.
5. **La recuperación es guía, no frase:** los cinco disparadores viven en el
   prompt; el LLM redacta y `spokenFeedback` del skill queda vacío.
6. **Backend primero, local como respaldo:** `searchDeep()` no usa
   `runBlocking`, no se llama antes de hablar y solo corre bajo demanda.
7. **La escucha continua pertenece al orquestador:** el facade espeja; no crees
   un segundo booleano en la UI ni en la sesión.
8. **Los modelos de dominio no conocen Compose:** `data/model` es puro; los
   iconos son extensiones de `ui/model`.
9. **La telefonía sigue fuera:** esta decisión no toca `FifoCallManager`,
   `AndroidTelephonyPort` ni la captura.
10. **El borrado remoto es un hueco conocido:** no lo presentes como resuelto.
