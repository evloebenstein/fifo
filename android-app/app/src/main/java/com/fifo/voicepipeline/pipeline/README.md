# ⚙️ Voice Pipeline Core (`com.fifo.voicepipeline.pipeline`)

Núcleo de la arquitectura de voz de Fifo. La decisión vigente está en **ADR-0010**
(`docs/adr/0010-voice-session-pipeline-flag.md`) y **ADR-0011**
(`docs/adr/0011-durable-memory-journal.md`).

El paquete contiene **dos caminos excluyentes**. Solo se construye uno por proceso:

| Camino | Cuándo se usa | Qué lo compone |
| :--- | :--- | :--- |
| `SESSION` | Release con `VOICE_SESSION_ENABLED=true` **y** STT de nube configurado | `VoiceSessionPipeline` + `VoiceSession` + `CaptureArbiter` + `TelephonyPort` + memoria durable |
| `LEGACY` | Debug, o release sin claves de STT | El camino anterior dentro de `VoicePipelineManager` |

`VoicePipelineModeSelector.select(flagEnabled, hasCloudStt)` es la única autoridad de
selección. Si falta STT de nube, cae a `LEGACY` para que Fifo no quede sordo. El flag
se elimina junto con el legacy en la release siguiente.

---

## Módulos

| Archivo / Carpeta | Rol |
| :--- | :--- |
| [`PipelineState.kt`](PipelineState.kt) | `PipelineState`: `DISCONNECTED · SLEEPING · IDLE · LISTENING · PROCESSING · SPEAKING · ERROR` y `MicSource`: `ESP32 · PHONE`. |
| [`VoicePipelineMode.kt`](VoicePipelineMode.kt) | `VoicePipelineMode` (`LEGACY`/`SESSION`) y el selector puro que evalúa el flag y el STT de nube. |
| [`VoicePipelineManager.kt`](VoicePipelineManager.kt) | **Fachada única para `MainActivity`/`FifoVoiceService`.** En `SESSION` espeja los `StateFlow` y delega en `VoiceSessionPipeline`; en `LEGACY` conserva el camino anterior. No crea una segunda sesión, VAD, TTS ni manager de telefonía. Comparte `UtterancePolicy` con la sesión y restaura la ruta persistida en `start()` (ADR-0041, ADR-0042). |
| [`NavigationNoticeQueue.kt`](NavigationNoticeQueue.kt) | Cola pura-JVM de avisos autónomos: un solo drenaje activo, el aviso más reciente reemplaza a los anteriores y `poll()` vacío libera la tanda (ADR-0041). |
| [`NavigationNoticeDispatcher.kt`](NavigationNoticeDispatcher.kt) | **Módulo profundo de locución autónoma (ADR-0041).** Encadena cola, espera sin plazo, descarte por destino y narración con fallback. Recibe espera/ruta/narrador/habla como costuras y se prueba en JVM con `runTest`. |
| [`session/VoiceSessionPipeline.kt`](session/VoiceSessionPipeline.kt) | **Raíz de composición del camino nuevo.** Es el único lugar que construye `VoiceSession`, los adaptadores de audio, `AndroidTelephonyPort` y `CallerContextCoordinator`, además de `MemoryCoordinator` + `SqliteConversationJournal` + `RepositorySessionMemoryConsolidator`. |
| [`session/VoiceSession.kt`](session/VoiceSession.kt) | **Sesión conversacional profunda.** Recibe tramas, usa el RMS solo para boca/OLED, transcribe, decide barge-in/eco/acciones de llamada, journaliza antes de hablar y pide consolidación al dormir. Alimenta el detector acústico solo dormida y consume el candidato con ventana de 8 s (ADR-0043). |
| [`session/TurnOrchestrator.kt`](session/TurnOrchestrator.kt) | Máquina de estados del **ConversationalTurn**: wake-word, ventana de follow-up (~14 s), barge-in por contenido y auto-sueño (~25 s, o ~120 s en modo continuo). Delega las reglas léxicas en `UtterancePolicy`. |
| [`session/UtterancePolicy.kt`](session/UtterancePolicy.kt) | **Única autoridad léxica (ADR-0042).** Política pura de invocación, consulta, saludo, silencio, sueño, eco, pregunta y control de llamada (`CallAction`). Incluye la fusión acústica + léxica `resolveWake()` (ADR-0043). La comparten `TurnOrchestrator` y el facade legacy; se prueba en JVM con `UtterancePolicyTest`. |
| [`session/WakeWordDetector.kt`](session/WakeWordDetector.kt) | **Costura del candidato acústico (ADR-0043).** Devuelve `WakeWordDetection` (score, instante, `modelId`); no abre micrófono, no tiene VAD, no habla y no autoriza acciones. `NoopWakeWordDetector` es el valor por defecto. |
| [`session/SpeechSegmenter.kt`](session/SpeechSegmenter.kt) | **Dueño único del VAD, el pre-roll (~480 ms) y el WAV** en el camino nuevo. No conoce Android, TTS, UI ni telefonía. |
| [`session/CaptureArbiter.kt`](session/CaptureArbiter.kt) | **Dueño único de la ruta de captura.** Decide `STOPPED`, `PHONE_NORMAL`, `PHONE_CALL`, `BLE_MIC` o `NATIVE_RECOGNIZER`; solo reconfigura hardware cuando la ruta efectiva cambia y descarta tramas tardías. |
| [`session/AndroidCaptureHardware.kt`](session/AndroidCaptureHardware.kt) | Implementación Android de `CaptureHardware`: envuelve `PhoneMicRecorder` y, cuando corresponde, el reconocedor nativo. |
| [`session/AudioSeam.kt`](session/AudioSeam.kt) | Costuras `AudioInboundAdapter` / `AudioOutboundAdapter` para desacoplar PCM de la máquina de estados. |
| [`session/AndroidAudioHardwareInboundAdapter.kt`](session/AndroidAudioHardwareInboundAdapter.kt) | Único adaptador de **entrada** de producción. Traduce eventos de micrófono/BLE/llamada a `CaptureEnvironment` y publica lo que decide el árbitro. |
| [`session/AndroidAudioHardwareAdapter.kt`](session/AndroidAudioHardwareAdapter.kt) | Adaptador de **salida** únicamente: Android TTS. No captura audio, no toca `AudioManager` y no enruta llamadas. |
| [`session/SpeechCognitionSeam.kt`](session/SpeechCognitionSeam.kt) | Costura de cognición (ADR-0006): STT Whisper + LLM Groq (`qwen/qwen3.8-27b`) con tool-calling, `ConversationContextProvider`, `generateRepairTurn()` y adaptador JVM de pruebas. |
| [`session/SpeechOutboundEngine.kt`](session/SpeechOutboundEngine.kt) | Sanitización y despacho de la respuesta al adaptador de salida. |
| [`session/MemoryCoordinator.kt`](session/MemoryCoordinator.kt) | Implementación de `SessionMemory`: diario antes de hablar, cola durable `PENDING`, reintentos con backoff (30 s → 15 min) y restauración de contexto. |
| [`session/ConversationRepairPolicy.kt`](session/ConversationRepairPolicy.kt) | Política pura de reparación ±1 turno: corrección explícita, “No” solo tras `needsRepair` y ventana de ~10 s para locutar. |
| [`session/SpeakerIdentity.kt`](session/SpeakerIdentity.kt) | **Dominio de identidad de locutor (ADR-0048).** `SpeakerRelation` (`OWNER · KNOWN · UNKNOWN · UNCERTAIN · UNAVAILABLE`), `SpeakerIdentity`, evidencia, `VoiceProfile`, `SpeakerPolicyConfig`, la política pura `SpeakerIdentityPolicy` y `SpeakerPromptContext`. Personaliza; no autentica. |
| [`session/SpeakerIdentityProvider.kt`](session/SpeakerIdentityProvider.kt) | **Costura de identidad (ADR-0048).** `SpeakerIdentityProvider` + `NoopSpeakerIdentityProvider` por defecto, `SpeakerEmbeddingExtractor`, `VoiceProfileStore`, `EmbeddingSpeakerIdentityProvider` y `SpeakerEnrollment`. Recibe el WAV ya cerrado; no abre micrófono ni tiene VAD. |
| [`session/EncryptedVoiceProfileStore.kt`](session/EncryptedVoiceProfileStore.kt) | **Almacén cifrado de perfiles (ADR-0048).** Blob versionado, escritura atómica, corrupción tolerada como "sin perfiles" y `ConsentAwareVoiceProfileStore` que bloquea lectura/enrolamiento sin consentimiento pero siempre permite borrar. |
| [`session/AndroidKeystoreProfileCipher.kt`](session/AndroidKeystoreProfileCipher.kt) | **Cifrado de producción (ADR-0048).** AES-256-GCM con clave del Android Keystore, sin dependencias nuevas; `androidEncryptedVoiceProfileStore()` compone archivo + cifrado + consentimiento. |

