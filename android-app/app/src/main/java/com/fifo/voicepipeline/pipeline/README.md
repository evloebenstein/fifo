# ⚙️ Voice Pipeline Core (`com.fifo.voicepipeline.pipeline`)

Núcleo central de procesamiento de voz y orquestación del asistente interactivo **FIFO**.

---

## 🗺️ Mapa de Archivos del Directorio

| Archivo | Rol y Responsabilidad |
| :--- | :--- |
| [`PipelineState.kt`](PipelineState.kt) | **Máquina de Estados Finita (`enum class PipelineState`)**: Define los estados del pipeline: `DISCONNECTED` (robot desconectado), `CONNECTING`, `SLEEPING` (en reposo silencioso esperando "Fifo"), `IDLE` (listo con micrófono abierto), `LISTENING` (capturando voz del usuario), `PROCESSING` (pensando / consultando IA), `SPEAKING` (hablando por altavoz), `ERROR`. |
| [`VoicePipelineManager.kt`](VoicePipelineManager.kt) | **Director de Orquesta de la Voz**: Clase de ~1,850 líneas que coordina la captura de micrófono, detección estricta de la palabra de activación (*"Fifo"*), **modo follow-up fluido** al formular preguntas directas al usuario, cancelación de eco acústico del altavoz, interrupción por voz (*Barge-in*), comandos de silencio (*"Fifo silencio"*), enrutamiento de intenciones directas mediante `FifoSkillRegistry` y consolidación de memoria al finalizar sesiones. |

---

## 🔄 Flujo Conversacional Estricto (Wake-Word vs. Follow-up)

```
       +────────────────────────────────────────────────────────────+
       │                     ESTADO: SLEEPING                       │
       │  (Micrófono escucha pero ignora todo ruido / voz ambiente) │
       +─────────────────────────────┬──────────────────────────────+
                                     │
                          ¿El usuario dijo "Fifo"?
                                     │
                                     ▼
       +────────────────────────────────────────────────────────────+
       │            ESTADO: LISTENING / PROCESSING                  │
       │   Procesa la consulta con Claude / Groq / Skills nativas   │
       +─────────────────────────────┬──────────────────────────────+
                                     │
                                     ▼
       +────────────────────────────────────────────────────────────+
       │                     ESTADO: SPEAKING                       │
       │         Fifo responde verbalmente por el altavoz           │
       +─────────────────────────────┬──────────────────────────────+
                                     │
                    Al terminar la locución (TTS Done):
                                     │
                ┌────────────────────┴────────────────────┐
                ▼                                         ▼
      ¿Fifo formuló una PREGUNTA?                ¿Fue una AFIRMACIÓN?
      (Contiene '?' o "¿" o invitó)             (ej: "Son las 4", "Listo")
                │                                         │
                ▼                                         ▼
   +──────────────────────────+              +──────────────────────────+
   │    ESTADO: LISTENING     │              │     ESTADO: SLEEPING     │
   │  Follow-up activo (14s)  │              │  Fifo descansa y exige   │
   │ El usuario puede responder│              │  "Fifo" para despertar   │
   │   DIRECTAMENTE sin decir │              +──────────────────────────+
   │      la palabra "Fifo"   │
   +──────────────────────────+
```

---

## 🧭 Reglas de Programación en `pipeline/`
1. **Regla de Oro del Wake-Word:** NUNCA aceptar audio sin la palabra "Fifo" a menos que `expectFollowUpQuestion == true` esté activo o el usuario esté en modo de escucha continua explícita.
2. **Protección Anti-Eco:** Durante y después de la locución, la respuesta del usuario en modo follow-up nunca debe descartarse como eco salvo que sea una repetición idéntica de la propia frase emitida por Fifo.
3. **Consolidación de Memoria:** Al pasar a `goToSleep()`, los turnos conversacionales acumulados en `activeSessionTurns` se envían en segundo plano a `consolidateSessionIntoMemory()` para persistencia en SQLite y MySQL.
