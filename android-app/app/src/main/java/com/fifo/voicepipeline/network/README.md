# 🌐 Network Subsystem (`com.fifo.voicepipeline.network`)

Subsistema de conectividad de hardware (BLE, WebSocket, Hotspot) e inteligencia artificial en la nube.

---

## Módulos

| Archivo | Rol |
| :--- | :--- |
| [`CloudApiClient.kt`](CloudApiClient.kt) | **Cognición en la nube**: Implementa `SpeechCognitionAdapter` (ADR-0006). Maneja Groq Whisper STT, Groq LPU (Qwen 27B) con tool-calling para skills, detección de alucinaciones context-aware y enriquecimiento dinámico vía `ConversationContextProvider`. |
| [`FifoBackendClient.kt`](FifoBackendClient.kt) | Cliente HTTP del backend propio (FastAPI/MySQL): perfiles, knowledge graph, GraphRAG recall, telefonía contextual (`/identify-contact`, `/log-call-event`), intención de navegación (`/contextual-intent`). |
| [`BleVoiceClient.kt`](BleVoiceClient.kt) | Cliente GATT BLE: conexión, streaming de audio PCM del ESP32, actualización de pantalla OLED, reconexión automática con backoff. |
| [`BleProvisioner.kt`](BleProvisioner.kt) | Aprovisionamiento Wi-Fi sobre BLE: envía SSID/contraseña al ESP32 en el setup inicial. |
| [`HotspotManager.kt`](HotspotManager.kt) | Punto de acceso Wi-Fi del teléfono para que el ESP32 se conecte sin router externo. |
| [`WsServer.kt`](WsServer.kt) | Servidor WebSocket local para streaming PCM de baja latencia (<50ms). |

---

## Reglas

1. **100% Groq LPU:** `CloudApiClient` opera exclusivamente sobre Groq LPU (Qwen 27B) y Groq Whisper turbo. Todo código residual de Claude ha sido purgado (ADR-0006).
2. **Desacoplamiento de contexto:** Utiliza `ConversationContextProvider` para inyectar contexto ambiental y temporal sin acoplamiento estático directo en pruebas.
3. **Reconexión BLE:** `BleVoiceClient` reintenta con backoff exponencial. No agregar lógica de reconexión en otros módulos.
4. **Claves de API:** Siempre leer desde `SharedPreferences` vía `VoicePipelineManager`. Nunca hardcodear.