`AudioInboundFilter` e `InboundFrameResult` fueron eliminados. No existe un corte de
TTS por RMS.

---

## Selección y composición

```
FifoVoiceService / MainActivity
              │
              ▼
    VoicePipelineManager  (fachada única)
              │
              ▼
 VoicePipelineModeSelector.select(flag, hasCloudStt)
         │                               │
         ▼                               ▼
      LEGACY                          SESSION
 (camino anterior)          VoiceSessionPipeline
                                      ├─ VoiceSession
                                      ├─ AndroidAudioHardwareInboundAdapter
                                      │    └─ CaptureArbiter → PhoneMicRecorder / BLE / nativo
                                      ├─ AndroidAudioHardwareAdapter (TTS)
                                      ├─ AndroidTelephonyPort → FifoCallManager
                                      ├─ CallerContextCoordinator
                                      └─ MemoryCoordinator → SQLite + consolidador
```

`VoicePipelineManager.start()` evalúa la selección después de crear `CloudApiClient`.
En `SESSION` retorna **antes** de inicializar el TTS legacy, `NativeSpeechRecognizer`,
`PhoneMicRecorder` o el VAD del facade. En `LEGACY` no construye nada del camino nuevo.

---

## Flujo del turno en `SESSION`

```
SLEEPING ──[WakeWordTrigger]──► LISTENING ──[fin de frase]──► PROCESSING ──► SPEAKING
    ▲                              │                              │              │
    │                              │ follow-up (~14 s si ?)       │              │
    │                              └──────────────────────────────┴──────────────┘
    │                              │ auto-sueño (~25 s / ~120 s continuo)
    └──────────────────────────────┴──────────────────────────────────────────────
```

