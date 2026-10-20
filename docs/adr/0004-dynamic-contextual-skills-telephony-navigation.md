# 0004-dynamic-contextual-skills-telephony-navigation

## Context and Decision
In voice interaction systems for older adults, generic canned responses (e.g., *"Llamada contestada"*, *"Llamada finalizada"*, *"Linterna encendida"*, *"El local más próximo queda a 200 metros"*) destroy empathy, break conversational trust, and fail to understand the user's underlying needs. Furthermore, telephony events require tracking and cataloging callers in the backend relational knowledge graph to recognize who is speaking, preserve continuity across calls, and offer proactive help.

We implemented an end-to-end contextual intelligence architecture across the backend and Android client:

### 1. Backend Caller Cataloging & Telephony History (`backend/api/main.py`)
- **`call_history_log` Table**: Tracks all telephony actions (`answer`, `hangup`, `incoming`, `dialed`, `missed`) with timestamps, durations, and notes.
- **`POST /users/{user_id}/telephony/identify-contact`**:
  - Resolves callers against `user_contacts_relational` and `knowledge_nodes` (entity types: `person`, `family`, `doctor`, `friend`).
  - Retrieves relationship roles, contextual memory notes, and recent mentions from past conversations.
  - Automatically catalogs unknown contacts into `knowledge_nodes` so they immediately become part of the user's knowledge graph.
  - Generates conversational guidance hints (`suggestedGreeting`, `suggestedFollowup`).
- **`POST /users/{user_id}/telephony/log-call-event`**: Asynchronously persists call history and increments mention counts on knowledge nodes.

### 2. Destination Need & Functional Context (`POST /users/{user_id}/navigation/contextual-intent`)
- Inspects destination intent to understand the user's real purpose:
  - **Health / Pharmacy / Doctor**: Cross-references past doctor mentions, active medication prescriptions, and reminders, suggesting IDs/prescriptions and preferential queues.
  - **Food / Dining**: Cross-references favorite tastes (`user_tastes`) and past companions from episodic memory.
  - **Shopping / Supermarket**: Suggests reusable bags or shopping carts.

### 3. Dynamic Non-Hardcoded Client Skills
- **`PhoneCallSkill.kt`**:
  - Physical execution remains instantaneous on the telephony hardware.
  - Asynchronously queries `FifoBackendClient.identifyContact` and logs call events.
  - Returns rich contextual data (`caller`, `relationship`, `recent_context`, `suggested_followup`).
  - Spoken feedback speaks warmly to the relationship (e.g. *"Contestando a su hija Carmen en altavoz..."*) and proactively prompts after hanging up (*"Listo, corté la llamada con Carmen. Si desea que le anote algún recado de lo que hablaron, con gusto lo guardo."*).
  - `FifoDataRepository`: Preserves `lastActiveCall` across hangup events so contact context is never lost.
- **`PhoneDeviceControlSkill.kt`**:
  - Eliminates monotone generic phrases.
  - Integrates situational empathy (e.g., low battery charging warnings, reassuring flashlight illumination, time with meal cues).
  - Returns complete feature telemetry in `SkillResult.data`.
- **`NavigationDirectionsSkill.kt`**:
  - Enriches routes with contextual intent tips (`purpose_type`, `contextual_notes`, `contextual_recommendations`).
  - Adapts spoken guidance with health/shopping cues without robotization.

### 4. Groq Tool Execution Loop Context Expansion (`CloudApiClient.kt`)
- **Data Key Retention**: Expanded `compactData` to pass all telephony (`caller`, `relationship`, `recent_context`, `suggested_followup`), device (`feature`, `state`, `battery_level`, `time_formatted`), and navigation (`purpose_type`, `contextual_notes`, `contextual_recommendations`, `turn`, `relative_turn`) metadata directly to Groq LPU.
- **Rule 6 in System Prompt**: Mandates 100% dynamic, warm, empathetic speech generation for telephony, device control, and navigation, prohibiting robotic enlatadas.

## Consequences
- Zero robotic or canned phrases when answering, hanging up, adjusting phone hardware, or requesting navigation directions.
- Full traceability of contacts and callers in the backend knowledge graph.
- Groq LPU receives rich multimodal and domain context to maintain emotional continuity with older adults.
