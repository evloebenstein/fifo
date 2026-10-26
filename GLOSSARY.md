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

**Cascada de invocación**:
Las dos etapas que despiertan a Fifo: un detector acústico propone un candidato y `UtterancePolicy.resolveWake()` lo confirma o lo descarta contra la transcripción. Ninguna etapa decide sola (ADR-0043).
_Avoid_: Doble wake word, Hotword + STT

**Candidato acústico**:
Evidencia de que en una ventana de audio hubo algo parecido a "Fifo": un score y un instante, sin decisión. Caduca en segundos y nunca autoriza una acción de llamada.
_Avoid_: Detección, Trigger, Match

**WakeWordDetector**:
Costura que genera candidatos acústicos a partir de las tramas que ya captura `CaptureArbiter`. No abre micrófono, no tiene VAD, no habla y no conoce el estado conversacional.
_Avoid_: HotwordEngine, KeywordSpotter, KWSManager

**Política de locución**:
Única autoridad léxica de Fifo (`UtterancePolicy`, ADR-0042). Clasifica invocación, consulta, saludo, silencio, sueño, eco, pregunta y control de llamada con las mismas reglas en el camino `SESSION` y en el legacy. Es pura y no redacta respuestas; solo decide qué hizo o pidió el usuario.
_Avoid_: Diccionario de comandos, Reglas de voz, IntentParser

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

**ConversationJournal**:
Diario durable de turnos completos (usuario + Fifo). Se escribe antes de la locución y sobrevive al cierre de la app. En producción es SQLite; en pruebas, memoria.
_Avoid_: ChatLog, HistoryBuffer, SessionCache

**JournalEntry**:
Un turno persistido en el diario con estado `PENDING` o `CONSOLIDATED`, intentos de consolidación, próximo intento y datos de reparación (`needsRepair`, `repairText`).
_Avoid_: ChatMessage, TurnRecord

**MemoryCoordinator**:
Orquestador de la memoria durable. Escribe turnos, mantiene la cola persistente, reintenta consolidaciones con backoff exponencial y restaura los turnos recientes en `CloudApiClient`. VoiceSession solo ve la costura `SessionMemory`.
_Avoid_: MemoryManager, PersistenceService

**PendingConsolidationQueue**:
Cola de turnos `PENDING` cuyo backoff ya venció. Vive en `conversation_journal`, no en RAM; un reinicio de la app la recupera.
_Avoid_: RetryCache, ConsolidationList

**ConversationRepair**:
Corrección de un turno anterior usando la ventana de ±1 turno. Si el turno original tiene ~10 s o menos, la corrección se locuta; si llega tarde, se guarda en diario y OLED sin interrumpir.
_Avoid_: Retraction, UndoMessage, CorrectionCommand

**Fast-path**:
Camino local de `VoiceIntentRouter` que ejecuta en microsegundos solo acciones mecánicas y sin ambigüedad: contestar/colgar una llamada, linterna, volumen, batería, hora y cancelar una ruta. No redacta respuestas.
_Avoid_: Atajo local, Comando directo, Router de voz

**Consulta informativa**:
Pregunta cuya respuesta depende de contexto (ubicación, orientación, memoria episódica, agenda, recomendaciones). Nunca la responde el fast-path: retorna `null` y la redacta el LLM con el contexto inyectado.
_Avoid_: Pregunta simple, Consulta trivial

**Contexto espacial**:
Bloque que `FifoNavigationManager.buildSystemPromptContext()` inyecta al LLM con calle, comuna, orientación de la brújula, destino activo, distancia restante, cuadras, indicación relativa, desvío y destino pendiente. Es la única fuente de verdad para responder “¿dónde estoy?” u “¿cuánto falta?”.
_Avoid_: Descripción espacial, Frase de ubicación

**Aviso de navegación**:
Hecho estructurado (`NavigationNotice`) que `FifoNavigationManager` detecta por sí mismo -llegada, desvío, quietud, 100 m, 50 m- y entrega al pipeline. El motor no redacta: el LLM compone la locución. `fallbackSpeech()` es el único texto estático y solo se usa si el modelo no responde. Los avisos coalescen al más reciente y esperan sin plazo al turno en curso; solo se descartan si el destino activo cambió (ADR-0041).
_Avoid_: Alerta de Waze, Frase de guía, Mensaje autónomo

