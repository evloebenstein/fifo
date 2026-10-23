# 🧠 Durable Session Memory (`com.fifo.voicepipeline.data.memory`)

Modelo y consolidación de la memoria conversacional durable del camino `SESSION`.
La decisión completa está en **ADR-0011** (`docs/adr/0011-durable-memory-journal.md`).

---

## 🗺️ Mapa de Archivos

| Archivo | Rol |
| :--- | :--- |
| [`ConversationJournal.kt`](ConversationJournal.kt) | `JournalStatus` (`PENDING`/`CONSOLIDATED`), `JournalEntry`, el puerto `ConversationJournal` e `InMemoryConversationJournal` para pruebas JVM. |
| [`SessionMemoryConsolidator.kt`](SessionMemoryConsolidator.kt) | Costura del consolidador (`consolidate(turns): Boolean`) y `InMemorySessionMemoryConsolidator`. `true` permite marcar el lote como consolidado; `false` o una excepción lo devuelven a la cola. |
| [`RepositorySessionMemoryConsolidator.kt`](RepositorySessionMemoryConsolidator.kt) | Consolidador de producción: extrae memoria con `CloudApiClient.extractSessionMemoryChecked()`, escribe fragmentos/conversaciones/recuerdos/gustos/contactos y sincroniza con `FifoBackendClient`. Usa IDs estables y prefiere `repairText` cuando el turno fue corregido. |

La implementación SQLite del puerto vive en
[`../local/SqliteConversationJournal.kt`](../local/SqliteConversationJournal.kt).

---

## 🔁 Flujo de un turno

```
VoiceSession
    │ 1. recordTurn(userText, assistantText)   ← antes de speakResponse
    ▼
MemoryCoordinator ──► SqliteConversationJournal (PENDING)
    │
    │ 2. requestConsolidation() / drain al dormir / bucle de reintento
    ▼
RepositorySessionMemoryConsolidator
    ├─ extracción local (CloudApiClient)
    ├─ mutaciones en FifoDataRepository
    └─ sincronización con el backend
    │
    ├─ true  ──► markConsolidated()
    └─ false ──► markRetry(attempts + 1, nextAttemptAt con backoff)
```

---

## 📌 Invariantes

1. **El turno se escribe antes de hablar.** Si el diario falla, la conversación continúa; la memoria nunca bloquea el TTS.
2. **La cola pendiente vive en SQLite.** `MemoryCoordinator` no guarda la cola en RAM; un reinicio recupera los turnos `PENDING`.
3. **Solo `CONSOLIDATED` cuando el consolidador devuelve `true`.** Los fallos conservan `attempts`, `nextAttemptAt` y `lastError`.
4. **Backoff exponencial de 30 s a 15 min.** El bucle de reintento se inicia con la pipeline y se detiene al cerrarla.
5. **La corrección se aprende, no el error.** `JournalEntry.effectiveAssistantText()` usa `repairText` si existe.
6. **Un solo dueño de la composición.** `VoiceSessionPipeline` construye `MemoryCoordinator`, el diario SQLite y el consolidador; `VoiceSession` solo ve `SessionMemory` y el facade legacy no crea otro coordinador.
