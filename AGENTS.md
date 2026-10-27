# Reglas de arquitectura para agentes

Estas reglas son invariantes de Fifo. Si un cambio las rompe, el cambio está mal aunque los tests pasen.

## Selección de camino

- **Un solo flag de release:** `VOICE_SESSION_ENABLED` está apagado en debug y encendido en release. `VoicePipelineModeSelector` decide `LEGACY` o `SESSION`; sin STT de nube cae a `LEGACY`.
- **Un solo pipeline construido:** `VoicePipelineManager.start()` construye `VoiceSessionPipeline` o inicializa el legacy, nunca ambos. No agregues una propiedad perezosa que cree `VoiceSession` en modo legacy.
- **`VoiceSessionPipeline` es la raíz del camino nuevo.** Es el único lugar que construye `VoiceSession`, el adaptador de captura, `AndroidTelephonyPort` y `CallerContextCoordinator`.
- **El facade delega, no duplica.** `VoicePipelineManager` espeja los flujos del pipeline nuevo y delega sus métodos públicos; no crees un segundo VAD, TTS o manager de telefonía.

## Respuestas dinámicas

- **Nada de mensajes enlatados.** La respuesta hablada se compone con contexto enriquecido (perfil, orientación, ruta, memoria episódica, agenda). Solo si ese contexto falta se admite un fallback genérico de error.
- **El fast-path solo hace acciones mecánicas:** contestar/colgar, linterna, volumen, batería, hora y cancelar una ruta. Las consultas informativas —ubicación (“¿dónde estoy?”), orientación activa (“¿dónde voy?”, “¿cuánto falta?”, “me desvié”) y cualquier pregunta conversacional o de memoria— retornan `null` y las redacta el LLM.
- **No agregues generadores de frases.** Si necesitas contexto nuevo, agrégalo al prompt (`buildSystemPromptContext()`, `DefaultConversationContextProvider`) o a `SkillResult.data`; no agregues métodos que devuelvan una oración armada para hablar.
- **`spokenFeedback` es payload, no respuesta.** El LLM lo recibe como dato y solo se locuta cuando la acción la ejecuta el fast-path o como fallback de error.
- **Pruebas de verdad:** `VoiceIntentRouterTest` verifica que ubicación y orientación activa se delegan al LLM.

## Guía autónoma y sesión de ruta

- **El sensor emite hechos, no frases.** `FifoNavigationManager` publica `NavigationNotice` (desvío, quietud, 100 m, 50 m, llegada); el pipeline pide la locución al LLM. `NavigationNotice.fallbackSpeech()` es el único texto estático y solo se usa si el modelo no responde.
- **La sesión de ruta tiene dueño.** `NavigationSessionMemory` guarda encargos (`PlannedStop`), pausas y correcciones; se reinicia al iniciar o terminar la ruta. El LLM la gestiona con la herramienta `manage_navigation_session`, no con frases armadas.
- **La ruta sobrevive al proceso.** `FifoNavigationManager` persiste la foto con `NavigationSessionStore` en cada cambio y `VoicePipelineManager.start()` la restaura con caducidad de 6 h (ADR-0041). No metas Android ni `SharedPreferences` dentro de `NavigationSessionMemory`: la costura es inyectable.
- **Una pausa consciente silencia el acompañamiento.** No se emiten avisos de desvío, quietud ni hitos mientras dura; la llegada sí se anuncia.
- **El aviso no se encima a un turno.** `NavigationNoticeDispatcher` es el dueño de la cola, la espera sin plazo y la narración; `VoicePipelineManager` solo entrega el hecho y elige el motor de salida. Nunca descartes por duración: el más reciente gana y solo se descarta si el destino activo cambió.
- **Pruebas de verdad:** `NavigationSessionMemoryTest`, `NavigationSessionPersistenceTest`, `NavigationNoticeQueueTest` y `NavigationNoticeDispatcherTest` cubren encargos, pausas, correcciones, contexto del prompt, avisos estructurados, persistencia/restauración/caducidad, coalescencia, relevancia y fallback.

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
- **Pruebas de verdad:** `SpeechSegmenterTest`, `UtterancePolicyTest` y `VoiceSessionTest` cubren pre-roll, cierre de frase, eco y barge-in por transcripción.

## Política de locución

