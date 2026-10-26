# ADR-0041: Sesión de ruta durable y avisos que no se pierden por esperar

**Status:** Accepted

**Date:** 2026-10-25

**Author:** Architecture Review — Acompañamiento de navegación

---

## Context

ADR-0040 dejó dos límites explícitos en el acompañamiento autónomo:

1. **La sesión de ruta vivía en RAM.** `NavigationSessionMemory` guardaba el
   destino, los encargos, la pausa y la corrección, pero un cierre de la app, un
   reinicio del teléfono o la muerte del proceso por el sistema borraban el
   acompañamiento. El usuario seguía caminando y Fifo ya no sabía a dónde iba.
2. **Un aviso se descartaba si el turno en curso duraba más de 6 s.** El pipeline
   esperaba como máximo 6 s a que `PROCESSING`/`SPEAKING` terminara. Una respuesta
   larga del LLM, una conversación lateral o una llamada hacían que el aviso de
   desvío, quietud o llegada se perdiera en silencio.

Ninguno de los dos límites es aceptable para un copiloto: la ruta es del usuario,
no del proceso, y un aviso viejo es mejor que ningún aviso, siempre que se
refiera al destino correcto.

---

## Decision

### 1. La ruta se persiste con una costura, no con Android dentro del dominio

`location/NavigationSessionStore.kt` define:

```kotlin
data class PersistedStop(val label: String, val addedAtMillis: Long)

data class PersistedNavigationSession(
    val savedAtMillis: Long,
    val destinationName: String,
    val latitude: Double,
    val longitude: Double,
    val fullAddress: String,
    val road: String,
    val routeStartedAtMillis: Long,
    val stops: List<PersistedStop> = emptyList(),
    val pauseReason: String = "",
    val pauseUntilMillis: Long? = null,
    val pauseStartedAtMillis: Long = 0L,
    val lastCorrection: String? = null,
    val lastCorrectionAtMillis: Long = 0L
) {
    fun isExpired(nowMillis: Long, ttlMillis: Long = DEFAULT_TTL_MILLIS): Boolean
}

interface NavigationSessionStore {
    fun load(): PersistedNavigationSession?
    fun save(session: PersistedNavigationSession)
    fun clear()
}
```

Implementaciones:

| Clase | Uso |
|---|---|
| `SharedPreferencesNavigationSessionStore` | Producción. Prefs `fifo_navigation`, clave `active_session`, Gson. Una foto corrupta o incompleta se descarta en `load()`. |
| `InMemoryNavigationSessionStore` | Pruebas JVM. Sin Android, inspeccionable. |

El dominio sigue sin conocer Android: `FifoNavigationManager` recibe el store por
constructor y `null` lo deja volátil, como en los dobles de prueba existentes.

### 2. Cada mutación efectiva deja una foto

`NavigationSessionMemory` expone `snapshot()`/`restore()` y el callback
`onChanged`. `FifoNavigationManager` lo conecta en su `init` a `persistSession()`:

- `add_stop`, `complete_stops`, `pause`, `resume`, `correct`, `clearCorrection` y
  `reset` notifican solo cuando cambian algo (un duplicado o una corrección vacía
  no reescriben el disco).
- `restore()` no notifica: restaurar no es un cambio del usuario.
- `startNavigation()` y `confirmPendingDestination()` guardan el destino; una
  llegada o `stopNavigation()` limpian la foto porque ya no hay ruta que reponer.
- `persistSession()` toma `savedAtMillis = now` en cada escritura y usa `apply()`
  para no bloquear el hilo que atiende la herramienta o el tick de GPS.

### 3. La restauración caduca a las 6 horas

`VoicePipelineManager.start()` llama a `restorePersistedSession()` antes de
construir el camino legacy o el de sesión. La restauración:

- es idempotente: si ya hay una ruta activa no la pisa;
- repone destino, calle, dirección, encargos, pausa y corrección;
- descarta una pausa cuyo plazo ya venció (el usuario ya no está esperando);
- vuelve a registrar el listener GPS continuo;
- descarta y borra la foto si tiene más de 6 h
  (`PersistedNavigationSession.DEFAULT_TTL_MILLIS`).

Seis horas es el horizonte razonable de un trayecto peatonal: más allá, la foto
ya no representa el viaje que la persona está haciendo.

### 4. Los avisos coalescen al más reciente y esperan sin plazo

`pipeline/NavigationNoticeQueue.kt` es una cola pequeña y pura-JVM:

- `offer(notice)` guarda el aviso y devuelve `true` solo al primer aviso de una
  tanda; los siguientes reemplazan al pendiente (gana el último).
- `poll()` toma el más reciente; un `null` libera la tanda para que el próximo
  aviso arranque un drenaje nuevo.
- Un solo drenaje puede estar activo a la vez; `release()` cubre el cierre
  anormal del job.

`VoicePipelineManager.handleNavigationNotice()` ya no usa deadline:

1. encola el aviso;
2. `awaitIdleForAutonomousSpeech()` espera, sin límite, a que el pipeline no esté
   en `PROCESSING` ni `SPEAKING`;
3. si mientras esperaba llegó otro hecho, se locuta el más reciente y se descarta
   el anterior;
4. pide la narración al LLM y usa `fallbackSpeech()` solo si no responde;
5. solo descarta un aviso si el destino activo cambió. `ARRIVED` se anuncia
   aunque la ruta ya se haya cerrado al detectarla; si el usuario arrancó otro
   viaje distinto, la llegada vieja se descarta.

