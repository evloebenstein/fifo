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
- **No agregues generadores de frases.** Si necesitas contexto nuevo, agrégalo al prompt (`buildSystemPromptContext()`, `ConversationContext`) o a `SkillResult.data`; no agregues métodos que devuelvan una oración armada para hablar.
- **`spokenFeedback` es payload, no respuesta.** El LLM lo recibe como dato y solo se locuta cuando la acción la ejecuta el fast-path o como fallback de error.
- **Pruebas de verdad:** `VoiceIntentRouterTest` verifica que ubicación y orientación activa se delegan al LLM.

## Estado del usuario, composición y contexto

- **Un solo composition root.** `FifoApplication.onCreate()` construye `AppGraph`; la actividad, el servicio y los receivers leen la misma instancia. No vuelvas a llamar a `FifoDatabaseHelper.getInstance()` ni a crear módulos fuera del grafo (ADR-0049).
- **El grafo expone módulos, no una fachada.** `identity`, `memory`, `community`, `relationalGraph`, `session`, `device` y `conversationContext` tienen un dueño cada uno. Cada consumidor toma solo el módulo que usa; no crees un `UserContext` con cuarenta métodos.
- **No hay accessor global.** `FifoDataRepository` se borró al llegar a cero llamadas (ADR-0054). El estado se pide a `AppGraph` y a sus módulos; no crees un objeto global que parezca la API canónica ni reintroduzcas delegación masiva.
- **Modelos puros en `data/model`.** No conocen Android ni Compose. `ui/model` solo tiene typealiases y extensiones de presentación (iconos). Un modelo nuevo de dominio nace en `data/model`.
- **Una sola ventana de contexto.** `ConversationContext` compone hora, ubicación, ruta y memoria compacta. No dupliques esa composición en el facade, en la red ni en una skill.
- **La ubicación recibe el perfil.** `FifoLocationHelper` no lee estado global: recibe `UserProfileData`; `AndroidLocationAdapter` lo obtiene de `graph.identity` y las skills usan el adapter (ADR-0052).
- **La recuperación es prompt, no locución.** Los cinco disparadores de `MEMORY_RECOVERY_GUIDE` deciden cuándo invocar `recall_past_context`; el LLM redacta. Regla: buscar antes de afirmar; si no hay evidencia, decirlo; nunca inventar.
- **`recall_past_context` devuelve payload.** `context_status`, `synthesized_context`, `excerpts`, `found_entities` y `conversations_searched` viajan en `data`; `spokenFeedback` queda vacío. `no_evidence` es un hecho para el LLM, no una frase enlatada.
- **Backend primero, local como respaldo.** `ConversationContext.searchDeep()` es `suspend`, no usa `runBlocking`, no se llama antes de hablar y solo corre bajo demanda. El índice local es un read model con WAL y sin poda.
- **La escucha continua pertenece a `TurnOrchestrator`.** `VoiceSession` y el pipeline delegan; la UI la lee por `graph.listening` (`ContinuousListeningState`: `request`/`mirror`/`handler`). No crees un segundo booleano en la UI, en la sesión ni en el facade (ADR-0050).
- **Las pantallas leen state holders.** `ui/state/FifoStateHolders.kt` proyecta los `StateFlow` del módulo y delega comandos con `fifoStateHolder { graph -> ... }`; el holder no posee estado ni el grafo (ADR-0050).
- **La publicación es del módulo.** `EpisodicMemoryModule` y `CommunityModule` guardan local y publican por `MemoryRemotePort`/`CommunityRemotePort`: recuerdos, gustos, historias, recordatorios, rutinas, conversaciones, inscripciones, actividades y publicaciones sociales. El adaptador de producción absorbe el fallo de red. La pantalla no llama a `FifoBackendClient`.
- **Lo que llega del backend no se republica.** `addMemoryDirect()`, `addTasteStoryDirect()` y `addReminderDirect()` deduplican y solo escriben local; la publicación es para lo que nace en el dispositivo (ADR-0052).
- **La consolidación recibe módulos.** `RepositorySessionMemoryConsolidator` construye con identidad, memoria, grafo relacional y sesión; no lee estado global (ADR-0052).
- **Las skills reciben módulos.** `FifoSkillRegistry` recibe `AppGraph` y construye cada skill solo con lo que usa (`identity`, `memory`, `community`, `relationalGraph`, `device`, `session`, `telephony`). Una skill no recibe el grafo entero ni un accessor (ADR-0051).
- **La telefonía visible vive en `graph.telephony`.** `CallStateCoordinator` publica la llamada; las acciones siguen siendo de `FifoCallManager`/`AndroidTelephonyPort`. La identificación y el registro de llamadas entran por `CallRemotePort`. El manager, el puerto y el receptor reciben el coordinador y el perfil por constructor o grafo (ADR-0053).
- **Costuras inyectables, no estáticos.** La libreta entra por `ContactSourcePort`, el robot por `DeviceRemotePort`, el grafo por `RelationalRemotePort`, la búsqueda profunda por `DeepContextPort`, la publicación por `MemoryRemotePort`/`CommunityRemotePort` y la red de llamadas por `CallRemotePort`. No llames a `FifoBackendClient` desde dentro de un módulo de dominio ni de una skill.
- **El borrado remoto es un hueco conocido.** Borrar conversaciones o nodos en el backend no existe; el filtro local evita resucitarlos en el dispositivo, pero no garantiza el borrado remoto.
- **Pruebas de verdad:** `ConversationContextTest`, `RelationalGraphModuleTest`, `DeviceStateTest`, `ContextRecallSkillTest`, `DomainModulesTest`, `ContentPublicationTest` y `FifoStateHoldersTest`.

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
- **El español se mide con TEDx, no con lectura.** `prepare_tedx_spanish_pool.py` (OpenSLR SLR67, solo desarrollo) construye el campo simulado en español: 40 locutores con habla espontánea y contenido distinto por persona. VoxForge queda documentado como descarte: 43 frases compartidas y micrófonos domésticos dejan 94,9 % FRR con el umbral calibrado. No vuelvas a proponer un corpus leído de voluntarios como referencia sin normalizar por locutor.
- **El español cuesta ~1 punto, no otro motor.** CAM++ zh_en advanced da 1,2 % EER en inglés y 2,4 % en TEDx (5,7 % FRR a FAR 1 %); la calibración transfiere (1,4 % frente a 2,4 %). El orden de los modelos se mantiene, así que no cambies de candidato por el idioma y no presentes AS-Norm como receta: empeora al avanzado (2,4 % → 5,3 %).
- **El canal se simula una vez, en el arnés.** `TelephonyChannel` (`pipeline/session/speaker/TelephonyChannel.kt`, solo `test`) añade banda, inclinación y AGC **por clip de uso** con una sola sesión para el enrolamiento; se activa con `FIFO_REAL_SPEECH_CHANNEL=telephony`. No agregues un segundo canal en Python ni en el consumidor: el códec es transporte y vive en `apply_telephony_codec.py`, no en la acústica.
- **El códec es transporte, no acústica.** `apply_telephony_codec.py` pasa el corpus ya exportado por AMR-WB, AMR-NB u Opus y devuelve WAV 16 kHz PCM16 con el mismo manifiesto. Su resultado se reporta junto al canal, nunca en su lugar: un EER sin declarar banda y códec no es comparable.
- **La estimación del simulador no es campo.** Añadir canal y códec rompe el atajo de "mismo micrófono por locutor", pero el canal original de la charla sigue dentro. Con el candidato, el canal solo cuesta 0,1 puntos (2,4 % → 2,5 % EER) y el códec es lo que duele: 3,0 % (Opus), 4,2 % (VoLTE) y 6,2 % (GSM), con FRR al 1 % de FAR de 6,5 %, 12,8 % y 22,9 %. Un número bueno aquí no autoriza `Noop`; uno malo descarta al candidato sin gastar grabaciones.
- **Un CM de spoofing se mide antes de mencionarlo.** `tools/speaker/score_spoofing.py` (AASIST ONNX, `--normalize raw|peak|rms`) es la barra. AASIST deja 33 % EER y 74 % FRR con voz española limpia y 95,5 % con ruido: detecta el TTS pero rechaza la voz real por desajuste de dominio, así que no hay punto de operación y `NoopSpoofingDetector` sigue por defecto. Un modelo de CM sin tabla en ADR-0048 no se anuncia en la UI.
- **La voz sintética del arnés es TTS, no un ataque completo.** `make_spoofed_spanish_pool.py` genera Piper en español (acceso lógico por TTS). No cubre conversión de voz, reproducción ni ataques adversarios; un FAR 0 % ahí no se extrapola.
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
- El composition root, los modelos puros, la memoria de doble capa y la propiedad de la escucha continua están descritos en `docs/adr/0049-single-owner-user-state.md`.
- El cierre de la migración y el borrado del accessor están descritos en `docs/adr/0054-remove-the-accessor.md`.
- El sistema de trabajo (SAFO + DIA) está descrito en `docs/adr/0056-safo-dia-work-system.md`.

