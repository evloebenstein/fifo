# Reglas de arquitectura para agentes

Estas reglas son invariantes de Fifo. Si un cambio las rompe, el cambio está mal aunque los tests pasen.

## Captura de audio

- **Un solo dueño:** `CaptureArbiter` (`android-app/app/src/main/java/com/fifo/voicepipeline/pipeline/session/CaptureArbiter.kt`) decide la fuente de captura. No llames a `PhoneMicRecorder`, al reconocedor nativo ni al VAD desde `VoiceSession`, la UI, `MainActivity` o `FifoVoiceService`.
- **Una reconfiguración por transición efectiva:** si la ruta calculada no cambia, no toques hardware. Timbrado → activo es la misma ruta (`PHONE_CALL`); no vuelvas a llamar a `switchToCallMode`.
- **Nunca `SpeechRecognizer` durante una llamada.** La ruta de llamada debe ser `PHONE_CALL`; el reconocedor nativo se detiene antes.
- **Descarta tramas tardías:** una trama del micrófono que llega después de cambiar a BLE/nativo/stopped no debe entrar al VAD.
- **`AndroidAudioHardwareAdapter` es solo salida (TTS).** No le agregues captura ni `AudioManager`.
- **No cambies la política de llamadas sin tests de transición.** Usa `CaptureArbiterTest` como tabla de verdad.

## Telefonía

- **La telefonía vive fuera de `VoiceSession`.** La sesión observa `TelephonyPort.callState`; no contesta, no cuelga y no toca `AudioManager`.
- **`FifoCallManager` es `internal` y no tiene efectos globales en el constructor.** Enlaza/desenlaza acciones con `bindRepositoryActions()`/`unbindRepositoryActions()`.
- **Un solo manager en el camino nuevo.** `AndroidTelephonyPort` es la única clase que lo construye; no crees managers desechables en receivers o skills.
- **Contestar/colgar por voz exige la palabra clave completa:** “Fifo contesta” / “Fifo cuelga”. “Contesta” a secas no es válido.
- **Conocido vs desconocido es de primera clase.** `CallSnapshot` no debe colapsar un llamante desconocido en un nombre inventado.

## Contexto de llamante

- **Anuncia local primero.** `CallerContextProvider.local()` es síncrono; nunca esperes al backend para decir el nombre que ya conoce el teléfono.
- **Enriquece en paralelo con timeout.** `enrich()` usa ~800 ms; si falla o tarda, se conserva el contexto local.
- **No leas `recentTopics`, `contextualNotes` ni `suggestedFollowup` en el timbrado.** Usa `CallerContext.announcementText()`; el resto es material para cuando el usuario pregunte.
- **Un desconocido sigue siendo desconocido.** Un caller ID con nombre no es un contacto conocido; no inventes parentesco ni confianza.
- **Usa `CallerContextCoordinator` como observador de `TelephonyPort`.** No metas telefonía dentro de `VoiceSession`.
- **Pruebas de verdad:** `CallerContextProviderTest` y `CallerContextCoordinatorTest` cubren conocido/desconocido, timeout, cancelación y anuncio sin temas recientes.

## Camino legacy

- `VoicePipelineManager` sigue siendo producción hasta que `VOICE_SESSION_ENABLED` se active en una release.
- No cambies el comportamiento de llamadas legacy sin una prueba que cubra timbrado, contestado, colgado y VoIP.
- Si tocas `VoicePipelineManager`, verifica que sigue llamando a `bindRepositoryActions()` y que `stopListening()` desenlaza.

## Documentación

- Decisiones nuevas: un ADR en `docs/adr/`.
- Términos de dominio: `GLOSSARY.md`.
- La costura de audio está descrita en `docs/adr/0008-capture-arbiter-telephony-port.md`.
