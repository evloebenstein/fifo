# 🛎️ Background Services (`com.fifo.voicepipeline.service`)

Subpaquete de servicios del sistema Android para mantener a Fifo activo en segundo plano con la pantalla apagada y permitir control manos libres del dispositivo.

---

## 🗺️ Mapa de Archivos del Directorio

| Archivo | Rol y Responsabilidad |
| :--- | :--- |
| [`FifoVoiceService.kt`](FifoVoiceService.kt) | **Servicio en Primer Plano (`ForegroundService`)**: Crea una notificación persistente con tipos `microphone` y `connectedDevice`. Mantiene vivo el hilo de corrutinas del `VoicePipelineManager`, la conexión Bluetooth con el robot ESP32 y el reconocedor de voz nativo incluso si el usuario bloquea el teléfono o cambia a otra aplicación. |
| [`FifoAccessibilityService.kt`](FifoAccessibilityService.kt) | **Servicio de Accesibilidad (`AccessibilityService`)**: Permite a Fifo interactuar con interfaces del sistema cuando la API estándar no lo permite, como pulsar automáticamente el botón de "Contestar" o "Colgar" en la pantalla de llamada telefónica cuando el usuario lo ordena por voz. |

---

## 🧭 Reglas de Programación en `service/`
1. **Canal de Notificación:** La notificación en primer plano en `FifoVoiceService` debe tener prioridad baja/media para no molestar auditivamente al usuario pero evitar que el sistema operativo mate el proceso por optimización de batería.
2. **Ciclo de Vida:** Si el usuario destruye la app desde la multitarea o presiona salir, el servicio debe liberar `AudioRecord`, Bluetooth LE y `TextToSpeech` limpiamente en `onDestroy()`.
