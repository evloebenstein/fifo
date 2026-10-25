# ADR-0038: Retiro del singleton de navegación

**Status:** Accepted

**Date:** 2026-10-24

**Author:** Architecture Review — Navigation singleton removal

---

## Context

ADR-0007 convirtió `FifoNavigationManager` de `object` global a clase
inyectable con `LocationAdapter`, pero dejó un shim temporal:

```kotlin
@Deprecated("Inject FifoNavigationManager via constructor")
lateinit var instance: FifoNavigationManager
fun initSingleton(manager: FifoNavigationManager)
```

`FifoSkillRegistry` lo inicializaba y cinco consumidores seguían leyéndolo:
`VoiceIntentRouter`, `DefaultConversationContextProvider`, `GroqDialogClient`,
`CloudApiClient` y `VoicePipelineManager`. Eso mantenía dos problemas:

1. el estado de navegación era global y dependía del orden de inicialización;
2. cualquier acceso antes de `initSingleton` lanzaba
   `UninitializedPropertyAccessException` en runtime.

La ruta de migración ya estaba escrita en ADR-0007; faltaba ejecutarla.

---

## Decision

### 1. El manager se construye una vez por registro de skills

`FifoSkillRegistry` pasa a ser la raíz de composición:

```kotlin
class FifoSkillRegistry(
    private val context: Context,
    private val groqCatalog: GroqToolCatalog = GroqToolCatalog(),
    private val locationAdapter: LocationAdapter = AndroidLocationAdapter(context),
    val navigationManager: FifoNavigationManager = FifoNavigationManager(
        locationAdapter = locationAdapter,
        locationManagerProvider = { context.getSystemService(LOCATION_SERVICE) as? LocationManager }
    ),
    private val intentRouter: VoiceIntentRouter = VoiceIntentRouter(context, navigationManager)
)
```

`navigationManager` es público para que `VoicePipelineManager` cablee el
callback de guía autónoma y lo entregue a `CloudApiClient`.

### 2. Los consumidores lo reciben por constructor

- `VoiceIntentRouter(context, navigationManager)` — confirmación de destino,
  cancelación, reporte de guía y conciencia espacial.
- `CloudApiClient(..., navigationManager)` — lo expone a `GroqDialogClient`
  como proveedor.
- `GroqDialogClient(..., navigationManager = { ... })` — contexto de
  navegación del system prompt.
- `DefaultConversationContextProvider(context, navigationManager)` — contexto
  de navegación del proveedor de conversación.
- `VoicePipelineManager` — usa `skillRegistry.navigationManager` para
  `onAutonomousGuidance`, tanto en legacy como en `VoiceSessionPipeline`.

### 3. Se elimina el shim

Se borran `instance`, `initSingleton` y sus `@Deprecated` de
`FifoNavigationManager`. Ya no existe estado de navegación global.

---

## Files Changed

| File | Change |
|---|---|
| `location/FifoNavigationManager.kt` | **Updated** — sin singleton ni shim |
| `skills/FifoSkillRegistry.kt` | **Updated** — construye y expone el manager una sola vez |
| `skills/VoiceIntentRouter.kt` | **Updated** — manager por constructor |
| `network/CloudApiClient.kt` | **Updated** — manager por constructor y proveedor al diálogo |
| `network/GroqDialogClient.kt` | **Updated** — proveedor de contexto de navegación |
| `pipeline/session/SpeechCognitionSeam.kt` | **Updated** — `DefaultConversationContextProvider` recibe el manager |
| `pipeline/VoicePipelineManager.kt` | **Updated** — cablea `skillRegistry.navigationManager` |
| `test/.../skills/VoiceIntentRouterTest.kt` | **Updated** — usa un manager inyectado y prueba confirmación/cancelación |

---

## What Did NOT Change

- No cambia el estado de navegación ni sus reglas (`pendingDestination`,
  `activeRoute`, guía, desvíos, llegada).
- No cambia `MapsSkill` ni `NavigationDirectionsSkill`: ya recibían el manager
  por constructor desde ADR-0007.
- No cambia `LocationAdapter`, `AndroidLocationAdapter`,
  `InMemoryLocationAdapter` ni `FifoLocationHelper`.
- No cambia el comportamiento de voz, telefonía, memoria ni datos.
- No se agregan dependencias ni singletons nuevos.

---

## Consequences

**Positive:**

- Desaparece el riesgo de `lateinit` sin inicializar en cualquier caller.
- El estado de navegación tiene un dueño explícito: el registro de skills.
- `VoiceIntentRouter` se puede probar con un manager y un adaptador en memoria,
  sin singleton ni orden de inicialización.
- La ruta de migración de ADR-0007 queda cerrada.

**Negative / Trade-offs:**

- `FifoSkillRegistry` concentra ahora la creación del adaptador y del manager.
  Es una raíz de composición deliberada; los callers no deben construir otro
  manager.
- Si en el futuro existen dos pipelines en el mismo proceso, cada uno tendrá su
  propio manager. Es el comportamiento correcto: el estado de navegación
  pertenece al pipeline activo, no al proceso.

---

## Verification

- JVM: `testDebugUnitTest`, `testReleaseUnitTest`, `compileReleaseKotlin` en
  verde, incluidas las pruebas nuevas de confirmación/cancelación con manager
  inyectado.
- Emulador Android (`Pixel_9_Pro`, API 36): instalación debug, arranque en frío,
  `FifoSkillRegistry` con 13 herramientas y consulta de texto
  “Fifo dónde estoy” resuelta localmente por el router inyectado, sin
  `FATAL EXCEPTION`.

---

## Related

- [ADR-0007](0007-geointentseam-decouple-geo-conversational-cluster.md) — seam
  geográfico y shim original.
- [ADR-0036](0036-activity-suggestion-policy.md) — política pura extraída del
  repositorio.
- [ADR-0037](0037-device-location-policy.md) — transiciones del dispositivo.
