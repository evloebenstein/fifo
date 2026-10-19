# 0001-deep-voice-session-architecture

## Context and Decision
The Android voice pipeline orchestrates real-time audio capture (Bluetooth LE from ESP32-S3 and phone microphone), WebRTC/RMS VAD, Picovoice Porcupine wake-word detection, Groq Whisper STT, Groq LPU conversational turns with dual-context barge-in resumption, and TTS output. We decided to encapsulate all hardware stream handling, circular pre-roll buffers, and network requests behind a deep `VoiceSession` module with two decoupled seams:
1. `AudioSeam` (`AudioInboundAdapter` / `AudioOutboundAdapter`): Implemented by `AndroidAudioHardwareInboundAdapter` (multiplexing phone mic and BLE ESP32 audio) and `AndroidAudioHardwareAdapter` in production, and `InMemoryAudioInboundAdapter` / `InMemoryAudioOutboundAdapter` in tests.
2. `SpeechCognitionSeam` (`SpeechCognitionAdapter`): Implemented by `GroqSpeechCognitionAdapter` in production (pure Groq Whisper STT + Groq LPU LLM, zero third-party/Claude dependencies) and `InMemorySpeechCognitionAdapter` in tests.

Crucially, all generic hardcoded canned messages are eliminated: greetings, sleep farewells, and barge-in continuations are generated dynamically by Groq LLM tailored to the user's personality and profile, while silence commands produce immediate, absolute silence.

## Consequences
- The UI and background services interact exclusively via `VoiceSession` state flows and intents (`VoiceSessionIntent`), with zero knowledge of audio hardware threads, raw PCM buffers, or Groq HTTP endpoints.
- Barge-in dual-context resolution, wake-word gating ("Fifo"), and follow-up question windowing are isolated inside `TurnOrchestrator`, making voice interactions verifiable in sub-second in-memory tests without an Android emulator.
- Existing callers transition smoothly through `VoicePipelineManager` as a delegation facade.