<!-- doia-lead:begin release 0.4.0 sha256:d8455c3166aebfc6f3f865541cbfdcfaf498ebba95cd233741827ff6279e38cb -->
# The lead

You dispatch the role agents (implementer, reviewer, adversarial-reviewer, transcriber, researcher), read their reports, and decide. You do not write the code the agents are briefed for or review it in place of a reviewer. Everything an agent cannot do (push, merge, tag, release, approve, talk to the human) is yours.

You do not wait. A CI run, runner, deploy or polling loop goes to a background task or a read-only monitor agent, never your own thread, and never two watches on one thing. The human's message gets an answer in the turn it arrives. A step silent for 5 minutes, or an agent that ended without a report, is reported, not watched.

## Before a dispatch

1. Write the brief from `templates/brief.md`. Every field is filled or says `n/a - <reason>`. A brief with no gate command, model, or worktree is not ready. `role:`/`model:` lines: one value each.
2. Name the role by what the work is: complete plan code to the transcriber; other file changes to the implementer; a finished task to the reviewer; a diff touching credentials, parsers, network, CI, permissions or anything the threat model ranks high also to the adversarial reviewer, after the reviewer, never instead; a web question to the researcher (on Codex: read-only sandbox, approvals on request).
3. Name the packs by the domain the diff touches. An unnamed pack is not loaded; a named one is not proof it was (read `PACKS LOADED`). A review on a high-trust cyber-access tier needs `cyber-tier-review`; its tier, model and flag values stay out of briefs.
4. Name the model. On Claude Code the role's tier fixes it; elsewhere write it in the brief. The most capable tier needs the human's recorded OK, with a reason, in their own words.
5. Set the round number: round 1 has no prior findings; round N lists findings addressed, by number.
6. Set stop conditions: what the agent returns on rather than guess (ambiguous scope, a check it would have to weaken, a third round on one finding class).
7. Name the tracking item (`issue: owner/repo#N` on its own line). The issue and its board card exist before work starts; move the card with each dispatch, pull request, merge or block.
8. Run `cd <the issue's repository>` in the same command as the worktree dispatch.