- **Una sola autoridad léxica:** `UtterancePolicy` (`pipeline/session/UtterancePolicy.kt`, ADR-0042) decide invocación, consulta, saludo, silencio, sueño, eco, pregunta y control de llamada. `TurnOrchestrator` y el facade legacy delegan; no agregues listas, regex o frases nuevas en ninguno de los dos.
- **La política clasifica, no redacta.** No devuelve oraciones para hablar ni conoce TTS, Android o el pipeline. La respuesta la sigue componiendo el LLM.
- **El estado entra como parámetro.** `isEcho()` recibe la frase hablada y si Fifo espera respuesta; no leas `StateFlow` ni estado mutable dentro de la política.
- **Pruebas de verdad:** `UtterancePolicyTest` es la tabla de verdad; `TurnOrchestratorTest` y `VoiceSessionTest` cubren la integración.

## Invocación (cascada acústica + léxica)

- **El detector acústico propone, no decide.** `WakeWordDetector` (`pipeline/session/WakeWordDetector.kt`, ADR-0043) devuelve un `WakeWordDetection` con score e instante; no habla, no despierta por sí solo y no autoriza acciones.
- **No abre micrófono ni tiene VAD.** `VoiceSession` le entrega las mismas tramas que ya captura `CaptureArbiter`; `SpeechSegmenter` sigue siendo el dueño único del VAD y del pre-roll.
- **Solo se evalúa mientras Fifo duerme.** Durante una locución la propia voz dispararía el detector ("Fifo contesta"/"Fifo cuelga"). Ese es el escudo de eco de la etapa acústica; el barge-in sigue siendo por transcripción.
- **Una sola fusión.** `UtterancePolicy.resolveWake()` decide `CONFIRMED`, `ACOUSTIC_ONLY`, `RETRY` o `IGNORE`. No agregues una segunda regla en `VoiceSession` ni en el detector.
- **La acústica nunca autoriza telefonía.** `classifyCallAction()` sigue exigiendo "Fifo" en la transcripción; un candidato acústico con "contesta" no contesta.
- **El candidato caduca y se consume una vez** (~8 s, para cubrir una pregunta larga) y el detector se reinicia al dormir, silenciar o reiniciar.
- **El arnés es sintético y no mide campo.** `WakeWordCorpus` + `WakeWordHarnessTest` (ADR-0044) generan el corpus y miden FRR por SNR, negativos difíciles, falsos aceptos/hora y latencia sin binarios ni descargas. Sirven para regresión y para comparar motores; no certifican el rendimiento con televisión y voces reales.
- **El corpus de campo es simulado, no grabado.** `RoomAcoustics` (RIR por método de imagen), `LombardEffect` y `SpeakerVariation.vtlp` (ADR-0045) añaden sala, esfuerzo vocal y tractos distintos en Kotlin puro. Miden el simulador, no el salón: sus números sirven para decidir que hace falta un modelo entrenado, no para certificar el FRR real.
- **El corpus base se congela.** `positives()`, `hardNegatives()` y `conversations()` conservan sus casos y su tabla; cualquier condición nueva va a `fieldPositives()`/`fieldHardNegatives()`/`fieldConversations()`. No mezcles ambos corpus en una misma comparación.
- **Diagnóstico antes de culpar al detector.** Si el arnés de campo cae, comprueba el desajuste de canal (plantillas secas vs plantillas de la misma sala) antes de tocar features o umbrales. Con la sala corregida, el FRR de `office-clean` pasa de 87,5 % a 12,5 %.
- **El detector de referencia vive en `test`, nunca en la app.** `SpectralTemplateWakeWordDetector` mide el techo del enfoque por plantilla espectral; no se envía y no sustituye a un modelo entrenado.
- **El corpus sintético mide, no entrena.** `WakeWordTrainingCorpus`, la logística, la red 257→24→1 y las plantillas aumentadas de ADR-0046 viven en `test` como evidencia reproducible. Ninguno alcanzó ≤1 FA/h: la mejor logística dejó 73,1 % de FRR y 834 FA/h incluso en el corpus base. No presentes esos números como validación de campo.
- **La exportación es un contrato, no un artefacto.** `WakeWordCorpusExportTest` escribe WAV mono a 16 kHz PCM16 y `manifest.jsonl` (split, familia, etiqueta, locutor, condición y tiempos). Se activa solo con `FIFO_WAKEWORD_EXPORT_DIR` y no se commitea ningún WAV. Cambiar el formato o los campos rompe el entrenador de ADR-0047.
- **El mel-CNN es una herramienta, no el detector.** `tools/wakeword/` (log-mel + CNN, calibración por FA/h y persistencia) es solo de desarrollo; `torch`/`numpy` no entran en la app y el `.pt` no se envía. Con 100 locutores baja el FRR de campo a 33,3 %, pero deja 13,2 FA/h; `NoopWakeWordDetector` sigue por defecto.
- **La persistencia es parte del detector.** Exigir dos ventanas consecutivas es lo que separa una palabra de un pico de conversación (FRR de campo 79,2 % → 33,3 %). Si algún día se envía un detector acústico, la histéresis va en su interfaz, no en el consumidor.
- **Cada tabla nueva se mide contra la misma barra.** Un cambio de corpus, ventana o umbral se compara con `WakeWordHarnessTest` y se documenta en un ADR; un buen número en el simulador no autoriza a cambiar el detector de producción.
- **Los experimentos de aprendizaje están apagados por defecto.** `WakeWordLearningTest` y `AugmentedTemplateTest` solo corren con `FIFO_WAKEWORD_EXPERIMENT=1`; sin esa variable JUnit los omite y la suite normal no paga minutos de entrenamiento.
- **La barra de producción es ≤1 FA/h y no se negocia.** `NoopWakeWordDetector` sigue siendo el valor por defecto. Cambiarlo exige un modelo que supere la barra de ADR-0044 (FRR por SNR, negativos difíciles, FA/h y latencia) y validación con grabaciones reales; un buen número en el corpus sintético no basta.
- **Pruebas de verdad:** `UtterancePolicyTest` (tabla de fusión), `WakeWordCascadeTest` (integración con detector falso; sin modelo descargado) y `WakeWordHarnessTest` (corpus base y de campo, RIR/FFT, Lombard y VTLP).

