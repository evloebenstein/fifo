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
| [`VoicePipelineManager.kt`](VoicePipelineManager.kt) | **Fachada única para `MainActivity`/`FifoVoiceService`.** En `SESSION` espeja los `StateFlow` y delega en `VoiceSessionPipeline`; en `LEGACY` conserva el camino anterior. No crea una segunda sesión, VAD, TTS ni manager de telefonía. Comparte `UtterancePolicy` con la sesión y restaura la ruta persistida en `start()` (ADR-0041, ADR-0042). |
| [`NavigationNoticeQueue.kt`](NavigationNoticeQueue.kt) | Cola pura-JVM de avisos autónomos: un solo drenaje activo, el aviso más reciente reemplaza a los anteriores y `poll()` vacío libera la tanda (ADR-0041). |
| [`NavigationNoticeDispatcher.kt`](NavigationNoticeDispatcher.kt) | **Módulo profundo de locución autónoma (ADR-0041).** Encadena cola, espera sin plazo, descarte por destino y narración con fallback. Recibe espera/ruta/narrador/habla como costuras y se prueba en JVM con `runTest`. |
| [`session/VoiceSessionPipeline.kt`](session/VoiceSessionPipeline.kt) | **Raíz de composición del camino nuevo.** Es el único lugar que construye `VoiceSession`, los adaptadores de audio, `AndroidTelephonyPort` y `CallerContextCoordinator`, además de `MemoryCoordinator` + `SqliteConversationJournal` + `RepositorySessionMemoryConsolidator`. |
| [`session/VoiceSession.kt`](session/VoiceSession.kt) | **Sesión conversacional profunda.** Recibe tramas, usa el RMS solo para boca/OLED, transcribe, decide barge-in/eco/acciones de llamada, journaliza antes de hablar y pide consolidación al dormir. |
| [`session/TurnOrchestrator.kt`](session/TurnOrchestrator.kt) | Máquina de estados del **ConversationalTurn**: wake-word, ventana de follow-up (~14 s), barge-in por contenido y auto-sueño (~25 s, o ~120 s en modo continuo). Delega las reglas léxicas en `UtterancePolicy`. |
| [`session/UtterancePolicy.kt`](session/UtterancePolicy.kt) | **Única autoridad léxica (ADR-0042).** Política pura de invocación, consulta, saludo, silencio, sueño, eco, pregunta y control de llamada (`CallAction`). La comparten `TurnOrchestrator` y el facade legacy; se prueba en JVM con `UtterancePolicyTest`. |
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

## Interpretación de la locución (ADR-0042)

`UtterancePolicy` es la única autoridad léxica del producto y la comparten los dos
caminos:

- invocación ("Fifo" y variantes fonéticas, sin aliases que despiertan por error);
- extracción de la consulta y detección de saludo puro;
- silencio, sueño, eco, pregunta y `CallAction` (`ANSWER`, `HANGUP`,
  `CALLER_STATUS`).

`TurnOrchestrator` solo aporta el estado conversacional (frase hablada, follow-up,
barge-in) y `VoicePipelineManager` delega en las mismas funciones. La política
clasifica; no redacta ni locuta.

---

## Guía autónoma de navegación (ADR-0040 y ADR-0041)

`FifoNavigationManager` pertenece al registro de skills y publica
`NavigationNotice` estructurados. `VoicePipelineManager` solo entrega el hecho a
`NavigationNoticeDispatcher`, que:

1. encola el aviso en `NavigationNoticeQueue`: el más reciente reemplaza a los
   anteriores;
2. espera, **sin plazo**, a que el pipeline no esté en `PROCESSING` ni
   `SPEAKING` (observa el `StateFlow`, sin sondeo);
3. descarta solo los avisos de un destino que ya no está activo (`ARRIVED` se
   anuncia aunque la ruta se haya cerrado);
4. pide la locución a `CloudApiClient.narrateNavigationNotice()` y usa
   `NavigationNotice.fallbackSpeech()` solo si el LLM no responde;
5. locuta por `speakNavigation()`: `session.speakResponse()` en `SESSION` o
   `speakAutonomousMessage()` en legacy.

El motor de navegación no redacta frases y la sesión de ruta no vive en
`VoiceSession`: encargos, pausas y correcciones están en
`NavigationSessionMemory`, que se persiste con `NavigationSessionStore` y se
restaura en `start()` (ADR-0041).

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
11. **Una sola política léxica:** `UtterancePolicy` (ADR-0042) decide invocación, silencio, sueño, eco, pregunta y control de llamada. `TurnOrchestrator` y el facade delegan; no agregues listas o regex en ninguno de los dos.
