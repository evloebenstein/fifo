# 📞 Telephony Subsystem (`com.fifo.voicepipeline.telephony`)

Subpaquete para asistencia por voz durante llamadas telefónicas convencionales del teléfono móvil.

---

## 🗺️ Mapa de Archivos del Directorio

| Archivo | Rol y Responsabilidad |
| :--- | :--- |
| [`FifoCallManager.kt`](FifoCallManager.kt) | **Gestor de Llamadas en Tiempo Real**: Detecta llamadas entrantes y salientes, consulta el nombre del contacto en la agenda, anuncia en voz alta quién llama (*"Llamada entrante de Carmen (Hija)... ¿Desea que conteste?"*), y ejecuta acciones por comando de voz (*"Fifo contesta"*, *"Fifo cuelga"*). |
| [`FifoPhoneCallReceiver.kt`](FifoPhoneCallReceiver.kt) | **Receptor de Eventos Telefónicos (`BroadcastReceiver`)**: Escucha cambios de estado del hardware telefónico (`EXTRA_STATE_RINGING`, `EXTRA_STATE_OFFHOOK`, `EXTRA_STATE_IDLE`) y notifica al `FifoCallManager` para iniciar la mediación de voz. |

---

## 🧭 Reglas de Programación en `telephony/`
1. **Prioridad de Audio:** Durante una llamada telefónica activa, el modo de audio debe configurarse con cuidado (`MODE_IN_COMMUNICATION`) para no interrumpir el canal celular del operador ni causar acople con el altavoz.
2. **Permisos Sensibles:** Las operaciones de contestar y colgar requieren permisos de sistema específicos (`ANSWER_PHONE_CALLS` o el servicio de accesibilidad como respaldo).