## Identidad de locutor

- **Personaliza, no autentica.** `SpeakerIdentity` (`pipeline/session/SpeakerIdentity.kt`, ADR-0048) etiqueta el turno como `OWNER`, `KNOWN`, `UNKNOWN`, `UNCERTAIN` o `UNAVAILABLE`. Nunca autoriza llamadas, pagos, cambios de configuración ni datos privados: la telefonía sigue exigiendo "Fifo contesta"/"Fifo cuelga" y `UtterancePolicy.classifyCallAction()` sigue siendo la única autoridad.
- **`Noop` por defecto.** `NoopSpeakerIdentityProvider` es el valor por defecto hasta que exista un modelo de embeddings medido con corpus real y un almacén cifrado. `VoiceIdentitySettings.provider()` elige el proveedor y `VoiceSessionPipeline` lo construye; el facade legacy no construye identidad.
- **Corre en paralelo al STT, con timeout de 400 ms.** `VoiceSession` lanza la identificación junto a la transcripción; si tarda, falla o no hay perfiles, el turno sigue como `UNAVAILABLE`. Nunca bloquea ni alarga la conversación.
- **Una costura, no un segundo micrófono ni un segundo VAD.** `SpeakerIdentityProvider` recibe el WAV que ya cerró `SpeechSegmenter`; `CaptureArbiter` sigue siendo el dueño único de la captura y `SpeechSegmenter` del VAD/pre-roll.
- **Una sola política.** `SpeakerIdentityPolicy.resolve()` es la única que convierte evidencia (candidatos, score, duración, `spoofRisk`) en una relación. No repitas umbrales ni reglas en `VoiceSession`, en el proveedor ni en el LLM. Los valores de `SpeakerPolicyConfig` son configuración por modelo y se recalibran con el arnés.
- **El spoofing es una costura, no una promesa.** `SpoofingDetector` (`pipeline/session/SpoofingDetector.kt`) corre en paralelo al extractor con timeout propio; `NoopSpoofingDetector` devuelve `null` y es el valor por defecto. `SpeakerEvidence.spoofRisk` y `SpeakerPolicyConfig.maxSpoofRisk` (0,50) solo degradan a `UNCERTAIN`: nunca autorizan ni rechazan acciones. Sin un modelo validado con ASVspoof no se anuncia protección contra voces sintéticas.
- **La UI comparte el almacén y no finge enrolamiento.** `VoiceIdentitySettings` (`pipeline/session/VoiceIdentitySettings.kt`) guarda el consentimiento en `fifo_prefs`, compone un único almacén cifrado y elige el proveedor. `ProfileScreen` muestra el estado real ("Motor de voz pendiente"), deshabilita el enrolamiento mientras `modelId()` sea `null` y siempre permite borrar, incluso con el consentimiento apagado.
- **La política clasifica, no redacta.** `SpeakerPromptContext` produce texto de system prompt (`OWNER`/`KNOWN`/`UNKNOWN`/`UNCERTAIN`); el LLM sigue redactando la respuesta. No agregues locuciones de identidad ni frases enlatadas.
- **Solo embeddings locales y cifrados.** Se guarda el centroide, nunca el audio crudo. `EncryptedVoiceProfileStore` (`pipeline/session/EncryptedVoiceProfileStore.kt`) cifra en reposo con `AndroidKeystoreProfileCipher` (AES-256-GCM), escribe atómicamente, versiona el blob y tolera corrupción como "sin perfiles". `ConsentAwareVoiceProfileStore` no lee ni enrola sin consentimiento, pero siempre permite borrar. Los perfiles de otro `modelId` se ignoran y **no se sincronizan con el backend**.
- **El arnés mide el simulador, no el campo.** `SpeakerVerificationCorpus` + `SpeakerVerificationHarnessTest` (ADR-0048, gated con `FIFO_SPEAKER_EXPERIMENT=1`) usan 8 locutores de evaluación, 4 de calibración y un extractor de referencia. El umbral se fija con los locutores de calibración a un FAR objetivo y se reporta con los de evaluación; nunca calibres y reportes sobre las mismas voces. Sus números sirven para regresión y comparación; no certifican EER real ni autorizan a enviar un detector biométrico.
- **El sintético no ordena motores reales.** Tres modelos reales medidos con sherpa-onnx (WeSpeaker CAM++ y ResNet34, 3D-Speaker CAM++) quedaron en 42–50 % EER global, peor que la referencia espectral (31,8 %). En limpio ResNet34 baja a 9,5 %, pero el ruido sintético (5–10 dB, sala + Lombard) es un dominio hostil. El control con LibriSpeech da **0,0 % EER** con el mismo pipeline: el problema es el corpus, no el código. No elijas ni descartes un motor por la tabla sintética; sirve para reproducir el contrato y detectar regresiones.
- **El campo simulado usa voces reales, no TTS ni formantes.** `prepare_librispeech_pool.py` + `RealSpeechAugmentationExportTest` (gated con `FIFO_REAL_SPEECH_POOL`/`FIFO_REAL_SPEECH_EXPORT_DIR`) toman LibriSpeech y aplican la misma `RoomAcoustics`, `SimulatedNoise` y `LombardEffect` del arnés. 40 locutores, splits disjuntos y seis condiciones; no se commitea audio. Sí prefiltra motores (CAM++ zh_en advanced 1,2 % EER crudo, con `white-5` aún en 10,7 % FRR), pero no certifica campo ni autoriza a cambiar `Noop`.
- **AS-Norm se entrena solo con calibración.** `score_embeddings.py --normalize as-norm` estima media y desviación con los perfiles y clips de `calibration`; `evaluation` no participa. Un cambio de cohorte, umbral o normalización se compara con el arnés y se documenta en ADR-0048; no conviertas la tabla en una profecía usando las mismas voces para calibrar y reportar. La normalización no es universal: ayuda al CAM++ zh-cn (11,6 % → 5,2 %) pero empeora al avanzado (1,2 % → 1,9 %). Ampliar la cohorte de 6 a 10 perfiles tampoco cambió el EER: el límite es el dominio, no el número de perfiles.
- **La exportación es un contrato.** `SpeakerCorpusExportTest` (gated con `FIFO_SPEAKER_EXPORT_DIR`) escribe WAV mono a 16 kHz PCM16 y `manifest.jsonl` con split, locutor, rol, condición, sílabas y duración. `tools/speaker/extract_embeddings_sherpa.py` (dev-only) extrae embeddings y `tools/speaker/score_embeddings.py` los calibra/puntúa sin meter ONNX ni torch en la app. No se commitea ningún WAV, embedding ni peso.
- **La barra para enviar es de campo.** Un proveedor real debe medir EER/FRR/FAR por condición y duración, cumplir el presupuesto de 400 ms, no subir audio ni embeddings y superar el arnés con grabaciones reales. Un buen número en voz sintética no basta.
- **El corpus de campo tiene contrato.** `tools/speaker/build_field_manifest.py` define `<locutor>/enroll/*.wav` y `<locutor>/test/<condición>/*.wav`, exige un plan con locutores disjuntos en `calibration`/`evaluation`, audio mono a 16 kHz PCM16 y al menos 3 frases por enrolado. El manifiesto entra en las mismas herramientas de extracción y scoring; no inventes splits, no mezcles voces entre calibración y evaluación y no commitees el audio.
- **Pruebas de verdad:** `SpeakerIdentityPolicyTest` (tabla de verdad, spoofing y prompt), `SpeakerIdentityProviderTest` (enrolamiento, coseno, modelo distinto, borrado y detector de spoofing en paralelo), `SpeakerIdentityIntegrationTest` (turno, `Noop`, timeout y fallo), `EncryptedVoiceProfileStoreTest` (cifrado, corrupción, consentimiento y borrado) y `SpeakerVerificationHarnessTest` (corpus sintético, calibración disjunta y métricas).

