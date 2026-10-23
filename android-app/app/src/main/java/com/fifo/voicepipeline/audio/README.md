# 🎙️ Audio Processing Subsystem (`com.fifo.voicepipeline.audio`)

Primitivas de audio de bajo nivel: captura PCM, VAD, buffers, reconocimiento nativo,
TTS y sanitización. La **propiedad** de la captura y del VAD en el camino nuevo no vive
aquí: pertenece a `pipeline/session/CaptureArbiter` y `SpeechSegmenter`.

---

## 🗺️ Mapa de Archivos

| Archivo | Rol y Responsabilidad |
| :--- | :--- |
| [`AndroidTtsSpeaker.kt`](AndroidTtsSpeaker.kt) | Envuelve `android.speech.tts.TextToSpeech`. Es usado por el adaptador de salida del camino nuevo y por el camino legacy. Gratuito y sin clave de API. |
| [`AudioConfig.kt`](AudioConfig.kt) | Constantes globales: 16,000 Hz, PCM 16-bit mono, tamaño de trama del VAD (512 muestras = 32 ms) y umbrales RMS. |
| [`AudioPlayer.kt`](AudioPlayer.kt) | Reproductor streaming PCM con `AudioTrack` para audio crudo de TTS externo o efectos locales. |
| [`NativeSpeechRecognizer.kt`](NativeSpeechRecognizer.kt) | Envuelve `SpeechRecognizer` de Android. Solo se usa en rutas sin STT de nube; `CaptureArbiter` lo detiene antes de una llamada. |
| [`PcmBuffer.kt`](PcmBuffer.kt) | Buffer circular PCM y construcción de WAV 16 kHz mono para STT. |
| [`PhoneMicRecorder.kt`](PhoneMicRecorder.kt) | Grabadora de `AudioRecord`. Es `internal` y **solo** puede ser usada por `AndroidCaptureHardware`/`CaptureArbiter`; nunca directamente por la sesión, UI o servicio. |
| [`SpanishCoherenceValidator.kt`](SpanishCoherenceValidator.kt) | Filtro de alucinaciones de Whisper y ruido sin estructura oracional. |
| [`TextSanitizer.kt`](TextSanitizer.kt) | Limpia acotaciones, Markdown, emojis y prefijos antes de enviar texto al TTS. |
| [`VadProcessor.kt`](VadProcessor.kt) | Detector de actividad de voz por energía y zero-crossing rate. Es una primitiva reutilizada por `SpeechSegmenter`. |

El **VAD + pre-roll + WAV** del camino nuevo viven en
[`../pipeline/session/SpeechSegmenter.kt`](../pipeline/session/SpeechSegmenter.kt).
La **decisión de fuente** vive en
[`../pipeline/session/CaptureArbiter.kt`](../pipeline/session/CaptureArbiter.kt).

---

## 🧭 Reglas de Programación

1. **Hilo de grabación:** nunca leer `AudioRecord` en `Dispatchers.Main`; usar un hilo de I/O dedicado o `Dispatchers.Default`.
2. **Sanitización obligatoria:** todo texto hablado debe pasar por `TextSanitizer.cleanForSpeech()`.
3. **Un solo dueño de la captura:** `PhoneMicRecorder` y `NativeSpeechRecognizer` no se llaman desde `VoiceSession`, `MainActivity`, la UI ni `FifoVoiceService`. Solo desde `CaptureArbiter`/`AndroidCaptureHardware`.
4. **No cortar por RMS:** el nivel RMS solo mueve la boca/OLED y la UI. Un ruido fuerte no detiene la locución; el barge-in se decide después de transcribir.
5. **El eco se ignora antes de cualquier acción:** la propia voz de Fifo no debe activar una respuesta ni una acción de llamada.
6. **Salida separada de entrada:** `AndroidAudioHardwareAdapter` es solo TTS. No agregarle captura ni `AudioManager`.
7. **Una reconfiguración por transición efectiva:** el árbitro no reinicia el micrófono si la ruta calculada no cambió y descarta tramas tardías.
8. **Nunca `SpeechRecognizer` durante una llamada:** Android bloquea el reconocedor con `ERROR_AUDIO`; la ruta debe ser `PHONE_CALL`.