No se descarta nada por duración: el acompañamiento no se pierde porque una
respuesta tarde más de 6 s.

---

## Files Changed

| File | Change |
|---|---|
| `location/NavigationSessionStore.kt` | **New** — foto durable, interfaz, implementación `SharedPreferences` y doble en memoria. |
| `location/NavigationSessionMemory.kt` | **Updated** — `Snapshot`, `snapshot()`, `restore()`, `onChanged`; mutadores sincronizados y notificación solo en cambios efectivos. |
| `location/FifoNavigationManager.kt` | **Updated** — `sessionStore`, `persistSession()`, `restorePersistedSession()` con TTL de 6 h. |
| `pipeline/NavigationNoticeQueue.kt` | **New** — coalescencia “el último gana” y dueño único del drenaje. |
| `pipeline/VoicePipelineManager.kt` | **Updated** — cola de avisos, espera sin plazo, descarte solo por cambio de destino, restauración en `start()`. |
| `skills/FifoSkillRegistry.kt` | **Updated** — inyecta `SharedPreferencesNavigationSessionStore(context)` en el manager. |
| `test/.../location/NavigationSessionPersistenceTest.kt` | **New** — snapshot/restore, notificaciones, persistencia, restauración y caducidad. |
| `test/.../pipeline/NavigationNoticeQueueTest.kt` | **New** — coalescencia y liberación de la tanda. |

---

## What Did NOT Change

- No cambia el motor de navegación ni la detección de avisos: los hechos siguen
  siendo los mismos (`ARRIVED`, `OFF_COURSE`, `APPROACHING_100M`,
  `APPROACHING_50M`, `STALLED`).
- No cambia la redacción por LLM ni el fallback estático de ADR-0040.
- No cambia `VoiceSession`, la telefonía, la captura de audio ni la memoria
  durable de conversación (`SessionMemory`/`conversation_journal`, ADR-0011).
- No se agregan dependencias: Gson y `SharedPreferences` ya estaban en el
  proyecto.
- No hay sincronización entre dispositivos ni backend: la foto es local.

---

## Consequences

**Positive:**

- Cerrar la app, reiniciar el teléfono o perder el proceso ya no borra la ruta:
  Fifo retoma destino, encargos, pausa y corrección al volver.
- Ningún aviso se pierde por esperar a que el turno termine; si hubo varios, se
  locuta el más reciente, que es el único todavía útil.
- El dominio sigue siendo puro-JVM y testeable; la persistencia es una costura
  inyectable.
- Un aviso de una ruta abandonada no se locuta aunque llegue tarde.

**Negative / Trade-offs:**

- `SharedPreferences` es best-effort: un `apply()` no confirmado antes de un
  `kill -9` puede perder la última mutación. No se justifica un journal SQLite
  para una foto de ruta pequeña.
- Una ruta retomada después de 6 h no se restaura; es deliberado para no guiar
  hacia un viaje que ya terminó.
- La espera sin plazo puede retrasar un aviso hasta que el usuario deje de hablar.
  Se prefiere a interrumpir o descartar.
- Si el usuario cambia de destino justo mientras se narra, el chequeo de
  relevancia puede dejar pasar un aviso del destino anterior. La ventana es de
  milisegundos y el siguiente aviso ya usa la ruta nueva.

---

## Verification

- JVM: `testDebugUnitTest`, `testReleaseUnitTest`, `compileReleaseKotlin` en
  verde.
- `NavigationSessionPersistenceTest` (11 tests):
  - `snapshot`/`restore` conservan encargos, pausa y corrección;
  - una pausa vencida no se restaura;
  - `onChanged` solo dispara en cambios efectivos y nunca durante `restore`;
  - iniciar una ruta persiste el destino; cada encargo/pausa/corrección lo hace;
  - detener la ruta borra la foto;
  - una nueva instancia del manager restaura ruta y sesión;
  - una foto de más de 6 h se descarta y se borra;
  - una ruta ya activa no se pisa al restaurar.
- `NavigationNoticeQueueTest` (5 tests):
  - el primer aviso arranca el drenaje y los siguientes solo encolan;
  - el más reciente gana;
  - `poll()` vacío libera la tanda;
  - `release()` recupera el drenaje tras un cierre anormal.
- Emulador Android (`Pixel_9_Pro`, API 36): con una ruta activa,
  `shared_prefs/fifo_navigation.xml` contenía la foto completa (destino,
  coordenadas, inicio, encargos, pausa); tras `am force-stop` y relanzar la app,
  el log mostró `Ruta restaurada tras reinicio` y el listener GPS continuo se
  volvió a registrar.

---

## Related

- [ADR-0040](0040-dynamic-autonomous-navigation-guidance.md) — guía autónoma
  redactada por el LLM y memoria conversacional de la ruta; este ADR corrige sus
  dos limitaciones declaradas.
- [ADR-0039](0039-dynamic-location-and-navigation-answers.md) — ubicación y
  orientación delegadas al LLM.
- [ADR-0038](0038-navigation-manager-injection.md) — el manager se inyecta y no
  es un singleton.
- [ADR-0011](0011-durable-memory-journal.md) — memoria durable de conversación;
  distinta de la foto de ruta.
- [`AGENTS.md`](../../AGENTS.md) — invariantes de la sesión de ruta.
