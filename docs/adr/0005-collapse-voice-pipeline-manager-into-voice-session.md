# 0005-collapse-voice-pipeline-manager-into-voice-session

## Context and Decision

`VoicePipelineManager` was the original monolithic orchestrator (~2,100 lines) that handled audio hardware capture, Bluetooth LE packet routing, Porcupine wake-word detection, Groq Whisper STT, LLM turn processing, TTS output, barge-in resumption, follow-up windows, auto-sleep timers, and the telephony Alexa-style call listener. In parallel, ADR-0001 introduced a deep `VoiceSession` module with two decoupled seams (`AudioSeam`, `SpeechCognitionSeam`) and a fully functional `TurnOrchestrator` implementing the same conversational machine — but VoicePipelineManager continued to duplicate that state rather than delegate to it.

We decided to collapse `VoicePipelineManager` into a thin delegation facade (~120 lines) over the deep `VoiceSession` module, following the same pattern established for `FifoDataRepository` (ADR-0002) and `FifoSkillRegistry` (ADR-0003).

### What moved into VoiceSession / TurnOrchestrator

- Follow-up window management (`expectFollowUp`, `startFollowUpWindow()`, 14s timer)
- Barge-in interruption and resolution (`onBargeInTriggered()`, `isWaitingForBargeInResolution`)
- Auto-sleep timer (25s normal, 120s in continuous-listening mode)
- Wake-word gating (`isWakeWord()`, `extractQuery()`)
- Silence and sleep command detection (`isSilenceCommand()`, `isSleepCommand()`)
- State machine transitions (SLEEPING → LISTENING → PROCESSING → SPEAKING → IDLE)

### What stays in VoicePipelineManager (the telephony invariant)

The telephony subsystem (`FifoCallManager`, `FifoPhoneCallReceiver`, `ensureCallListeningActive()`, `switchToCallMode()`, `onCallEnded()`) **remains exclusively in `VoicePipelineManager`**. This is an explicit architectural invariant because:

1. Past revisions experienced severe telephony regressions (broken concurrent audio capture in calls and videocalls, `ERROR_AUDIO` from Android's `SpeechRecognizer`) when telephony state was moved across module boundaries.
2. The call listening mode requires VOICE_RECOGNITION audio source concurrent sharing with the system telephony app — this is hardware-level Android audio policy that is difficult to test in-memory and must remain close to the Android `PhoneMicRecorder`.
3. `FifoDataRepository` already holds the `incomingCall` StateFlow as the canonical telephony source of truth (ADR-0002 telephony invariant).

A future `TelephonySeam` can be introduced as a separate ADR only after the VoiceSession collapse is stable and the call subsystem has integration test coverage.

### Hardware audio capture (AudioSeam)

Audio capture (ESP32 BLE packets, `AudioRecord` phone mic, `PhoneMicRecorder` call mode switching) remains in `AndroidAudioHardwareInboundAdapter` behind `AudioSeam`. `VoicePipelineManager` routes incoming PCM to `VoiceSession` via this adapter.

### MicSource switching

`MicSource.PHONE` / `MicSource.ESP32` switching and the `nativeRecognizer` / `phoneMicRecorder` selection logic remain in `VoicePipelineManager` as it directly manages Android hardware resources (`AudioRecord`, `SpeechRecognizer`).

## Consequences

- `VoicePipelineManager` is now a ~120-line backward-compatible delegation facade. All callers (`MainActivity`, `FifoVoiceService`) continue working without changes.
- The complete `ConversationalTurn` (WakeWordTrigger → user utterance → Groq response → TTS → follow-up window → sleep) is verifiable in pure JVM unit tests in `src/test/` without Android emulators.
- Telephony regressions are prevented by keeping the telephony subsystem isolated in VoicePipelineManager until a proper `TelephonySeam` is designed.
- `TurnOrchestrator` is now the single authoritative place for conversational state — `expectFollowUp`, `isWaitingForBargeInResolution`, auto-sleep timers, and `isWakeWord()` — eliminating the previous duplicate state that caused the first-greeting follow-up timeout bug.