**Sesión de navegación**:
Estado conversacional de una ruta activa (`NavigationSessionMemory`): encargos o paradas anotadas en el camino, pausa pactada por el usuario y última corrección de ruta. Se reinicia al iniciar o terminar la ruta y el LLM la gestiona con la herramienta `manage_navigation_session`. Su foto (`PersistedNavigationSession`) se guarda con `NavigationSessionStore` y se restaura al arrancar con caducidad de 6 h (ADR-0041).
_Avoid_: Memoria de ruta, Contexto de viaje

**Foto de ruta**:
`PersistedNavigationSession`: destino, encargos, pausa y corrección serializados en `SharedPreferences` (`fifo_navigation`). No es la memoria durable de conversación (ADR-0011): es el estado de la ruta activa que sobrevive al cierre de la app.
_Avoid_: Snapshot de memoria, Journal de ruta

### Comunidad y Memoria

**Estado del dispositivo Fifo**:
Última conexión, ubicación, habitación, RSSI y beeper del robot (`FifoDeviceLocation`). `DeviceLocationPolicy` decide la transición; el repositorio conserva el reloj, SQLite y la sincronización.
_Avoid_: Estado del robot, Telemetría, DeviceStatus

**Contexto episódico local**:
Resultado del fallback offline de `recall_past_context`: busca contactos, rutinas, recuerdos, gustos, recordatorios y fragmentos con stop words y puntuación local. Vive en `EpisodicContextSearch`; el backend sigue teniendo prioridad cuando responde.
_Avoid_: Búsqueda profunda, Recall, RAG local

**ActividadComunitaria**:
Encuentro o evento presencial propuesto por un usuario o patrocinado por Fifo, con afinidades temáticas calculadas para personas mayores.
_Avoid_: Evento, Reunión, Match

**Borrador de actividad**:
Propuesta local que Fifo arma a partir de una idea dictada y del perfil: elige categoría, título, lugar, gustos y descripción sin llamar al backend. Vive en `ActivitySuggestionPolicy` y no está publicada hasta que el usuario la crea.
_Avoid_: Actividad sugerida por IA, Plantilla, Draft

**FifoAmigo**:
Contacto de confianza en la red social de personas mayores que utilizan Fifo, con soporte de afinidades de pasatiempos y recuerdos compartidos.
_Avoid_: Contacto, Buddy, UserProfile

**Grafo relacional**:
Conjunto local de personas que Fifo conoce (`RelationalContact`), fusionado desde la libreta del teléfono, los perfiles de FifoAmigos y las menciones en conversaciones. No inventa parentesco y preserva la memoria aprendida.
_Avoid_: Agenda, Lista de contactos, KnowledgeGraph

**Aparición de contacto**:
Registro append-only (`ContactAppearance`) de dónde apareció una persona: libreta (`PHONE_CONTACT`), FifoAmigos (`FIFO_FRIEND`) o conversación (`CONVERSATION`). Vive solo en `contact_appearances` y no se envía al backend.
_Avoid_: Evento, Log, Historial

**Resolución de contacto**:
Decisión de qué persona del grafo corresponde a un nombre, rol, apodo o fragmento dicho por el usuario. Vive en `ContactMatcher`: ignora acentos y mayúsculas, prioriza coincidencia exacta y luego cercanía, y nunca resuelve una consulta vacía.
_Avoid_: Búsqueda de contacto, Matching, Lookup

**Persona cercana**:
Rol por defecto de una persona mencionada en conversación cuando Fifo no puede inferir un parentesco. Es una descripción neutral, no una afirmación de familiaridad.
_Avoid_: Familiar, Amigo, Conocido

**Indicio telefónico**:
Número que aparece explícito en el contexto de una persona mencionada y que `PhoneHintExtractor` asocia solo si el contacto no tiene teléfono. Vive en `contact_phone_hints` como `PENDING`; no reemplaza la libreta, no se usa para llamar y no se sincroniza hasta que el usuario lo confirma.
_Avoid_: Teléfono confirmado, Contacto verificado

**Teléfono confirmado**:
Teléfono que el usuario aceptó a partir de un indicio mencionado. Recién entonces se copia a `RelationalContact.phoneNumber`, se persiste y se sincroniza; deja de estar pendiente en `contact_phone_hints`.
_Avoid_: Teléfono mencionado, Indicio
