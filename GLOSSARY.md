# Fifo Domain Context

Fifo es un robot compañero físico y aplicación móvil de bienestar y envejecimiento activo que interactúa mediante voz empática en español neutro con adultos mayores.

## Language

### Voz e Interacción

**VoiceSession**:
La sesión conversacional activa que coordina el flujo continuo de interacción hablada entre el usuario y Fifo.
_Avoid_: AudioLoop, CallSession, VoiceThread

**ConversationalTurn**:
Un ciclo indivisible de diálogo compuesto por la intervención hablada del usuario y la respuesta generada por Fifo.
_Avoid_: ChatMessage, InteractionStep

**BargeIn**:
Interrupción de la locución de Fifo confirmada por el contenido de la transcripción: una nueva invocación "Fifo …" o una orden de silencio. La energía RMS no corta la locución; solo mueve la boca/OLED. Un eco de la propia voz se ignora.
_Avoid_: AudioCut, HardStop, MuteInterrupt, RmsCut

**WakeWordTrigger**:
La detección de la palabra de activación ("Fifo"), tanto de forma aislada como incrustada de forma natural dentro de una frase continua hablada por el usuario.
_Avoid_: Hotword, KeywordDetection

**AudioSeam**:
La costura de interfaz que desacopla la captura y emisión física de tramas PCM (micrófono del teléfono o BLE ESP32) de la máquina de estados conversacional.
_Avoid_: AudioBridge, HardwareBoundary

**SpeechSegmenter**:
Dueño único del VAD, el pre-roll y la construcción del WAV en el camino nuevo. Entrega segmentos de habla completos a VoiceSession; no conoce Android, TTS ni telefonía.
_Avoid_: VadFilter, AudioInboundFilter, SpeechBuffer

**PreRollBuffer**:
Búfer circular de audio en memoria (~480 ms) que retiene los milisegundos previos a la confirmación de la palabra clave para evitar la amputación del fonema inicial "Fi-". En el camino nuevo vive dentro de `SpeechSegmenter`.
_Avoid_: AudioCache, FrameQueue

**VOICE_SESSION_ENABLED**:
Flag de una release que selecciona el camino nuevo (`SESSION`) o el legacy (`LEGACY`). Está apagado en debug y encendido en release; sin STT de nube cae a legacy. Se borra junto con el camino legacy en la release siguiente.
_Avoid_: FeatureToggle, UseNewPipeline

**VoicePipelineMode**:
Resultado puro de `VoicePipelineModeSelector`: `LEGACY` o `SESSION`. Garantiza que solo se construya un pipeline.
_Avoid_: PipelineType, RuntimeMode

**VoiceSessionPipeline**:
Raíz de composición del camino nuevo. Construye `VoiceSession`, el adaptador de captura, `AndroidTelephonyPort` y `CallerContextCoordinator`; conecta el estado de llamada con la captura y entrega las acciones de llamada al exterior.
_Avoid_: NewPipelineManager, VoiceFacade

**CaptureArbiter**:
El único dueño de la captura de audio. Decide entre micrófono del celular en modo normal, micrófono en modo llamada, BLE del ESP32, reconocedor nativo o captura detenida. Solo reconfigura hardware cuando cambia la ruta efectiva y descarta tramas tardías.
_Avoid_: AudioRouter, MicManager, SourceSwitcher

**CaptureRoute**:
La fuente efectiva de audio elegida por el CaptureArbiter para el turno actual: `STOPPED`, `PHONE_NORMAL`, `PHONE_CALL`, `BLE_MIC` o `NATIVE_RECOGNIZER`.
_Avoid_: AudioSource, InputMode

**TelephonyPort**:
La costura que mantiene la telefonía fuera de VoiceSession. Expone el estado de llamada y las acciones contestar/colgar sin que la sesión toque AudioManager ni FifoCallManager. VoiceSession solo recibe `callState` y entrega la acción por `onCallAction`.
_Avoid_: CallManager, PhoneBridge

**CallSnapshot**:
Fotografía inmutable del estado telefónico: fase, nombre del llamante y número. Distingue llamante conocido, desconocido y llamada sin identificar; no inventa nombres.
_Avoid_: CallInfo, PhoneState

**CallPhase**:
Fase de una llamada en el dominio de Fifo: `NONE`, `RINGING` o `ACTIVE`. Timbrado y activo comparten la ruta de captura `PHONE_CALL`.
_Avoid_: CallState (ambiguo con el estado nativo de Android)

**CallerContext**:
Contexto unificado de un llamante: nombre visible, número, parentesco, si es conocido o desconocido, notas y temas recientes. El anuncio de timbrado solo usa nombre/parentesco; las notas y temas quedan para cuando el usuario pregunte.
_Avoid_: CallerInfo, ContactCard

**CallerContextSource**:
Origen de la identidad del llamante: `UNKNOWN`, `LOCAL_CONTACT`, `BACKEND_KNOWN` o `BACKEND_NEW`. Un caller ID con nombre no convierte por sí solo a un desconocido en conocido.
_Avoid_: IdentityType, ContactSource

**CallerContextProvider**:
Proveedor en dos fases: `local()` resuelve de inmediato el nombre del teléfono o del grafo relacional; `enrich()` consulta el backend con timeout corto (~800 ms). Si la red falla, conserva el contexto local.
_Avoid_: CallerResolver, ContactLookup

**CallerContextCoordinator**:
Observador externo de `TelephonyPort` que emite el contexto local al instante, lanza el enriquecimiento en paralelo y descarta resultados de llamadas que ya terminaron. No inicia ni detiene la telefonía.
_Avoid_: CallOrchestrator, PhoneSession

### Comunidad y Memoria

**ActividadComunitaria**:
Encuentro o evento presencial propuesto por un usuario o patrocinado por Fifo, con afinidades temáticas calculadas para personas mayores.
_Avoid_: Evento, Reunión, Match

**FifoAmigo**:
Contacto de confianza en la red social de personas mayores que utilizan Fifo, con soporte de afinidades de pasatiempos y recuerdos compartidos.
_Avoid_: Contacto, Buddy, UserProfile