1. `CaptureArbiter` entrega tramas al adaptador de entrada.
2. `SpeechSegmenter` aplica VAD, pre-roll y cierra la frase en un WAV.
3. `VoiceSession` transcribe y aplica el escudo de eco.
4. Si hay orden de llamada, se entrega por `onCallAction`; la sesión no contesta ni cuelga.
5. Si es un turno normal, genera la respuesta y **registra el turno en `SessionMemory` antes de `speakResponse`**.
6. Al dormir o cerrar la pipeline, `MemoryCoordinator` drena la cola y consolida.

---

## Interpretación de la locución (ADR-0042)

`UtterancePolicy` es la única autoridad léxica del producto y la comparten los dos
caminos:

- invocación ("Fifo" y variantes fonéticas, sin aliases que despiertan por error);
- extracción de la consulta y detección de saludo puro;
- silencio, sueño, eco, pregunta y `CallAction` (`ANSWER`, `HANGUP`,
  `CALLER_STATUS`).

`TurnOrchestrator` solo aporta el estado conversacional (frase hablada, follow-up,
barge-in) y `VoicePipelineManager` delega en las mismas funciones. La política
clasifica; no redacta ni locuta.

### Invocación en dos etapas (ADR-0043)

La invocación ya no depende solo del STT. `VoiceSession` alimenta un
`WakeWordDetector` con las tramas que captura `CaptureArbiter` y, **solo mientras
Fifo duerme**, guarda el candidato que devuelve. Cuando llega la transcripción,
`UtterancePolicy.resolveWake()` decide:

