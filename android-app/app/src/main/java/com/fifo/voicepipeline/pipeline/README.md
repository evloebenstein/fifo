# ⚙️ Voice Pipeline Core (`com.fifo.voicepipeline.pipeline`)

Núcleo de la arquitectura de voz de Fifo. Ver **ADR-0001** (`docs/adr/0001-deep-voice-session-architecture.md`) para el diseño completo.

---

## Módulos

| Archivo / Carpeta | Rol |
| :--- | :--- |
| [`PipelineState.kt`](PipelineState.kt) | `enum class PipelineState`: `DISCONNECTED · SLEEPING · IDLE · LISTENING · PROCESSING · SPEAKING · ERROR` |
| [`VoicePipelineManager.kt`](VoicePipelineManager.kt) | **Fachada de delegación** (~120 líneas). Expone los `StateFlow` observables hacia la UI y delega toda la lógica conversacional al módulo profundo `VoiceSession`. Retiene la lógica de telefonía (`FifoCallManager`, `ensureCallListeningActive`) que por invariante arquitectónico no puede moverse (ver ADR-0005). |
| [`session/VoiceSession.kt`](session/VoiceSession.kt) | **Módulo profundo de sesión conversacional**. Interfaz pequeña que oculta: PreRollBuffer, AudioInboundFilter, SpeechOutboundEngine, TurnOrchestrator y los adaptadores de hardware. |
| [`session/TurnOrchestrator.kt`](session/TurnOrchestrator.kt) | Máquina de estados del **ConversationalTurn**: wake-word gating, ventana de follow-up (14s), barge-in con resolución contextual, auto-sueño (25s / 120s modo continuo). |
| [`session/AudioSeam.kt`](session/AudioSeam.kt) | Costura de entrada/salida de audio PCM. Produce `AudioInboundAdapter` y `AudioOutboundAdapter`. |
| [`session/AndroidAudioHardwareAdapter.kt`](session/AndroidAudioHardwareAdapter.kt) | Adaptador de hardware: TTS del teléfono (producción). |
| [`session/AndroidAudioHardwareInboundAdapter.kt`](session/AndroidAudioHardwareInboundAdapter.kt) | Adaptador de entrada: multiplexor de micrófono del teléfono y audio BLE del ESP32. |
| [`session/SpeechCognitionSeam.kt`](session/SpeechCognitionSeam.kt) | Costura de cognición (ADR-0006): STT Whisper + LLM Groq (Qwen 27B) con tool-calling, `ConversationContextProvider` desacoplado e `InMemorySpeechCognitionAdapter` con simulación de ejecución de skills en JVM. |
| [`session/SpeechOutboundEngine.kt`](session/SpeechOutboundEngine.kt) | Motor de salida de voz: sanitización y despacho al adaptador de hardware. |
| [`session/AudioInboundFilter.kt`](session/AudioInboundFilter.kt) | Filtro anti-eco y blindaje contra audio del propio altavoz. |

---

## Flujo del ConversationalTurn

```
SLEEPING ──[WakeWordTrigger]──► LISTENING ──► PROCESSING ──► SPEAKING
                                    ▲                              │
                                    │   follow-up (14s si ?)       │
                                    └──────────────────────────────┘
                                    │   auto-sleep (25s inactividad)
                                    └──────────────────────────────► SLEEPING
```

---

## Invariantes (no modificar sin leer los ADRs)

1. **Wake-word estricto:** Ningún audio se procesa sin `isWakeWord()` a menos que `expectFollowUp` esté activo o el modo continuo esté habilitado.
2. **Anti-eco:** Las respuestas en modo follow-up nunca se descartan como eco salvo que sean repetición idéntica de lo que Fifo acabó de decir.
3. **Telefonía:** `FifoCallManager` y `ensureCallListeningActive()` permanecen en `VoicePipelineManager`. No mover. Ver ADR-0005.
4. **Memoria de sesión:** Al ejecutar `goToSleep()`, los turnos de `activeSessionTurns` se consolidan en SQLite + backend.
