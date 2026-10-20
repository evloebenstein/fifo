# 0006-collapse-cloud-api-client-into-speech-cognition-seam

## Context and Decision

Following the consolidation of `VoicePipelineManager` into `VoiceSession` (ADR-0005), `CloudApiClient` remained as a legacy multi-purpose monolith (~1,500 lines). It coupled multiple disparate responsibilities:
1. Groq LPU tool-calling chat engine (Qwen 27B) and multi-turn execution.
2. Whisper Speech-to-Text inference and hallucination filtering.
3. OpenAI Text-To-Speech (TTS) audio synthesis and resampling.
4. Direct, static coupling to `FifoDataRepository` for temporal and episodic context enrichment.
5. Legacy, unused Anthropic/Claude fallback logic (~350 lines).

We decided to collapse `CloudApiClient` behind the `SpeechCognitionSeam` (`SpeechCognitionAdapter`) architecture, establishing a deep module design that preserves all capabilities while unlocking full JVM testability.

### Guarantees and Invariants

1. **Empathetic AI & Zero Canned Responses:**
   All conversational utterances and skill follow-ups continue to be generated dynamically by Groq LPU (Qwen 27B) under the senior-citizen empathetic prompt. No hardcoded or generic replies are introduced.

2. **Full Phone and App Control (Skills preserved):**
   `FifoSkillRegistry` and all device/telephony/navigation actions (`manage_phone_call`, `control_device_hardware`, `open_navigation_directions`, `search_nearby_places`, `recall_past_context`) remain 100% active and operative. The tool-calling loop executes seamlessly through the adapter.

3. **Context Enrichment & Memory Consolidation:**
   Dynamic contextual enrichment (current time, date, user profile, GPS location, active route, and recent memory fragments) is preserved through a decoupled `ConversationContextProvider` interface. `extractSessionMemory()` remains available for long-term database storage.

4. **Telephony & Audio Invariant:**
   The telephony subsystem (`FifoCallManager`, `FifoPhoneCallReceiver`, phone call concurrency, Bluetooth SCO/A2DP routing, and Barge-In interruption) remains 100% isolated and protected against regressions.

5. **Pruning Dead Claude Code:**
   Unused Anthropic API calls (`chatWithClaude`, `chatStreamingClaude`, `testAnthropicConnection`) are pruned safely to eliminate technical debt and reduce failure surfaces, standardizing on 100% Groq LPU.

6. **JVM Testability with Skill Simulation:**
   `InMemorySpeechCognitionAdapter` is enhanced to optionally simulate tool calls (e.g. simulating that the LLM invoked `control_device_hardware`), allowing pure JVM unit tests of end-to-end device control workflows without physical hardware.

## Consequences

- `SpeechCognitionAdapter` is the clean, deep interface for speech transcription, LLM reasoning, tool-calling execution, and natural conversational resumption.
- `CloudApiClient` remains a backward-compatible facade delegating speech and dialogue turns to `GroqSpeechCognitionAdapter`.
- Network layer is decoupled from SQLite/Room database structures.
- Unit testing coverage expands to include multi-turn tool calling and follow-up resolutions in `VoiceSessionTest`.
