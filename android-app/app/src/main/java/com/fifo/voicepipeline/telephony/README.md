# 📞 Telephony Subsystem (`com.fifo.voicepipeline.telephony`)

Subpaquete de telefonía y contexto de llamante. La telefonía vive **fuera** de
`VoiceSession`: la sesión solo recibe `callState` y entrega acciones por
`onCallAction`. Ver **ADR-0008** (`docs/adr/0008-capture-arbiter-telephony-port.md`)
y **ADR-0009** (`docs/adr/0009-caller-context-provider.md`).

---

## 🗺️ Mapa de Archivos

| Archivo | Rol y Responsabilidad |
| :--- | :--- |
| [`TelephonyPort.kt`](TelephonyPort.kt) | **Costura de telefonía.** Define `TelephonyPort`, `CallSnapshot` (fase, nombre, número), `CallPhase` (`NONE`, `RINGING`, `ACTIVE`) e `InMemoryTelephonyPort` para pruebas. |
| [`CallStateCoordinator.kt`](CallStateCoordinator.kt) | **Estado puro de la llamada entrante** (ADR-0030): `incomingCall`, `lastActiveCall` y callbacks de contestar/colgar. No contesta, no cuelga ni toca `AudioManager`. `FifoDataRepository` lo expone por delegación. |
| [`AndroidTelephonyPort.kt`](AndroidTelephonyPort.kt) | Implementación Android. Es la **única clase del camino nuevo** que construye `FifoCallManager`; enlaza/desenlaza acciones del repositorio y expone `answer()`/`hangup()`. |
| [`FifoCallManager.kt`](FifoCallManager.kt) | Gestor de llamadas `internal`. Resuelve el contacto local, anuncia el timbrado y ejecuta las acciones. No tiene efectos globales en el constructor: usa `bindRepositoryActions()`/`unbindRepositoryActions()`. |
| [`FifoPhoneCallReceiver.kt`](FifoPhoneCallReceiver.kt) | `BroadcastReceiver` de `EXTRA_STATE_RINGING`/`OFFHOOK`/`IDLE`. Usa `FifoCallManager.resolveContactName()`; no construye un manager desechable por broadcast. |
| [`CallerContextProvider.kt`](CallerContextProvider.kt) | Modelo `CallerContext`, clasificación conocido/desconocido y proveedor en dos fases: `local()` síncrono y `enrich()` contra el backend con timeout de ~800 ms. |
| [`BackendCallerIdentitySource.kt`](BackendCallerIdentitySource.kt) | Adaptador de producción de `CallerIdentitySource` contra `FifoBackendClient.identifyContact`. |
| [`CallerContextCoordinator.kt`](CallerContextCoordinator.kt) | Observa `TelephonyPort.callState`, emite el contexto local al instante, enriquece en paralelo y descarta resultados de llamadas ya terminadas. No inicia ni detiene la telefonía. |

---

## 🔁 Flujo de una llamada en `SESSION`

```
FifoPhoneCallReceiver ──► FifoCallManager ──► FifoDataRepository.incomingCall
                                                   │  (delega en CallStateCoordinator)
AndroidTelephonyPort.callState ◄───────────────────┘
        │
        ├─► CallerContextCoordinator
        │      ├─ local()  → anuncia de inmediato
        │      └─ enrich() → backend (~800 ms, paralelo)
        │
        └─► VoiceSessionPipeline
               ├─ setCallMode(true) en CaptureArbiter → ruta PHONE_CALL
               ├─ anuncia CallerContext.announcementText()
               └─ recibe CallAction (ANSWER/HANGUP/CALLER_STATUS)
                        │
                        ▼
               AndroidTelephonyPort.answer()/hangup()
```

---

## 🧭 Reglas de Programación

1. **Un solo dueño de `FifoCallManager`:** `AndroidTelephonyPort` en el camino nuevo; `VoicePipelineManager` en el legacy. No crear managers en receivers, skills o UI.
2. **La sesión no contesta ni cuelga:** `VoiceSession` solo clasifica la intención y entrega `CallAction`. No toca `AudioManager`.
3. **Comandos completos:** contestar/colgar por voz exige *“Fifo contesta”* / *“Fifo cuelga”*. “Contesta” o “cuelga” aislados no son válidos.
4. **Eco antes que acción:** el anuncio de timbrado contiene esas mismas frases; el escudo de eco se evalúa antes del control de llamada.
5. **Timbrado y activo comparten `PHONE_CALL`:** no reconfigurar el micrófono al pasar de `RINGING` a `ACTIVE`.
6. **Nunca `SpeechRecognizer` durante una llamada:** la ruta de llamada detiene el reconocedor nativo.
7. **Anuncia local primero:** `CallerContextProvider.local()` es síncrono. Nunca esperar al backend para decir el nombre que ya conoce el teléfono.
8. **Un desconocido sigue siendo desconocido:** un caller ID con nombre no se convierte en contacto conocido; no se inventan parentescos.
9. **No leer temas recientes en el timbrado:** usa `CallerContext.announcementText()`. `recentTopics`, `contextualNotes` y `suggestedFollowup` quedan para cuando el usuario pregunte.
