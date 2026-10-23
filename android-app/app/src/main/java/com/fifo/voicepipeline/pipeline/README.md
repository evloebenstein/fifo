# ⚙️ Voice Pipeline Core (`com.fifo.voicepipeline.pipeline`)

Núcleo de la arquitectura de voz de Fifo. La decisión vigente está en **ADR-0010**
(`docs/adr/0010-voice-session-pipeline-flag.md`) y **ADR-0011**
(`docs/adr/0011-durable-memory-journal.md`).

El paquete contiene **dos caminos excluyentes**. Solo se construye uno por proceso:

| Camino | Cuándo se usa | Qué lo compone |
| :--- | :--- | :--- |
| `SESSION` | Release con `VOICE_SESSION_ENABLED=true` **y** STT de nube configurado | `VoiceSessionPipeline` + `VoiceSession` + `CaptureArbiter` + `TelephonyPort` + memoria durable |
| `LEGACY` | Debug, o release sin claves de STT | El camino anterior dentro de `VoicePipelineManager` |

`VoicePipelineModeSelector.select(flagEnabled, hasCloudStt)` es la única autoridad de
selección. Si falta STT de nube, cae a `LEGACY` para que Fifo no quede sordo. El flag
se elimina junto con el legacy en la release siguiente.

---

## Módulos

| Archivo / Carpeta | Rol |
| :--- | :--- |
| [`PipelineState.kt`](PipelineState.kt) | `PipelineState`: `DISCONNECTED · SLEEPING · IDLE · LISTENING · PROCESSING · SPEAKING · ERROR` y `MicSource`: `ESP32 · PHONE`. |
| [`VoicePipelineMode.kt`](VoicePipelineMode.kt) | `VoicePipelineMode` (`LEGACY`/`SESSION`) y el selector puro que evalúa el flag y el STT de nube. |
| [`VoicePipelineManager.kt`](VoicePipelineManager.kt) | **Fachada única para `MainActivity`/`FifoVoiceService`.** En `SESSION` espeja los `StateFlow` y delega en `VoiceSessionPipeline`; en `LEGACY` conserva el camino anterior. No crea una segunda sesión, VAD, TTS ni manager de telefonía. |
| [`session/VoiceSessionPipeline.kt`](session/VoiceSessionPipeline.kt) | **Raíz de composición del camino nuevo.** Es el único lugar que construye `VoiceSession`, los adaptadores de audio, `AndroidTelephonyPort` y `CallerContextCoordinator`, además de `MemoryCoordinator` + `SqliteConversationJournal` + `RepositorySessionMemoryConsolidator`. |
| [`session/VoiceSession.kt`](session/VoiceSession.kt) | **Sesión conversacional profunda.** Recibe tramas, usa el RMS solo para boca/OLED, transcribe, decide barge-in/eco/acciones de llamada, journaliza antes de hablar y pide consolidación al dormir. |
| [`session/TurnOrchestrator.kt`](session/TurnOrchestrator.kt) | Máquina de estados del **ConversationalTurn**: wake-word, ventana de follow-up (~14 s), barge-in por contenido y auto-sueño (~25 s, o ~120 s en modo continuo). |
| [`session/SpeechSegmenter.kt`](session/SpeechSegmenter.kt) | **Dueño único del VAD, el pre-roll (~480 ms) y el WAV** en el camino nuevo. No conoce Android, TTS, UI ni telefonía. |
| [`session/CaptureArbiter.kt`](session/CaptureArbiter.kt) | **Dueño único de la ruta de captura.** Decide `STOPPED`, `PHONE_NORMAL`, `PHONE_CALL`, `BLE_MIC` o `NATIVE_RECOGNIZER`; solo reconfigura hardware cuando la ruta efectiva cambia y descarta tramas tardías. |
| [`session/AndroidCaptureHardware.kt`](session/AndroidCaptureHardware.kt) | Implementación Android de `CaptureHardware`: envuelve `PhoneMicRecorder` y, cuando corresponde, el reconocedor nativo. |
| [`session/AudioSeam.kt`](session/AudioSeam.kt) | Costuras `AudioInboundAdapter` / `AudioOutboundAdapter` para desacoplar PCM de la máquina de estados. |
| [`session/AndroidAudioHardwareInboundAdapter.kt`](session/AndroidAudioHardwareInboundAdapter.kt) | Único adaptador de **entrada** de producción. Traduce eventos de micrófono/BLE/llamada a `CaptureEnvironment` y publica lo que decide el árbitro. |
| [`session/AndroidAudioHardwareAdapter.kt`](session/AndroidAudioHardwareAdapter.kt) | Adaptador de **salida** únicamente: Android TTS. No captura audio, no toca `AudioManager` y no enruta llamadas. |
| [`session/SpeechCognitionSeam.kt`](session/SpeechCognitionSeam.kt) | Costura de cognición (ADR-0006): STT Whisper + LLM Groq (`qwen/qwen3.8-27b`) con tool-calling, `ConversationContextProvider`, `generateRepairTurn()` y adaptador JVM de pruebas. |
| [`session/SpeechOutboundEngine.kt`](session/SpeechOutboundEngine.kt) | Sanitización y despacho de la respuesta al adaptador de salida. |
| [`session/MemoryCoordinator.kt`](session/MemoryCoordinator.kt) | Implementación de `SessionMemory`: diario antes de hablar, cola durable `PENDING`, reintentos con backoff (30 s → 15 min) y restauración de contexto. |
| [`session/ConversationRepairPolicy.kt`](session/ConversationRepairPolicy.kt) | Política pura de reparación ±1 turno: corrección explícita, “No” solo tras `needsRepair` y ventana de ~10 s para locutar. |

