# 🌐 Network & Communication Subsystem (`com.fifo.voicepipeline.network`)

Subpaquete para conectividad de hardware (Bluetooth LE, Hotspot Wi-Fi, WebSockets) y servicios de Inteligencia Artificial en la nube.

---

## 🗺️ Mapa de Archivos del Directorio

| Archivo | Rol y Responsabilidad |
| :--- | :--- |
| [`BleProvisioner.kt`](BleProvisioner.kt) | **Aprovisionamiento Wi-Fi por BLE**: Escanea el robot Fifo por Bluetooth Low Energy y le envía las credenciales Wi-Fi (SSID y contraseña) para que el ESP32 se conecte a internet. |
| [`BleVoiceClient.kt`](BleVoiceClient.kt) | **Cliente GATT Bluetooth Low Energy**: Establece y mantiene la conexión con el ESP32. Recibe tramas de audio del micrófono físico, envía paquetes de voz para el parlante y transmite actualizaciones de estado para la pantalla OLED (estados `ESCUCHANDO`, `HABLANDO`, `DURMIENDO` y texto en pantalla). |
| [`CloudApiClient.kt`](CloudApiClient.kt) | **Cerebro de IA en la Nube (Claude / Groq / OpenAI)**: Gestiona las llamadas al LLM (Anthropic Claude 3.5 Sonnet o Groq LPU), transcripción STT con Whisper, detección de alucinaciones y llamadas a herramientas (Tool Use / Function Calling) con el catálogo de `FifoSkillRegistry`. |
| [`FifoBackendClient.kt`](FifoBackendClient.kt) | **Cliente HTTP del Backend MySQL**: Comunicación con la API REST (`backend/api/main.py`) para perfiles, recuerdos vivos, historias de gustos, recordatorios y amigos. |
| [`HotspotManager.kt`](HotspotManager.kt) | **Gestor de Punto de Acceso Wi-Fi (Tethering)**: Permite al teléfono crear una red Wi-Fi local para que el ESP32 se conecte directamente en caso de no haber router en el hogar. |
| [`WsServer.kt`](WsServer.kt) | **Servidor WebSocket Local**: Servidor WebSocket integrado en el teléfono para streaming de audio PCM con latencia ultrabaja (<50ms) entre el ESP32 y el móvil. |

---

## 🧭 Reglas de Programación en `network/`
1. **Reconexión Automática BLE:** El cliente BLE debe reintentar reconectar con backoff exponencial si el ESP32 se apaga o sale del rango de cobertura.
2. **Fallback entre Motores de IA:** Si Groq o Claude no responden dentro de 20s, debe existir fallback limpio sin colgar la interfaz de usuario.
3. **Manejo de Claves:** Las claves de API se configuran desde la pantalla de perfil (`ProfileScreen`) y se persisten de forma segura en `EncryptedSharedPreferences` / `FifoDataRepository`.
