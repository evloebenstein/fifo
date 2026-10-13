# 📍 Location & Navigation Subsystem (`com.fifo.voicepipeline.location`)

Subpaquete para geolocalización en tiempo real, brújula digital y guiado de navegación peatonal o vehicular adaptado para adultos mayores.

---

## 🗺️ Mapa de Archivos del Directorio

| Archivo | Rol y Responsabilidad |
| :--- | :--- |
| [`FifoLocationHelper.kt`](FifoLocationHelper.kt) | **Manejador de GPS y Geocodificación**: Consulta la ubicación actual mediante `FusedLocationProviderClient`, realiza geocodificación inversa (convierte lat/long en dirección legible en español: calle, número y comuna) y mantiene el estado reactivo de la posición actual del usuario. |
| [`FifoNavigationManager.kt`](FifoNavigationManager.kt) | **Guía de Navegación Paso a Paso (Estilo Waze)**: Gestiona rutas de navegación activas, calcula distancias restantes, detecta giros próximos y emite instrucciones verbales autónomas mediante `VoicePipelineManager.speakAutonomousMessage()` (ej: *"En 50 metros gire a la derecha por calle Los Alerces"*). |
| [`FifoOrientationHelper.kt`](FifoOrientationHelper.kt) | **Sensor de Orientación y Brújula**: Lee el acelerómetro y magnetómetro del teléfono mediante `SensorManager` para calcular el azimut o rumbo en tiempo real y guiar hacia dónde mirar o caminar. |

---

## 🧭 Reglas de Programación en `location/`
1. **Consumo de Batería:** Detener las actualizaciones de GPS continuas cuando no haya una ruta activa para preservar la batería del teléfono.
2. **Claridad del Mensaje:** Las indicaciones habladas deben ser cortas, claras y con referencias amigables sin jerga técnica.