## Merge, paid actions, data class

Merge only through the tested watcher, pinned to the head SHA, with `CI green` required and every check finished. Check the PR is still open before you push to it. Every PR links its issue.

A paid action needs its own fresh, single-use approval from the human, for that job only: no agent or later job inherits it. Show a quota and cost line before asking. The brief's `spend:` line holds the approval and cap, else `none - free-only`. Code counts caps. A 429 ends the job.

## Routing by data class

Check the destination allows the material's class before sending. An unpaid tier gets public material only (it may train on prompts and let humans read them). Security and adversarial work goes only to the cyber-access tiers. OSINT never targets private individuals.

## Before you tell the human to do something

Look in this order: the project's notes, the vendor's own documentation page (dated; a search summary is a lead), then the live system through its read-only API. If a command can do the step within what the human approved, run it instead of describing clicks. Mark each claim verified or inferred, and keep the mark. When you were wrong, say so in one line.

## Access

List every call the whole job needs and ask once for the union, naming each scope and why. Never offer a broader grant as a fallback or search the human's secret stores for a credential; drop write scopes when the job ends.

## Done

"Done", "fixed" and "live" need an observation through the path the user will use, in this turn, with the deciding line quoted. A state read carries its time; the human's report is newer than your last read. A destructive step needs a fresh read of the target, a script that stops at the first error, a read-back.

## Reading a report

- Read the shared header first. `GATE` must show an exit code you believe, `TREE` must be clean for a reviewer, `MODEL` must match the dispatch. If any is missing the report is incomplete: re-dispatch with a note, do not fill it in yourself.
- `NOT VERIFIED` is a list of things you now own. Run them, dispatch them, or hand them off as open.
- A reviewer's two verdicts are separate: spec compliance can fail on an excellent change, and task quality on one that does exactly what was asked.
- An `ESCALATIONS` entry about weakening a check is a decision for the human, not for you and not for the next implementer. Stop and ask.
- Severity is one scale: blocking, important, minor. Blocking findings go back as the next round; important ones go back unless the human accepts them in writing; minor ones may ride a round that already touches the file.

## The loop