| Acústica | Transcripción | Resultado |
| :--- | :--- | :--- |
| — | contiene "Fifo" | `CONFIRMED` (turno normal) |
| — | sin "Fifo" | `IGNORE` (audio ambiental) |
| candidato | texto sin "Fifo" | `ACOUSTIC_ONLY` (la transcripción es la consulta) |
| candidato | vacío | `RETRY` (despierta sin hablar) |

Despertar por acústica no locuta: la frase la compone el LLM. La acústica nunca
autoriza `ANSWER`/`HANGUP` y no toca el barge-in, que sigue exigiendo invocación
transcrita. El detector es `Noop` hasta que exista un modelo medido; cambiarlo es
una línea en `VoiceSessionPipeline`.

El arnés que mide esa sustitución vive en `app/src/test/.../wakeword/` (ADR-0044):
corpus sintético determinista, detector de referencia por plantilla espectral y
tabla de FRR por SNR, negativos difíciles, falsos aceptos/hora y latencia. No se
envía con la app y no certifica el rendimiento de campo; fija la barra que debe
superar el modelo entrenado.

Ese arnés tiene dos corpus separados (ADR-0045). El **base** es de campo cercano
y sala seca, y su tabla se congela. El **de campo** pasa la voz por una sala
sintetizada con el método de imagen, aplica el efecto Lombard y deriva locutores
por VTLP antes de mezclar ruido. Con plantillas secas el FRR en un despacho
reverberante es 87,5 %; con plantillas de la misma sala cae a 12,5 %. La lección
no es "la plantilla es mala", sino que el desajuste de canal domina: un modelo
entrenado con salas es la vía, no bajar el umbral.

### Entrenar sobre el corpus: resultado negativo (ADR-0046)

El intento de convertir ese diagnóstico en un detector está documentado en
ADR-0046. Se probaron tres enfoques con locutores y posiciones de sala que no
aparecen en la evaluación:

| Enfoque | FRR despacho | Negativos aceptados | FA/h |
| :--- | ---: | ---: | ---: |
| Regresión logística (40 locutores) | 100 % | 7,0 % | 343 |
| Red 257→24→1 (12 locutores) | 12,5 % | 98,4 % | 1.924 |
| Plantillas de sala, umbral 0,65 | 0,0 % | 9,4 % | 26,4 |

Ninguno cumple el punto de trabajo de ≤1 FA/h. La red memoriza a sus 12
locutores y dispara en habla no vista; la logística no aprende y falla incluso
en el corpus base (73,1 % de FRR y 834 FA/h). Las plantillas de sala arreglan el
canal, pero dejan el salón y la cocina al 100 %: el ruido sigue ganando. La causa
es la representación (el vector CMVN no es un embedding) y la falta de voces
reales, no el umbral ni el tamaño del modelo.

Los experimentos quedan en `test`, reproducibles y **apagados por defecto**:

```powershell
$env:FIFO_WAKEWORD_EXPERIMENT="1"
.\gradlew.bat testDebugUnitTest --tests "*WakeWordLearningTest" --tests "*AugmentedTemplateTest"
```

Sin esa variable JUnit los omite. `NoopWakeWordDetector` sigue siendo el valor
por defecto; el siguiente paso es openWakeWord/microWakeWord con embeddings y un
corpus multi-locutor, medido contra la misma barra.

### Exportar y entrenar fuera de la JVM (ADR-0047)

El corpus se exporta a WAV mono 16 kHz PCM16 + `manifest.jsonl` con
`WakeWordCorpusExportTest` (apagado salvo con `FIFO_WAKEWORD_EXPORT_DIR`). Sobre
esa exportación, `tools/wakeword/train_mel_cnn.py` entrena un log-mel de 40
bandas + CNN pequeña con 100 locutores y conversaciones en todas las condiciones
con ruido. Es una herramienta de desarrollo: `torch`/`numpy` no entran en la app.