`AudioInboundFilter` e `InboundFrameResult` fueron eliminados. No existe un corte de
TTS por RMS.

---

## Selección y composición

```
FifoVoiceService / MainActivity
              │
              ▼
    VoicePipelineManager  (fachada única)
              │
              ▼
 VoicePipelineModeSelector.select(flag, hasCloudStt)
         │                               │
         ▼                               ▼
      LEGACY                          SESSION
 (camino anterior)          VoiceSessionPipeline
                                      ├─ VoiceSession
                                      ├─ AndroidAudioHardwareInboundAdapter
                                      │    └─ CaptureArbiter → PhoneMicRecorder / BLE / nativo
                                      ├─ AndroidAudioHardwareAdapter (TTS)
                                      ├─ AndroidTelephonyPort → FifoCallManager
                                      ├─ CallerContextCoordinator
                                      └─ MemoryCoordinator → SQLite + consolidador
```

`VoicePipelineManager.start()` evalúa la selección después de crear `CloudApiClient`.
En `SESSION` retorna **antes** de inicializar el TTS legacy, `NativeSpeechRecognizer`,
`PhoneMicRecorder` o el VAD del facade. En `LEGACY` no construye nada del camino nuevo.

---

## Flujo del turno en `SESSION`

```
SLEEPING ──[WakeWordTrigger]──► LISTENING ──[fin de frase]──► PROCESSING ──► SPEAKING
    ▲                              │                              │              │
    │                              │ follow-up (~14 s si ?)       │              │
    │                              └──────────────────────────────┴──────────────┘
    │                              │ auto-sueño (~25 s / ~120 s continuo)
    └──────────────────────────────┴──────────────────────────────────────────────
```

1. `CaptureArbiter` entrega tramas al adaptador de entrada.
2. `SpeechSegmenter` aplica VAD, pre-roll y cierra la frase en un WAV.
3. `VoiceSession` transcribe y aplica el escudo de eco.
4. Si hay orden de llamada, se entrega por `onCallAction`; la sesión no contesta ni cuelga.
5. Si es un turno normal, genera la respuesta y **registra el turno en `SessionMemory` antes de `speakResponse`**.
6. Al dormir o cerrar la pipeline, `MemoryCoordinator` drena la cola y consolida.

---

## Invariantes (no modificar sin leer los ADRs)

1. **Un solo pipeline vivo:** `VoicePipelineManager` construye `VoiceSessionPipeline` o inicializa el legacy, nunca ambos.
2. **Un solo dueño de captura:** `CaptureArbiter`. Ni la sesión, ni la UI, ni `MainActivity`, ni `FifoVoiceService` llaman a `PhoneMicRecorder` o al reconocedor nativo.
3. **Un solo dueño de VAD/pre-roll:** `SpeechSegmenter`. El facade no tiene una copia propia.
4. **Una reconfiguración por transición efectiva:** timbrado → activo sigue siendo `PHONE_CALL`; no se vuelve a llamar a `switchToCallMode`.
5. **Nunca `SpeechRecognizer` durante una llamada:** la ruta de llamada es `PHONE_CALL`.
6. **Barge-in por contenido:** una invocación “Fifo …” o una orden de silencio corta TTS; el RMS solo anima boca/OLED.
7. **Eco antes que acción:** el anuncio de timbrado contiene “Fifo contesta”/“Fifo cuelga”; ese eco no puede contestar ni colgar.
8. **Telefonía fuera de la sesión:** `VoiceSession` solo recibe `callState` y entrega `CallAction`; no toca `AudioManager`.
9. **Memoria antes de hablar:** `recordTurn()` ocurre antes de `speakResponse`; la cola pendiente vive en `conversation_journal`, no en RAM.
10. **Un solo dueño de memoria:** `VoiceSessionPipeline` compone el diario y el consolidador; `VoiceSession` solo ve `SessionMemory` y el facade legacy no crea otro coordinador.
