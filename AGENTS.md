# Reglas de arquitectura para agentes

Estas reglas son invariantes de Fifo. Si un cambio las rompe, el cambio está mal aunque los tests pasen.

## Selección de camino

- **Un solo flag de release:** `VOICE_SESSION_ENABLED` está apagado en debug y encendido en release. `VoicePipelineModeSelector` decide `LEGACY` o `SESSION`; sin STT de nube cae a `LEGACY`.
- **Un solo pipeline construido:** `VoicePipelineManager.start()` construye `VoiceSessionPipeline` o inicializa el legacy, nunca ambos. No agregues una propiedad perezosa que cree `VoiceSession` en modo legacy.
- **`VoiceSessionPipeline` es la raíz del camino nuevo.** Es el único lugar que construye `VoiceSession`, el adaptador de captura, `AndroidTelephonyPort` y `CallerContextCoordinator`.
- **El facade delega, no duplica.** `VoicePipelineManager` espeja los flujos del pipeline nuevo y delega sus métodos públicos; no crees un segundo VAD, TTS o manager de telefonía.

## Captura de audio

- **Un solo dueño de hardware:** `CaptureArbiter` (`android-app/app/src/main/java/com/fifo/voicepipeline/pipeline/session/CaptureArbiter.kt`) decide la fuente de captura. No llames a `PhoneMicRecorder` ni al reconocedor nativo desde `VoiceSession`, la UI, `MainActivity` o `FifoVoiceService`.
- **Un solo dueño de VAD/pre-roll:** `SpeechSegmenter` posee el VAD, el pre-roll y el WAV en el camino nuevo. `VoiceSession` solo le entrega tramas y usa el RMS para la boca/OLED. No agregues un segundo VAD en el facade.
- **Una reconfiguración por transición efectiva:** si la ruta calculada no cambia, no toques hardware. Timbrado → activo es la misma ruta (`PHONE_CALL`); no vuelvas a llamar a `switchToCallMode`.
- **Nunca `SpeechRecognizer` durante una llamada.** La ruta de llamada debe ser `PHONE_CALL`; el reconocedor nativo se detiene antes.
- **Descarta tramas tardías:** una trama del micrófono que llega después de cambiar a BLE/nativo/stopped no debe entrar al VAD.
- **`AndroidAudioHardwareAdapter` es solo salida (TTS).** No le agregues captura ni `AudioManager`.
- **No cambies la política de llamadas sin tests de transición.** Usa `CaptureArbiterTest` como tabla de verdad.

## Barge-in y eco

- **El RMS no corta locuciones.** Solo mueve la boca/OLED y el nivel de UI. El corte se decide después de transcribir.
- **Barge-in por contenido:** una nueva invocación “Fifo …” o una orden de silencio corta TTS; un ruido fuerte no.
- **El eco se ignora antes de cualquier acción.** El anuncio de timbrado contiene “Fifo contesta”/“Fifo cuelga”; un eco nunca debe contestar una llamada.
- **Pruebas de verdad:** `SpeechSegmenterTest` y `VoiceSessionTest` cubren pre-roll, cierre de frase, eco y barge-in por transcripción.

## Telefonía

- **La telefonía vive fuera de `VoiceSession`.** La sesión solo recibe `callState` y entrega la acción por `onCallAction`; no contesta, no cuelga y no toca `AudioManager`.
- **`FifoCallManager` es `internal` y no tiene efectos globales en el constructor.** Enlaza/desenlaza acciones con `bindRepositoryActions()`/`unbindRepositoryActions()`.
- **Un solo manager en el camino nuevo.** `AndroidTelephonyPort` es la única clase que lo construye; no crees managers desechables en receivers o skills.
- **Contestar/colgar por voz exige la palabra clave completa:** “Fifo contesta” / “Fifo cuelga”. “Contesta” a secas no es válido.
- **El control de llamada se evalúa después del escudo de eco.** El anuncio de timbrado contiene las mismas frases; no dejes que el eco conteste.
- **Conocido vs desconocido es de primera clase.** `CallSnapshot` no debe colapsar un llamante desconocido en un nombre inventado.

## Contexto de llamante

- **Anuncia local primero.** `CallerContextProvider.local()` es síncrono; nunca esperes al backend para decir el nombre que ya conoce el teléfono.
- **Enriquece en paralelo con timeout.** `enrich()` usa ~800 ms; si falla o tarda, se conserva el contexto local.
- **No leas `recentTopics`, `contextualNotes` ni `suggestedFollowup` en el timbrado.** Usa `CallerContext.announcementText()`; el resto es material para cuando el usuario pregunte.
- **Un desconocido sigue siendo desconocido.** Un caller ID con nombre no es un contacto conocido; no inventes parentesco ni confianza.
- **Usa `CallerContextCoordinator` como observador de `TelephonyPort`.** No metas telefonía dentro de `VoiceSession`.
- **Pruebas de verdad:** `CallerContextProviderTest` y `CallerContextCoordinatorTest` cubren conocido/desconocido, timeout, cancelación y anuncio sin temas recientes.

## Camino legacy

- `VOICE_SESSION_ENABLED` está apagado en debug y encendido en release. El legacy sigue siendo el camino de debug y el fallback sin STT de nube; se borra en la release siguiente.
- No cambies el comportamiento de llamadas legacy sin una prueba que cubra timbrado, contestado, colgado y VoIP.
- Si tocas `VoicePipelineManager`, verifica que sigue llamando a `bindRepositoryActions()` y que `stopListening()` desenlaza.
- No agregues funcionalidad nueva al legacy: la funcionalidad nueva va al camino `VoiceSessionPipeline`.

## Documentación

- Decisiones nuevas: un ADR en `docs/adr/`.
- Términos de dominio: `GLOSSARY.md`.
- La costura de audio está descrita en `docs/adr/0008-capture-arbiter-telephony-port.md`.
- El flag y la composición del camino nuevo están descritos en `docs/adr/0010-voice-session-pipeline-flag.md`.