| Enfoque | FRR campo | Negativos difíciles | FA/h |
| :--- | ---: | ---: | ---: |
| Log-mel + CNN, 1 ventana | 79,2 % | 1,6 % | 0,0 |
| Log-mel + CNN, 2 ventanas | 33,3 % | 6,2 % | 13,2 |
| Log-mel + CNN, oráculo con ≤1 FA/h | 41,7 % | 3,9 % | 0,0 |

La representación mejora el resultado de ADR-0046 (100 % de FRR), pero no
alcanza el punto de trabajo. El falso acepto está en el salón y la cocina se
come el 62,5 % de los positivos. La causa ya no es solo la representación: el
corpus de campo tiene 0,076 h de conversación y un solo evento son 13,2 FA/h.
`NoopWakeWordDetector` sigue por defecto; el siguiente paso son voces reales o
TTS y decenas de horas de negativo, no otro barrido de umbral.

---

## Identidad de locutor (ADR-0048)

`VoiceSession` atribuye cada turno a una voz sin abrir una segunda captura:

```
SpeechSegmenter ──► WAV ──┬──► STT (Whisper) ──┐
                          │                     ├──► VoiceSession ──► LLM
                          └──► SpeakerIdentityProvider (400 ms) ──► SpeakerIdentityPolicy
                                        │
                                        ▼
                              OWNER · KNOWN · UNKNOWN · UNCERTAIN · UNAVAILABLE
```

- La identificación corre **en paralelo al STT y con timeout de 400 ms**. Si
  tarda, falla o no hay perfiles, el turno sigue como `UNAVAILABLE`; nunca
  bloquea la conversación.
- `EmbeddingSpeakerIdentityProvider` carga los perfiles del modelo activo,
  extrae el embedding y compara por coseno. La decisión final es de
  `SpeakerIdentityPolicy`; el proveedor solo produce evidencia ordenada.
- `SpeakerPromptContext` inyecta la etiqueta en el system prompt. El LLM decide
  cómo tratar el turno; no hay locuciones de identidad ni una segunda autoridad
  léxica.
- La identidad **no autentica**: llamadas, pagos y datos privados siguen
  exigiendo confirmación explícita y la política de telefonía no cambia.
- `NoopSpeakerIdentityProvider` es el valor por defecto. Se cambia cuando exista
  un extractor real y un `VoiceProfileStore` cifrado; `VoiceSessionPipeline` es
  el único lugar que lo elige.
- `EncryptedVoiceProfileStore` + `AndroidKeystoreProfileCipher` implementan el
  almacén: blob versionado con AES-256-GCM, escritura atómica y corrupción
  tolerada. `ConsentAwareVoiceProfileStore` no lee ni enrola sin consentimiento,
  pero siempre permite borrar. `androidEncryptedVoiceProfileStore()` compone las
  piezas; aún no se cablea porque falta la UI y el extractor.
- Solo se guardan embeddings (centroides), nunca audio crudo. Los perfiles de
  otro `modelId` se ignoran y no se sincronizan con el backend.
- El arnés vive en `app/src/test/.../speaker/` y se activa con
  `FIFO_SPEAKER_EXPERIMENT=1`. Mide un extractor de referencia sobre 8 locutores
  sintéticos de evaluación y 4 de calibración, disjuntos. El umbral se fija a un
  FAR objetivo con los de calibración y se reporta con los de evaluación; nunca
  se calibra y reporta sobre las mismas voces. **No certifica campo**: en limpio
  el EER es 8,3 %, pero a FAR objetivo 1 % el FRR global de evaluación es 70,8 %
  y el EER global 31,8 %. Sirve para regresión y para decidir cuándo hace falta
  un modelo entrenado, no para afirmar un EER real.
