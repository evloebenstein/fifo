# location/

Motor de localización, orientación y navegación autónoma de Fifo.

## Archivos

| Archivo | Responsabilidad |
|---|---|
| [`LocationAdapter.kt`](LocationAdapter.kt) | **GeoIntentSeam** — interfaz que aísla todo acceso a hardware GPS, geocodificación, búsqueda de POI e intents de mapa. Única frontera autorizada para operaciones de localización. |
| [`AndroidLocationAdapter.kt`](AndroidLocationAdapter.kt) | Implementación de producción de `LocationAdapter`. Delega en `FifoLocationHelper` y mantiene el `Context` confinado a esta capa. |
| [`InMemoryLocationAdapter.kt`](InMemoryLocationAdapter.kt) | Doble de prueba puro-JVM de `LocationAdapter`. Sin dependencias Android — permite tests unitarios de skills de navegación sin emulador ni Robolectric. |
| [`FifoLocationHelper.kt`](FifoLocationHelper.kt) | Utilidad GPS de bajo nivel: obtención de ubicación fresca, geocodificación inversa, búsqueda Nominatim de POI cercanos, lanzamiento de intents Maps/Waze. No debe ser llamado directamente desde skills; usar `LocationAdapter`. |
| [`FifoNavigationManager.kt`](FifoNavigationManager.kt) | Copiloto peatonal autónomo estilo Waze: monitorea GPS continuo (vía `locationManagerProvider`), detecta desvíos, quietud, proximidad (100 m, 50 m) y llegada. Publica `NavigationNotice` estructurados; **no redacta frases** (ADR-0040). Ya no es un `object` singleton — se inyecta como clase. |
| [`NavigationSessionMemory.kt`](NavigationSessionMemory.kt) | Estado conversacional de la ruta activa: encargos/paradas en el camino, pausas con o sin plazo y última corrección del usuario. `snapshot()`/`restore()` permiten persistirlo y `describeForPrompt()` entrega hechos para el system prompt (ADR-0040, ADR-0041). |
| [`NavigationSessionStore.kt`](NavigationSessionStore.kt) | **Costura de persistencia de la ruta (ADR-0041).** `PersistedNavigationSession`/`PersistedStop`, la interfaz `NavigationSessionStore`, la implementación `SharedPreferences` con Gson y el doble en memoria para tests JVM. |
| [`FifoOrientationHelper.kt`](FifoOrientationHelper.kt) | Sensor de brújula: azimuth en grados y orientación cardinal del dispositivo en español. Singleton de solo lectura, sin efectos de lado. |

## Arquitectura (ADR-0007)

```
Callers (Skills, VoiceIntentRouter, SpeechCognitionSeam)
        │
        ▼
  LocationAdapter (interface)          FifoNavigationManager (class, inyectado)
        │                                        │
  AndroidLocationAdapter                locationManagerProvider()
        │                                        │ (Android only)
  FifoLocationHelper          ◄───────  LocationManager (GPS listener)
  (GPS, Geocoder, Nominatim, Intent)
```

- **Skills** reciben `LocationAdapter` + `FifoNavigationManager` por constructor.
- **`FifoSkillRegistry`** construye el adaptador y el manager una sola vez y los inyecta en `VoiceIntentRouter`, `CloudApiClient`, `DefaultConversationContextProvider` y `VoicePipelineManager`. No existe un singleton de navegación (ADR-0038).

## Contexto espacial para el LLM (ADR-0039)

`FifoNavigationManager.buildSystemPromptContext()` es la **única** fuente de
contexto espacial del prompt: calle, comuna, orientación de la brújula, destino
activo, distancia restante, cuadras, indicación relativa, estado de desvío y
destino pendiente.

Las preguntas “¿dónde estoy?”, “¿dónde voy?” o “¿cuánto falta?” no se responden
con plantillas locales: `VoiceIntentRouter` las delega al LLM, que redacta con
ese contexto. `getSpatialAwarenessDescription()` se eliminó por ser un generador
de frases enlatadas.

## Guía autónoma y sesión de ruta (ADR-0040)

`FifoNavigationManager` no elige frases. Cuando detecta algo emite un
`NavigationNotice` (`ARRIVED`, `OFF_COURSE`, `APPROACHING_100M`,
`APPROACHING_50M`, `STALLED`) con destino, calle, distancia, indicación
relativa, rumbo, segundos detenido, encargos pendientes y pausa. El pipeline
pide al LLM que lo redacte; `NavigationNotice.fallbackSpeech()` es el único
texto estático y solo se usa si el modelo no responde.

`NavigationSessionMemory` guarda lo que pasa entre medio: encargos
(`comprar pan`), pausas (`conversando un rato`, con o sin plazo) y correcciones
(`la ruta sigue por Manquehue`). Se reinicia al iniciar o terminar la ruta.
Durante una pausa consciente no se emiten avisos de desvío, quietud ni hitos; la
llegada siempre se anuncia. El LLM la gestiona con la herramienta
`manage_navigation_session` (`add_stop`, `complete_stops`, `pause`, `resume`,
`correct`, `status`).

`noticeDispatch` es la costura de entrega: en producción usa el `Handler`
principal y en pruebas JVM entrega de forma síncrona.

## Ruta durable (ADR-0041)

La ruta no vive solo en RAM. `FifoNavigationManager` recibe un
`NavigationSessionStore` (producción:
`SharedPreferencesNavigationSessionStore`, prefs `fifo_navigation`) y guarda una
`PersistedNavigationSession` en cada mutación efectiva de la sesión: inicio o
confirmación de ruta, encargo, pausa, corrección y fin de ruta (que limpia la
foto). `NavigationSessionMemory.onChanged` es el disparador.

Al arrancar, `VoicePipelineManager.start()` llama a `restorePersistedSession()`:
repone destino, encargos, pausa y corrección, vuelve a registrar el GPS y
descarta la foto si tiene más de 6 h o si ya hay una ruta activa. Una pausa cuyo
plazo venció se restaura sin pausa.

```
NavigationSessionMemory ──onChanged──► FifoNavigationManager.persistSession()
                                             │
                                             ▼
                              NavigationSessionStore (SharedPreferences)
                                             │
                    VoicePipelineManager.start() ──restorePersistedSession()
```

## Notas importantes

1. **GPS solo cuando hay ruta activa:** El listener continuo se inicia en `startNavigation()` y se detiene en `stopNavigation()` / llegada a destino.
2. **`FifoLocationHelper` NO cambia:** toda la lógica de resolución de ubicación, geocodificación y Nominatim sigue igual; `AndroidLocationAdapter` es solo un wrapper de compatibilidad.
3. **`pendingDestination`:** El estado de confirmación vive en `FifoNavigationManager.pendingDestination`. `NavigationDirectionsSkill` confirma contra él cuando el usuario dice "sí" o "guíame".
4. **Memoria y contexto histórico:** La memoria de ubicaciones pasadas (lugares visitados, contexto de conversaciones) vive en `FifoDataRepository` y el pipeline de enriquecimiento del backend — completamente separado de esta capa.
