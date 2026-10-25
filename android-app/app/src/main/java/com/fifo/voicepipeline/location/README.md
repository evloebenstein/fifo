# location/

Motor de localización, orientación y navegación autónoma de Fifo.

## Archivos

| Archivo | Responsabilidad |
|---|---|
| [`LocationAdapter.kt`](LocationAdapter.kt) | **GeoIntentSeam** — interfaz que aísla todo acceso a hardware GPS, geocodificación, búsqueda de POI e intents de mapa. Única frontera autorizada para operaciones de localización. |
| [`AndroidLocationAdapter.kt`](AndroidLocationAdapter.kt) | Implementación de producción de `LocationAdapter`. Delega en `FifoLocationHelper` y mantiene el `Context` confinado a esta capa. |
| [`InMemoryLocationAdapter.kt`](InMemoryLocationAdapter.kt) | Doble de prueba puro-JVM de `LocationAdapter`. Sin dependencias Android — permite tests unitarios de skills de navegación sin emulador ni Robolectric. |
| [`FifoLocationHelper.kt`](FifoLocationHelper.kt) | Utilidad GPS de bajo nivel: obtención de ubicación fresca, geocodificación inversa, búsqueda Nominatim de POI cercanos, lanzamiento de intents Maps/Waze. No debe ser llamado directamente desde skills; usar `LocationAdapter`. |
| [`FifoNavigationManager.kt`](FifoNavigationManager.kt) | Copiloto peatonal autónomo estilo Waze: monitorea GPS continuo (vía `locationManagerProvider`), detecta desvíos, emite alertas de proximidad (100 m, 50 m, llegada 25 m) y detecta detenciones prolongadas. Ya no es un `object` singleton — se inyecta como clase. |
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

## Notas importantes

1. **GPS solo cuando hay ruta activa:** El listener continuo se inicia en `startNavigation()` y se detiene en `stopNavigation()` / llegada a destino.
2. **`FifoLocationHelper` NO cambia:** toda la lógica de resolución de ubicación, geocodificación y Nominatim sigue igual; `AndroidLocationAdapter` es solo un wrapper de compatibilidad.
3. **`pendingDestination`:** El estado de confirmación vive en `FifoNavigationManager.pendingDestination`. `NavigationDirectionsSkill` confirma contra él cuando el usuario dice "sí" o "guíame".
4. **Memoria y contexto histórico:** La memoria de ubicaciones pasadas (lugares visitados, contexto de conversaciones) vive en `FifoDataRepository` y el pipeline de enriquecimiento del backend — completamente separado de esta capa.