## Telefonía

- **La telefonía vive fuera de `VoiceSession`.** La sesión solo recibe `callState` y entrega la acción por `onCallAction`; no contesta, no cuelga y no toca `AudioManager`.
- **`FifoCallManager` es `internal` y no tiene efectos globales en el constructor.** Enlaza/desenlaza acciones con `bindRepositoryActions()`/`unbindRepositoryActions()`.
- **Un solo manager en el camino nuevo.** `AndroidTelephonyPort` es la única clase que lo construye; no crees managers desechables en receivers o skills.
- **Contestar/colgar por voz exige la palabra clave completa:** “Fifo contesta” / “Fifo cuelga”. “Contesta” a secas no es válido. `UtterancePolicy.classifyCallAction()` es el único clasificador; el legacy y el camino nuevo lo comparten.
- **El control de llamada se evalúa después del escudo de eco.** El anuncio de timbrado contiene las mismas frases; no dejes que el eco conteste.
- **Conocido vs desconocido es de primera clase.** `CallSnapshot` no debe colapsar un llamante desconocido en un nombre inventado.

## Contexto de llamante

- **Anuncia local primero.** `CallerContextProvider.local()` es síncrono; nunca esperes al backend para decir el nombre que ya conoce el teléfono.
- **Enriquece en paralelo con timeout.** `enrich()` usa ~800 ms; si falla o tarda, se conserva el contexto local.
- **No leas `recentTopics`, `contextualNotes` ni `suggestedFollowup` en el timbrado.** Usa `CallerContext.announcementText()`; el resto es material para cuando el usuario pregunte.
- **Un desconocido sigue siendo desconocido.** Un caller ID con nombre no es un contacto conocido; no inventes parentesco ni confianza.
- **Usa `CallerContextCoordinator` como observador de `TelephonyPort`.** No metas telefonía dentro de `VoiceSession`.
- **Pruebas de verdad:** `CallerContextProviderTest` y `CallerContextCoordinatorTest` cubren conocido/desconocido, timeout, cancelación y anuncio sin temas recientes.

