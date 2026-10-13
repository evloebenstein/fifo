# 🎙️ Audio Processing Subsystem (`com.fifo.voicepipeline.audio`)

Subpaquete responsable de la captura de audio por micrófono, detección de actividad de voz (VAD), síntesis por voz (TTS), validación fonética en español y reproducción en streaming.

---

## 🗺️ Mapa de Archivos del Directorio

| Archivo | Rol y Responsabilidad |
| :--- | :--- |
| [`AndroidTtsSpeaker.kt`](AndroidTtsSpeaker.kt) | **Sintetizador de Voz Nativo de Android**: Envuelve `android.speech.tts.TextToSpeech`. Gratuito, sin conexión a internet y sin claves de API. Soporta eventos `onStart` y `onDone`, con modulación de velocidad y tono amigable para adultos mayores. |
| [`AudioConfig.kt`](AudioConfig.kt) | **Constantes Globales de Audio**: Frecuencia de muestreo (16,000 Hz), codificación (PCM 16-bit mono), tamaño de tramas para VAD (512 muestras = 32ms) y umbrales de energía RMS. |
| [`AudioPlayer.kt`](AudioPlayer.kt) | **Reproductor Streaming PCM**: Utiliza `AudioTrack` en modo stream para reproducir audio crudo recibido de servicios de TTS externos (OpenAI o ElevenLabs) o efectos de sonido locales sin latencia. |
| [`NativeSpeechRecognizer.kt`](NativeSpeechRecognizer.kt) | **Reconocedor Nativo de Google**: Envuelve `android.speech.SpeechRecognizer` para transcripción continua en tiempo real sin gastar tokens de Whisper. Soporta reinicio automático ante pausas y emisión de resultados parciales. |
| [`PcmBuffer.kt`](PcmBuffer.kt) | **Buffer Circular de Audio**: Acumula muestras PCM en memoria y las convierte en un archivo `.wav` estándar de 16 kHz mono para enviar a la nube cuando se usa Whisper. |
| [`PhoneMicRecorder.kt`](PhoneMicRecorder.kt) | **Grabador de Micrófono del Teléfono**: Lee directamente de `AudioRecord` en un hilo de alta prioridad cuando el robot físico BLE no está conectado o el usuario prefiere el micrófono del celular. |
| [`SpanishCoherenceValidator.kt`](SpanishCoherenceValidator.kt) | **Filtro Anti-Alucinaciones en Español**: Analiza la transcripción de voz para descartar sonidos sin sentido, alucinaciones de Whisper ("Subtítulos por...", "Suscríbete") o ruido ambiente que no forma oraciones lógicas. |
| [`TextSanitizer.kt`](TextSanitizer.kt) | **Sanitizador de Texto para TTS**: Limpia acotaciones escénicas (`*sonríe*`, `[pausa]`), asteriscos de markdown, emojis y prefijos de rol (`Fifo:`) antes de enviarlos al motor de voz para que no pronuncie palabras residuales. |
| [`VadProcessor.kt`](VadProcessor.kt) | **Detector de Actividad de Voz (VAD)**: Algoritmo de energía acústica y zero-crossing rate que detecta cuándo una persona empieza y termina de hablar para recortar el audio de forma precisa. |

---

## 🧭 Reglas de Programación en `audio/`
1. **Hilo de Grabación:** Nunca ejecutar lectura de `AudioRecord` en el hilo principal (`Dispatchers.Main`); siempre usar hilos de I/O dedicados o corrutinas con `Dispatchers.Default`.
2. **Sanitización Obligatoria:** Todo texto que deba ser hablado por parlante DEBE pasar antes por `TextSanitizer.cleanForSpeech()`.
3. **Cancelación de Eco:** El microfono debe pausar o ignorar tramas emitidas mientras `AndroidTtsSpeaker.isSpeaking` está activo, salvo comandos expresos de silencio ("Fifo silencio").