- `SpeakerCorpusExportTest` (gated con `FIFO_SPEAKER_EXPORT_DIR`) exporta 120
  clips WAV + `manifest.jsonl`. `tools/speaker/extract_embeddings_sherpa.py`
  extrae embeddings con sherpa-onnx y `score_embeddings.py` calibra con el split
  de calibración y reporta EER/FRR/FAR por condición y duración. No se commitean
  WAV, embeddings ni pesos y no se añade ONNX/torch a la app.
- Los primeros modelos reales medidos (WeSpeaker CAM++/ResNet34 y 3D-Speaker
  CAM++) quedaron entre 42 % y 50 % de EER global, peor que la referencia
  espectral. En limpio ResNet34 baja a 9,5 %, pero el ruido sintético (5–10 dB,
  sala + Lombard) los hunde. **El corpus no ordena motores reales**: sus números
  no eligen ni descartan un modelo, solo validan el contrato y detectan
  regresiones.
- El control con voz real (LibriSpeech dev-clean, 78 clips de 10 locutores,
  `tools/speaker/build_librispeech_control.py`) da **0,0 % EER y 0,0 % FRR/FAR**
  con los mismos modelos y el mismo pipeline. Confirma que el código funciona y
  que el problema del 42–50 % es el corpus sintético, no el scoring. Es lectura
  limpia: no mide campo ni el móvil.
- El corpus de campo tiene layout y plan propio (`tools/speaker/build_field_manifest.py`):
  `<locutor>/enroll` y `<locutor>/test/<condición>`, splits disjuntos y audio
  mono 16 kHz PCM16. El script valida el reparto y produce el mismo manifiesto;
  falta grabar, no inventar el formato.

---

## Guía autónoma de navegación (ADR-0040 y ADR-0041)

`FifoNavigationManager` pertenece al registro de skills y publica
`NavigationNotice` estructurados. `VoicePipelineManager` solo entrega el hecho a
`NavigationNoticeDispatcher`, que:

1. encola el aviso en `NavigationNoticeQueue`: el más reciente reemplaza a los
   anteriores;
2. espera, **sin plazo**, a que el pipeline no esté en `PROCESSING` ni
   `SPEAKING` (observa el `StateFlow`, sin sondeo);
3. descarta solo los avisos de un destino que ya no está activo (`ARRIVED` se
   anuncia aunque la ruta se haya cerrado);
4. pide la locución a `CloudApiClient.narrateNavigationNotice()` y usa
   `NavigationNotice.fallbackSpeech()` solo si el LLM no responde;
5. locuta por `speakNavigation()`: `session.speakResponse()` en `SESSION` o
   `speakAutonomousMessage()` en legacy.

El motor de navegación no redacta frases y la sesión de ruta no vive en
`VoiceSession`: encargos, pausas y correcciones están en
`NavigationSessionMemory`, que se persiste con `NavigationSessionStore` y se
restaura en `start()` (ADR-0041).

---

## Invariantes (no modificar sin leer los ADRs)

