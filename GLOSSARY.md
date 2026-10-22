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
Interrupción natural de la locución de Fifo provocada cuando el usuario habla mientras Fifo emite audio, pausando inmediatamente la reproducción y evaluando con el LLM si se trata de una aclaración, una nueva pregunta o una orden de silencio.
_Avoid_: AudioCut, HardStop, MuteInterrupt

**WakeWordTrigger**:
La detección de la palabra de activación ("Fifo"), tanto de forma aislada como incrustada de forma natural dentro de una frase continua hablada por el usuario.
_Avoid_: Hotword, KeywordDetection

**AudioSeam**:
La costura de interfaz que desacopla la captura y emisión física de tramas PCM (micrófono del teléfono o BLE ESP32) de la máquina de estados conversacional.
_Avoid_: AudioBridge, HardwareBoundary

**PreRollBuffer**:
Búfer circular de audio en memoria (~480 ms) que retiene los milisegundos previos a la confirmación de la palabra clave para evitar la amputación del fonema inicial "Fi-".
_Avoid_: AudioCache, FrameQueue

**CaptureArbiter**:
El único dueño de la captura de audio. Decide entre micrófono del celular en modo normal, micrófono en modo llamada, BLE del ESP32, reconocedor nativo o captura detenida. Solo reconfigura hardware cuando cambia la ruta efectiva y descarta tramas tardías.
_Avoid_: AudioRouter, MicManager, SourceSwitcher

**CaptureRoute**:
La fuente efectiva de audio elegida por el CaptureArbiter para el turno actual: `STOPPED`, `PHONE_NORMAL`, `PHONE_CALL`, `BLE_MIC` o `NATIVE_RECOGNIZER`.
_Avoid_: AudioSource, InputMode

**TelephonyPort**:
La costura que mantiene la telefonía fuera de VoiceSession. Expone el estado de llamada y las acciones contestar/colgar sin que la sesión toque AudioManager ni FifoCallManager.
_Avoid_: CallManager, PhoneBridge

**CallSnapshot**:
Fotografía inmutable del estado telefónico: fase, nombre del llamante y número. Distingue llamante conocido, desconocido y llamada sin identificar; no inventa nombres.
_Avoid_: CallInfo, PhoneState

**CallPhase**:
Fase de una llamada en el dominio de Fifo: `NONE`, `RINGING` o `ACTIVE`. Timbrado y activo comparten la ruta de captura `PHONE_CALL`.
_Avoid_: CallState (ambiguo con el estado nativo de Android)

### Comunidad y Memoria

**ActividadComunitaria**:
Encuentro o evento presencial propuesto por un usuario o patrocinado por Fifo, con afinidades temáticas calculadas para personas mayores.
_Avoid_: Evento, Reunión, Match

**FifoAmigo**:
Contacto de confianza en la red social de personas mayores que utilizan Fifo, con soporte de afinidades de pasatiempos y recuerdos compartidos.
_Avoid_: Contacto, Buddy, UserProfile