## Memoria y reparación

- **El turno se journaliza antes de hablar.** `VoiceSession` llama a `SessionMemory.recordTurn()` antes de `speakResponse`. No muevas la escritura después del TTS: un cierre de la app no puede perder lo que el usuario ya escuchó.
- **`MemoryCoordinator` es el dueño de la cola pendiente.** La cola vive en `conversation_journal` (SQLite), no en RAM. Un turno solo pasa a `CONSOLIDATED` cuando el consolidador devuelve `true`; los fallos conservan `attempts`, `nextAttemptAt` y backoff exponencial.
- **`VoiceSession` solo ve `SessionMemory`.** No le agregues SQLite, HTTP, backend ni reloj de pared. La composición de `SqliteConversationJournal` y `RepositorySessionMemoryConsolidator` vive en `VoiceSessionPipeline`.
- **No consolides desde el facade legacy.** La memoria durable es del camino `VoiceSessionPipeline`; `VoicePipelineManager` no construye un segundo coordinador ni duplica la cola.
- **La reparación usa ±1 turno y una ventana de ~10 s.** `ConversationRepairPolicy` decide; si el turno anterior está dentro de la ventana, la corrección se locuta y reemplaza cualquier TTS en curso. Si llega tarde, se guarda en diario/OLED pero no se habla.
- **Un “No” aislado no es una corrección.** Solo cuenta cuando el turno anterior quedó `needsRepair`. No conviertas una respuesta natural a una pregunta en una reparación.
- **Pruebas de verdad:** `MemoryCoordinatorTest`, `ConversationRepairPolicyTest` y `VoiceSessionMemoryTest` cubren durabilidad, backoff, reinicio, journal antes de hablar y reparación.

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
- La memoria durable, la cola de pendientes y la reparación están descritas en `docs/adr/0011-durable-memory-journal.md`.
- La regla de respuestas dinámicas y la delegación de ubicación/orientación al LLM están descritas en `docs/adr/0039-dynamic-location-and-navigation-answers.md`.
