# 0002-data-repository-modularization

## Context and Decision
The monolithic `FifoDataRepository` previously mixed user profile identity management, community activities, social feed operations, relational contacts, circadian routines, dual-layer episodic memory, and telephony hardware state into a single 1500+ line object directly bound to Android SQLite.

We decomposed this monolith into three deep domain modules backed by clean storage seams:
1. `UserIdentityModule`: Encapsulates user profile, demographics, authentication identity resolution, AI-generated bio, and interest tags. Backed by `UserStorageAdapter` (`SqliteUserStorageAdapter` in production, `InMemoryUserStorageAdapter` in tests).
2. `CommunityModule`: Encapsulates community gathering activities for seniors (with attendee spot limits and registration state) and the community social feed ("Fifo Amigos"). Backed by `CommunityStorageAdapter` (`SqliteCommunityStorageAdapter` in production, `InMemoryCommunityStorageAdapter` in tests).
3. `EpisodicMemoryModule`: Encapsulates the dual-layer memory system:
   - **Layer 1 (Fast on-device context window):** `buildCompactContextWindow` (<80 tokens) for sub-second, low-token Groq prompt injection.
   - **Layer 2 (Deep context retrieval):** `searchDeepContext` querying the Docker/MySQL backend agent with local multi-level SQLite fallback.
   - Also encapsulates memories, reminders with completion states, tastes with duplicate prevention, relational contacts with closeness scoring, and circadian routines. Backed by `MemoryStorageAdapter` (`SqliteMemoryStorageAdapter` in production, `InMemoryMemoryStorageAdapter` in tests).

### Telephony Invariant
Past revisions experienced severe telephony regressions when call listeners were moved across boundaries. As an explicit architectural invariant, telephony (`incomingCall`, `_incomingCall`, `setIncomingCall()`, `answerCurrentCall()`, and `hangupCurrentCall()`) remains directly in `FifoDataRepository` as the single source of truth for the telephony receiver and phone mic recorder.

## Consequences
- `FifoDataRepository` remains as a 100% backward-compatible delegation facade, allowing all 27 caller files in the project to operate without breaking changes.
- Core domain logic (demographics, community activity join/leave, dual-layer context synthesis) is now 100% unit testable in pure JVM without Android Context, SQLite, or emulators.
- The two-layer memory architecture is preserved and cleanly isolated inside `EpisodicMemoryModule`.
