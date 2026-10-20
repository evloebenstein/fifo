# 0003-knowledge-graph-and-intent-router

## Context and Decision
Fifo serves older adults who interact solely through natural voice. To achieve instant conversational responsiveness while providing deep, truthful episodic memory, we implemented two architectural pillars:

### 1. Hybrid GraphRAG Memory Pipeline (Asynchronous Catalog → Relational Tree)
Older adults naturally ask multi-hop and ambiguous memory questions (e.g., *"Do you remember what restaurant I went to with María last year? Is it still open?"*, *"Did I mention what the doctor said about the prescription?"*, or *"Did I tell you what my granddaughter was wearing?"*).
- **On-Device Compressed Context**: `conversation_fragments` and `buildCompactContextWindow` maintain an ultra-lean (<80 tokens) context window on the phone containing only the most salient entity nodes and summaries, ensuring low latency and low token consumption on Groq LPU.
- **Asynchronous Raw Ingestion & Chunking**: Full turn-by-turn conversations (`conversations_full`) are streamed to the backend upon session completion. The backend breaks transcripts into structural turn chunks.
- **Relational Tree Nodes & Edges**: Entities (People, Places/Restaurants, Medical/Prescriptions, Routines, Objects/Clothing) are extracted and stored as `knowledge_nodes` (`entity_type`: person, place, medical, routine, object, concept). Mentions (`knowledge_node_mentions`) link each node directly to specific chunk indices across past conversations.
- **Sliding Window Retrieval & Gap Detection (`POST /users/{userId}/recall-context`)**: When `recall_past_context` is invoked, the backend traverses directly from the target entity node to its referenced chunks, pulls adjacent turns (Sliding Window $N-1, N+1$) for complete grammatical coherence, and analyzes missing details / blank spaces (*"espacios en blanco"*) or confirms that a topic was never discussed, eliminating hallucinations.

### 2. Disentangled Voice Intent Router & Pure Groq Tool Catalog
`FifoSkillRegistry` was previously a 700-line monolith mixing tool registration, dead Anthropic Claude JSON schemas, and a procedural regex matcher that risked intercepting conversational queries. We decomposed this into:
- `VoiceIntentRouter`: A strict, sub-millisecond local matcher for zero-ambiguity hardware commands (flashlight, volume, time, battery), phone calls (answer, hangup, status), sleep/silence, and active walking navigation. Any query with the slightest conversational ambiguity, memory recall, personal question, or external inquiry returns `null` to pass directly to Groq LPU.
- `GroqToolCatalog`: A 100% Groq/OpenAI tool catalog eliminating dead Claude schemas, with an enriched `recall_past_context` schema that explicitly supports people, dialogue recall, doctor prescriptions, clothing, and gap detection.
- `FifoSkillRegistry`: A lightweight delegation facade preserving 100% backward compatibility for callers (`VoiceSession`, `CloudApiClient`, `VoicePipelineManager`).

## Consequences
- Zero conversational questions or episodic memory inquiries are prematurely hijacked by local string matches.
- Multi-hop agentic reasoning (e.g., querying episodic memory for a restaurant name, then chaining a live web search to check current opening status) executes cleanly through Groq LPU.
- Hardware and telephony controls execute in sub-millisecond local speed without network overhead.
