# 🛎️ Background Services (`com.fifo.voicepipeline.service`)

Subpaquete de servicios del sistema Android para mantener a Fifo activo en segundo
plano y permitir control manos libres del dispositivo.

---

## 🗺️ Mapa de Archivos del Directorio

| Archivo | Rol y Responsabilidad |
| :--- | :--- |
| [`FifoVoiceService.kt`](FifoVoiceService.kt) | **Servicio en Primer Plano (`ForegroundService`)**: notificación persistente con tipos `microphone` y `connectedDevice`. Mantiene vivo el `VoicePipelineManager` (y por tanto el `VoiceSessionPipeline` en release), la conexión BLE con el ESP32 y el ciclo de vida del asistente aunque el usuario bloquee el teléfono. |
| [`FifoAccessibilityService.kt`](FifoAccessibilityService.kt) | **Servicio de Accesibilidad (`AccessibilityService`)**: respaldo para pulsar “Contestar” o “Colgar” en la pantalla de llamada cuando la API estándar no lo permite. |

---

## 🧭 Reglas de Programación en `service/`

1. **Canal de notificación:** prioridad baja/media para no molestar auditivamente, pero suficiente para evitar que el sistema mate el proceso por optimización de batería.
2. **Ciclo de vida:** al destruir el servicio, liberar el audio, Bluetooth LE, TTS y la memoria durable de forma limpia en `onDestroy()`. El cierre de la pipeline debe detener la captura y el bucle de reintentos.
3. **Un solo dueño de hardware:** el servicio no llama a `PhoneMicRecorder` ni al reconocedor nativo. La captura pertenece a `CaptureArbiter`; el servicio solo sostiene la fachada.
4. **Solo un pipeline:** el servicio observa `VoicePipelineManager`; no construye `VoiceSession`, `VoiceSessionPipeline` ni managers de telefonía paralelos.
5. **Memoria antes de dormir:** al detener la sesión, el pipeline drena la cola pendiente y conserva el diario SQLite para el próximo arranque.
