# 🧠 Fifo Kotlin Architecture Map (`com.fifo.voicepipeline`)

Raíz del código fuente en Kotlin de la aplicación Android de **FIFO**.

---

## 🗺️ Mapa de Archivos y Subpaquetes

| Archivo / Subpaquete | Tipo | Rol Principal |
| :--- | :--- | :--- |
| [`MainActivity.kt`](MainActivity.kt) | Actividad Principal | Punto de entrada UI en Android (`ComponentActivity`). Inicializa `FifoDataRepository`, enlaza el servicio en primer plano `FifoVoiceService`, gestiona permisos en tiempo de ejecución (Micrófono, BLE, Teléfono, Ubicación, Notificaciones) y renderiza la interfaz Jetpack Compose mediante `MainScreen`. |
| [`audio/`](audio/README.md) | Subpaquete | Procesamiento de bajo nivel de señales de audio: VAD (Voice Activity Detection), buffers circulares PCM, reconocimiento nativo de Google y síntesis TTS. |
| [`data/`](data/README.md) | Subpaquete | Repositorio central de datos reactivo (`FifoDataRepository`), base de datos local SQLite (`local/FifoDatabaseHelper.kt`) y cliente REST móvil (`remote/FifoApiClient.kt`). |
| [`location/`](location/README.md) | Subpaquete | Asistente de ubicación GPS en tiempo real, brújula digital, geocodificación y navegación paso a paso estilo Waze para personas mayores. |
| [`network/`](network/README.md) | Subpaquete | Clientes de red: Bluetooth Low Energy (`BleVoiceClient`), cliente de IA en la nube (`CloudApiClient`), sincronización con backend (`FifoBackendClient`) y aprovisionamiento Wi-Fi. |
| [`pipeline/`](pipeline/README.md) | Subpaquete | **Núcleo del asistente de voz**: `VoicePipelineManager.kt` orquesta la máquina de estados, detección de palabra de activación ("Fifo"), preguntas con follow-up fluido, cancelación de eco y barge-in. |
| [`service/`](service/README.md) | Subpaquete | Servicios en segundo plano: `FifoVoiceService` (mantiene a Fifo vivo y escuchando con la pantalla apagada) y `FifoAccessibilityService` (manos libres en llamadas). |
| [`skills/`](skills/README.md) | Subpaquete | Catálogo de herramientas y habilidades ejecutables por la IA: recordatorios, llamadas, agenda, navegación GPS, búsqueda web y base de datos relacional. |
| [`telephony/`](telephony/README.md) | Subpaquete | Gestión de llamadas telefónicas entrantes/salientes y contestador inteligente con Fifo. |
| [`ui/`](ui/README.md) | Subpaquete | Componentes visuales en Jetpack Compose: pantallas principales, barra de navegación, diálogos flotantes y caritas animadas. |

---

## 🧭 Diagrama de Flujo Arquitectónico

```
                      +-------------------+
                      |   MainActivity    |
                      +---------+---------+
                                │ Inicializa
                                ▼
                      +-------------------+
                      | FifoVoiceService  |
                      +---------+---------+
                                │ Instancia y sostiene
                                ▼
                   +------------------------+
                   |  VoicePipelineManager  | ◄─── Núcleo de Voz
                   +---+--------+-------+---+
                       │        │       │
       ┌───────────────┘        │       └───────────────┐
       ▼                        ▼                       ▼
  +---------+             +-----------+           +-----------+
  |  audio/ |             | network/  |           |  skills/  |
  |  - VAD  |             | - Claude  |           | - Llamadas|
  |  - Mic  |             | - BLE     |           | - Recuerdos|
  |  - TTS  |             | - Backend |           | - Rutas   |
  +---------+             +-----+-----+           +-----+-----+
                                │                       │
                                ▼                       ▼
                         +-------------+         +-------------+
                         |    data/    |         |  telephony/ |
                         | - SQLite    |         | - Monitor   |
                         | - Repositorio|        | - Control   |
                         +-------------+         +-------------+
```