1. **Un solo pipeline vivo:** `VoicePipelineManager` construye `VoiceSessionPipeline` o inicializa el legacy, nunca ambos.
2. **Un solo dueño de captura:** `CaptureArbiter`. Ni la sesión, ni la UI, ni `MainActivity`, ni `FifoVoiceService` llaman a `PhoneMicRecorder` o al reconocedor nativo.
3. **Un solo dueño de VAD/pre-roll:** `SpeechSegmenter`. El facade no tiene una copia propia.
4. **Una reconfiguración por transición efectiva:** timbrado → activo sigue siendo `PHONE_CALL`; no se vuelve a llamar a `switchToCallMode`.
5. **Nunca `SpeechRecognizer` durante una llamada:** la ruta de llamada es `PHONE_CALL`.
6. **Barge-in por contenido:** una invocación “Fifo …” o una orden de silencio corta TTS; el RMS solo anima boca/OLED.
7. **Eco antes que acción:** el anuncio de timbrado contiene “Fifo contesta”/“Fifo cuelga”; ese eco no puede contestar ni colgar.
8. **Telefonía fuera de la sesión:** `VoiceSession` solo recibe `callState` y entrega `CallAction`; no toca `AudioManager`.
9. **Memoria antes de hablar:** `recordTurn()` ocurre antes de `speakResponse`; la cola pendiente vive en `conversation_journal`, no en RAM.
10. **Un solo dueño de memoria:** `VoiceSessionPipeline` compone el diario y el consolidador; `VoiceSession` solo ve `SessionMemory` y el facade legacy no crea otro coordinador.
11. **Una sola política léxica:** `UtterancePolicy` (ADR-0042) decide invocación, silencio, sueño, eco, pregunta y control de llamada. `TurnOrchestrator` y el facade delegan; no agregues listas o regex en ninguno de los dos.
12. **La acústica propone, el léxico dispone:** `WakeWordDetector` (ADR-0043) solo produce candidatos; `UtterancePolicy.resolveWake()` es la única fusión y la acústica nunca autoriza `CallAction`. El detector no abre micrófono, no tiene VAD y no corre mientras Fifo habla.
13. **El arnés no se envía y su corpus base no se toca:** `WakeWordCorpus` y `SpectralTemplateWakeWordDetector` viven en `test`. El corpus base de ADR-0044 se congela; las condiciones de sala, Lombard y VTLP van al corpus de campo de ADR-0045 y se comparan por separado.
14. **El corpus sintético mide, no entrena:** los clasificadores y las plantillas aumentadas de ADR-0046 viven en `test`, se activan con `FIFO_WAKEWORD_EXPERIMENT=1` y no sustituyen a `NoopWakeWordDetector` hasta superar la barra de ADR-0044 con validación de campo.
15. **La exportación y el entrenador son de desarrollo:** `WakeWordCorpusExportTest` (gated por `FIFO_WAKEWORD_EXPORT_DIR`) es el único contrato WAV/manifiesto; `tools/wakeword/` no entra en el APK y su `.pt` no se envía. El resultado de ADR-0047 (33,3 % de FRR y 13,2 FA/h) no autoriza a cambiar el detector de producción.
16. **La identidad personaliza, no autentica:** `SpeakerIdentity` (ADR-0048) es contexto para el LLM. Nunca autoriza llamadas, pagos ni datos privados; `UtterancePolicy.classifyCallAction()` sigue siendo la única autoridad de telefonía.
17. **Una sola costura de identidad:** `SpeakerIdentityProvider` recibe el WAV de `SpeechSegmenter`, corre en paralelo al STT con timeout de 400 ms y no abre micrófono ni VAD. `VoiceSessionPipeline` elige el proveedor; el facade legacy no construye identidad. `NoopSpeakerIdentityProvider` es el valor por defecto.
18. **Una sola política de identidad:** `SpeakerIdentityPolicy.resolve()` convierte evidencia en `OWNER`/`KNOWN`/`UNKNOWN`/`UNCERTAIN`/`UNAVAILABLE`. Los umbrales de `SpeakerPolicyConfig` son por modelo; no los dupliques en la sesión, el proveedor o el LLM. `SpeakerPromptContext` solo produce texto de prompt.
19. **Biometría local y con consentimiento:** se guardan embeddings, nunca audio; `EncryptedVoiceProfileStore` cifra en reposo con Keystore + AES-256-GCM, escribe atómicamente, tolera corrupción y lleva `modelId`. `ConsentAwareVoiceProfileStore` bloquea lectura/enrolamiento sin consentimiento pero siempre permite borrar. No se sincroniza con el backend.
20. **Calibrar y reportar con voces distintas:** el umbral se fija con los locutores de `calibration` a un FAR objetivo y se reporta con los de `evaluation`. Nunca calibres y evalúes sobre el mismo conjunto. La exportación (`FIFO_SPEAKER_EXPORT_DIR`) y `tools/speaker/` son de desarrollo; el arnés sintético no certifica campo y no autoriza a enviar un detector biométrico.
