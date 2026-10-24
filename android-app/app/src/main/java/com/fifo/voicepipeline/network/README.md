# 🌐 Network Subsystem (`com.fifo.voicepipeline.network`)

Subsistema de conectividad (BLE, WebSocket, hotspot) e inteligencia artificial en la
nube para el camino de voz.

---

## Módulos

| Archivo | Rol |
| :--- | :--- |
| [`CloudApiClient.kt`](CloudApiClient.kt) | **Cognición en la nube** (ADR-0006). Implementa `SpeechCognitionAdapter`: Groq Whisper STT (`whisper-large-v3-turbo`), Groq LLM (`qwen/qwen3.8-27b`) con tool-calling, detección de alucinaciones, contexto conversacional y `generateRepairTurn()`. También expone `extractSessionMemoryChecked()` para la consolidación durable. |
| [`FifoBackendClient.kt`](FifoBackendClient.kt) | **Fachada HTTP del backend propio** (ADR-0029). Conserva la API pública, los tipos `SyncedUserBundle`/`AuthResult`/`UserProfileSummary` y la `baseUrl`; delega en adaptadores por dominio. |
| [`BackendHttp.kt`](BackendHttp.kt) | Transporte compartido: `OkHttpClient`, timeouts, `Content-Type` JSON, `baseUrl` USB/emulador y helper `postOrPatchJson`. |
| [`BackendSyncAdapter.kt`](BackendSyncAdapter.kt) | Salud del backend y descarga/parseo del bundle completo. |
| [`BackendAccountAdapter.kt`](BackendAccountAdapter.kt) | Login, Google, alta de perfil, listado de usuarios, demografía, bio e intereses. |
| [`BackendContentAdapter.kt`](BackendContentAdapter.kt) | Gustos, historias, posts, recuerdos, recordatorios, conversaciones y rutinas. |
| [`BackendMemoryAdapter.kt`](BackendMemoryAdapter.kt) | Consolidación de sesión (`analyze-and-consolidate`) y GraphRAG (`recall-context`). |
| [`BackendContactAdapter.kt`](BackendContactAdapter.kt) | Contactos relacionales y telefonía contextual (`/identify-contact`, `/log-call-event`). |
| [`BackendDeviceAdapter.kt`](BackendDeviceAdapter.kt) | Ubicación del dispositivo y navegación (`/contextual-intent`). |
| [`BackendCommunityAdapter.kt`](BackendCommunityAdapter.kt) | Recomendaciones de amigos y actividades comunitarias. |
| [`BleVoiceClient.kt`](BleVoiceClient.kt) | Cliente GATT BLE: conexión, streaming PCM del ESP32, OLED y reconexión con backoff. |
| [`BleProvisioner.kt`](BleProvisioner.kt) | Aprovisionamiento Wi-Fi sobre BLE para el setup inicial. |
| [`HotspotManager.kt`](HotspotManager.kt) | Punto de acceso Wi-Fi del teléfono para el ESP32. |
| [`WsServer.kt`](WsServer.kt) | Servidor WebSocket local para streaming PCM de baja latencia. |

---

## Memoria y contexto

`CloudApiClient` mantiene un `conversationHistory` en RAM de **8 mensajes** (4 turnos)
que usa como caché de contexto del LLM. **No es la fuente de verdad durable.**

- `VoiceSessionPipeline` limpia el historial al arrancar y lo repuebla desde
  `ConversationJournal` (los últimos 4 turnos).
- Cada turno se journaliza en SQLite antes de hablar.
- La consolidación usa `extractSessionMemoryChecked()`:
  - una excepción de red/parseo **se propaga** y `MemoryCoordinator` reintenta con backoff;
  - `null` significa “no había nada significativo que recordar” y permite marcar el turno como consolidado;
  - un resultado válido se persiste en `FifoDataRepository` y en el backend.
- Si un turno fue reparado, la consolidación usa `repairText` en lugar de la respuesta errónea original.

---

## Reglas

1. **Groq es el cerebro de producción:** `CloudApiClient` usa Groq para STT y LLM. No agregues un segundo proveedor de cognición al camino `SESSION` sin un ADR.
2. **Desacoplamiento de contexto:** usa `ConversationContextProvider` para inyectar contexto ambiental/temporal sin acoplar pruebas a Android.
3. **Reconexión BLE:** `BleVoiceClient` reintenta con backoff exponencial. No dupliques lógica de reconexión en otros módulos.
4. **Claves de API:** se leen de `SharedPreferences`/`BuildConfig` a través de la fachada. Nunca hardcodear claves.
5. **El diario es la verdad:** no uses `conversationHistory` como almacenamiento durable ni muevas la escritura del diario después del TTS.
6. **Errores recuperables:** la extracción de memoria debe distinguir fallo (reintento) de ausencia de memoria (`null`). No descartes turnos por un corte de red.
7. **El backend propio se delega por dominio:** `FifoBackendClient` es una fachada; el transporte vive en `BackendHttp` y cada endpoint en su adaptador. No agregues llamadas HTTP directas a la fachada ni crees un segundo cliente.