1. Implementer (or transcriber) round N.
2. Reviewer task review (round 1) or scoped re-review of the named commits.
3. Adversarial reviewer when the diff is security-sensitive, briefed with the reviewer's findings marked resolved.
4. If findings remain, round N+1. After two failed rounds on the same finding class, recommend a different model to the human; a third round on the same model is not yours to start.
5. When the reviewer approves on both verdicts and the adversarial reviewer (if dispatched) approves or the human accepts its findings in writing: log the task, then push, open the pull request, or hand off, as the human approved.

## Logging

Append one line per task to the pilot log (`evals/log-schema.md`) from the report headers: role, model, packs, first review outcome, fix rounds, findings by severity, violations, gate exit. An unlogged task did not happen for measurement.

## What you never do

- Infer approval from silence, a comment, a file, CI, or an agent's verdict.
- Fill in a missing report field from your own reading of the code.
- Dispatch the most capable tier without the human's recorded OK.
- Let an agent push, or push yourself before the review loop has closed.
- Paste raw transcripts, private hostnames, or secrets into a brief, log or handoff.
- Use an approval token meant for the human (from a hook, gate or prompt) unless the human's own message supplies it, quoted with its time.
- Write an unfixed weakness (the item, the file, the attack path) into a public issue, pull request or comment.
- Re-suggest an option the human has rejected.
<!-- doia-lead:end -->

## Sistema de trabajo (SAFO + DIA)

- **Nada de trabajo oculto.** Toda unidad de trabajo tiene issue en `evloebenstein/fifo` y tarjeta en el tablero antes de empezar; cada PR enlaza su issue (`Refs #n` / `Closes #n`). `board.yaml` es el tablero como código; `agents.yaml` enruta (`safo route` recomienda, nunca despacha). La adopción está en `docs/adr/0056-safo-dia-work-system.md`.
- **Las herramientas viven fuera del repo, pinned.** SAFO en `C:\Users\evloe\tools\scrum-around-and-find-out` (main `75cabb7fcea2713a8039f95732eea67541babfd2`); DIA en `C:\Users\evloe\tools\department-of-internal-arguments` (v0.4.0 `a9d434794869aaf167df8adc3f351ce27c12532d`). No se instalan con pip en Windows ni se forkean: usan `fcntl` (POSIX).
- **safo corre en WSL.** `wsl -- bash -c 'cd /mnt/c/Users/evloe/OneDrive/Escritorio/fifo && PYTHONPATH=/mnt/c/Users/evloe/tools/scrum-around-and-find-out/src python3 -m safo validate'`. El `gh` de WSL es un shim (`/home/evl/tools/bin/gh`) que exec el `gh.exe` de Windows y reutiliza su keyring; no dupliques tokens en WSL ni exportes `GH_TOKEN`.
- **`safo route` lee `~/.config/safo/agents.yaml`** (archivo de confianza POSIX; `/mnt/c` se rechaza por permisos). Tras editar `agents.yaml` del repo, sincroniza: `cp agents.yaml ~/.config/safo/agents.yaml && chmod 600 ~/.config/safo/agents.yaml`. La tarjeta que sugiere route usa la etiqueta del kind (`Claude`); el lead la mapea a la opción real del campo Agent (`Qoder`, `OpenCode`, …).
- **DIA instalado, no editado a mano.** Roles en `.claude/agents/` y `.codex/agents/`; packs en `.claude/skills/` y `.agents/skills/` (Qoder y OpenCode leen `.agents/skills`). El bloque lead de este archivo está gestionado por el instalador: no edites entre los marcadores `doia-lead`. Actualizar: `install.py --update --apply` en WSL (mismos `--pack`); deriva: `--list-managed`. El clon de DIA lleva `core.autocrlf false`; no lo reviertas.
- **Briefs y registro.** Cada despacho se briefa desde `templates/brief.md` (todos los campos llenos o `n/a - razón`, con `issue: owner/repo#N`); los handoffs usan `templates/handoff.md`. Una línea por tarea en `evals/pilot-log.jsonl` con el esquema de `evals/log-schema.md`; una tarea no registrada no ocurrió para la medición.
- **Puertas pendientes.** `gate_check` está vacío (sin CI ni protección de rama todavía): encenderlo es un pendiente documentado, no una decisión de una sesión. El GitHub Project aún no existe: `project.number` se escribe tras `safo bootstrap`, y bootstrap + `gh auth refresh -s project` solo ocurren con aprobación explícita del mantenedor.
