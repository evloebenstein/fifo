# 📍 Location & Navigation (`com.fifo.voicepipeline.location`)

Subsistema de geolocalización, brújula y copiloto peatonal autónomo. Ver **Candidato 02** del Architecture Review para la propuesta de profundizar este módulo en un `PedestrianCopilotModule`.

---

## Módulos

| Archivo | Rol |
| :--- | :--- |
| [`FifoLocationHelper.kt`](FifoLocationHelper.kt) | GPS + Geocodificación inversa + búsqueda de lugares cercanos (Overpass API). Caché de 45s para no consultar hardware repetidamente. |
| [`FifoNavigationManager.kt`](FifoNavigationManager.kt) | Copiloto peatonal autónomo estilo Waze: monitorea GPS continuo, detecta desvíos, emite alertas de proximidad (100m, 50m, llegada 25m) y detecta detenciones prolongadas. Mantiene `activeRoute` y `pendingDestination`. |
| [`FifoOrientationHelper.kt`](FifoOrientationHelper.kt) | Sensor de orientación (acelerómetro + magnetómetro) para calcular azimut y dar instrucciones relativas al cuerpo ("gire a su derecha"). |

---

## Reglas

1. **GPS solo cuando hay ruta activa:** Detener actualizaciones continuas cuando `FifoNavigationManager.activeRoute == null`.
2. **Sin hardcodes de lugar:** Ningún nombre de local, dirección, ni POI puede estar hardcodeado. Todo viene de Overpass API o del backend.
3. **`pendingDestination`:** El estado de confirmación de destino vive en `FifoNavigationManager.pendingDestination`. `NavigationDirectionsSkill` confirma contra él con `destination='confirm_pending'`.
