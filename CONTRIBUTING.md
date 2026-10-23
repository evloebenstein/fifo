# Guía de Contribución a Fifo 

¡Gracias por tu interés en colaborar con **Fifo**! Este proyecto tiene como misión brindar compañía empática, autonomía y asistencia integral a personas mayores mediante interacción por voz fluida, inteligencia artificial y hardware accesible.

Toda contribución —ya sea en código, documentación, corrección de bugs o ideas de diseño accesible— es muy bienvenida.

---

## 1. Principios Éticos y de Producto (Lectura Obligatoria)

Antes de realizar cambios, por favor revisa [PRODUCT.md](PRODUCT.md), [FIFO_DATABASE_MAP.md](FIFO_DATABASE_MAP.md), [AGENTS.md](AGENTS.md) y [GLOSSARY.md](GLOSSARY.md). Los ADR de [docs/adr/](docs/adr/) son la fuente de verdad de la arquitectura. Cualquier contribución debe alinearse con estos principios:

1. **Dignidad y Paciencia ante todo**: Fifo interactúa con personas mayores. Jamás se deben apresurar sus pausas al hablar, ni cortar sus respuestas bruscamente, ni generar respuestas frías o despersonalizadas.
2. **Accesibilidad sin Concesiones**:
   - Áreas táctiles mínimas de 48×48 dp (56 dp para botones de voz).
   - Tipografía escalable en `sp` con alto contraste (WCAG AAA para textos clave).
   - Soporte total para lectores de pantalla (TalkBack con `contentDescription` descriptivo en español).
3. **Privacidad y Registro Positivo**:
   - Por defecto, Fifo solo almacena recuerdos, intereses y anécdotas positivas.
   - Datos médicos o diagnósticos delicados nunca se publican ni se guardan sin confirmación verbal clara del usuario.
   - Tolerancia cero a contenido inapropiado o difusión no autorizada de datos de salud.
4. **Cero Sobre-carga Cognitiva**: La interfaz debe ser simple, tranquila y comprensible sin manuales de usuario.

---

## 2. Estructura del Repositorio

El proyecto está organizado en módulos desacoplados:

- `android-app/`: Aplicación nativa en Kotlin con Jetpack Compose.
  - `pipeline/`: Núcleo de voz. `VoicePipelineManager` es la fachada; `VoicePipelineModeSelector` elige `LEGACY` o `SESSION`; `VoiceSessionPipeline` compone la sesión, la captura, la telefonía y la memoria durable.
  - `pipeline/session/`: `VoiceSession`, `SpeechSegmenter` (VAD/pre-roll/WAV), `CaptureArbiter` (dueño de la captura), `MemoryCoordinator` y `ConversationRepairPolicy`.
  - `audio/`: Primitivas de audio (`PhoneMicRecorder`, VAD, buffer PCM, TTS nativo y sanitización). No llamar a `PhoneMicRecorder` fuera de `CaptureArbiter`.
  - `telephony/`: `TelephonyPort`, `AndroidTelephonyPort`, `FifoCallManager` y contexto de llamante en dos fases.
  - `data/`: Repositorio reactivo, módulos de dominio, diario durable (`data/memory`) y SQLite v7 (`data/local`).
  - `skills/`: Registro de herramientas ejecutables por voz (`FifoSkillRegistry`).
  - `network/`: BLE, Groq STT/LLM, backend y extracción de memoria.
- `esp32-firmware/`: Código C++ para ESP32 con audio I2S, pantalla OLED SSD1306 y enlace BLE.
- `database/`: Definiciones de esquemas SQLite, mapeos relacionales y memoria a largo plazo.
- `backend/`: API FastAPI y esquema MySQL para consolidación y sincronización.
- `docs/adr/`: Decisiones de arquitectura (ADR-0001 a ADR-0011).

---

## 3. Configuración del Entorno de Desarrollo

### Requisitos Previos
- **Java Development Kit**: JDK 17 o superior.
- **Android Studio**: Versión Iguana, Koala o más reciente.
- **Android SDK**: API nivel 34 (Android 14) con compatibilidad mínima en API 26 (Android 8.0).
- **Git** instalado y configurado.

### Clonar y Compilar la App Android

```bash
# Clonar el repositorio
git clone https://github.com/evloebenstein/fifo.git
cd fifo

# Entrar a la carpeta de la app Android
cd android-app

# Compilar el código Kotlin
./gradlew compileDebugKotlin    # En Linux/macOS
gradlew.bat compileDebugKotlin  # En Windows

# Generar el instalador APK de desarrollo
./gradlew assembleDebug         # En Linux/macOS
gradlew.bat assembleDebug       # En Windows
```

El APK resultante se genera en `android-app/app/build/outputs/apk/debug/app-debug.apk`.

### Claves de API y Variables de Entorno
Fifo usa **Groq LPU** para STT (`whisper-large-v3-turbo`) y LLM (`qwen/qwen3.8-27b`). Opcionalmente, `OPENAI_API_KEY` habilita Whisper de OpenAI como alternativa de STT. No se requiere clave para el TTS nativo de Android.

>  **IMPORTANTE: NUNCA subas claves de API al repositorio.**
> Las claves se configuran localmente en `android-app/local.properties` o en los Ajustes de la app. `local.properties` está excluido por `.gitignore`.

---

## 4. Convenciones de Código y Commits

### Estilo de Código
- Código escrito en **Kotlin idiomático** siguiendo la [Guía de Estilo Oficial de Kotlin](https://developer.android.com/kotlin/style-guide).
- Comentarios y logs técnicos documentados en **español** para mantener coherencia en todo el proyecto.
- Manejo proactivo de excepciones y corrutinas estructuradas con `SupervisorJob()`.

### Convención de Commits (Conventional Commits)
Utilizamos el estándar de Conventional Commits para mantener un historial limpio:

- `feat(modulo): descripción` — Nuevas funcionalidades (ej. `feat(voice): agregar soporte para temporizadores verbales`).
- `fix(modulo): descripción` — Corrección de errores (ej. `fix(barge-in): evitar cortes en locución por ruido ambiente`).
- `docs(modulo): descripción` — Mejoras en documentación (ej. `docs: actualizar PRODUCT.md con políticas de voz`).
- `refactor(modulo): descripción` — Cambios que no alteran la funcionalidad externa.
- `test(modulo): descripción` — Adición o actualización de pruebas.

---

## 5. Pruebas de la Canalización de Voz (Voice Pipeline)

Antes de abrir un PR, ejecuta las pruebas JVM de ambos build types:

```powershell
cd android-app
.\gradlew.bat testDebugUnitTest
.\gradlew.bat testReleaseUnitTest
.\gradlew.bat compileReleaseKotlin
```

Si tocas `pipeline/`, `audio/`, `telephony/` o `data/memory/`, verifica siempre:

1. **Un solo pipeline:** `VoicePipelineManager` construye `VoiceSessionPipeline` o inicializa el legacy, nunca ambos. Debug usa `LEGACY`; release usa `SESSION` si hay STT de nube.
2. **Un solo dueño de captura:** `CaptureArbiter` decide entre micrófono, BLE, llamada o reconocedor nativo. No llames a `PhoneMicRecorder` desde la sesión, UI o servicio.
3. **Un solo dueño de VAD/pre-roll:** `SpeechSegmenter`. No agregues un segundo VAD en el facade.
4. **Wake-word estricto:** Fifo no responde a conversaciones ajenas salvo wake word, botón manual, modo continuo o follow-up de una pregunta directa.
5. **Barge-in por contenido:** ruidos fuertes o RMS no cortan la locución. Solo una nueva invocación “Fifo …” o una orden de silencio.
6. **Eco antes de acción:** el eco del anuncio de timbrado no puede contestar ni colgar. Los comandos exigen *“Fifo contesta”* / *“Fifo cuelga”*.
7. **Llamadas:** la ruta es `PHONE_CALL`; nunca `SpeechRecognizer` durante una llamada. Timbrado y activo no reconfiguran el micrófono.
8. **Memoria:** cada turno se journaliza antes de `speakResponse`; la cola pendiente vive en `conversation_journal`; la consolidación usa backoff y `repairText` cuando corresponde.
9. **Reparación:** ±1 turno, ventana de ~10 s; un “No” aislado solo cuenta tras `needsRepair`.

Las pruebas de verdad incluyen `CaptureArbiterTest`, `SpeechSegmenterTest`, `VoiceSessionTest`,
`TelephonyPortTest`, `CallerContextProviderTest`, `CallerContextCoordinatorTest`,
`MemoryCoordinatorTest`, `ConversationRepairPolicyTest` y `VoiceSessionMemoryTest`.

---

## 6. Flujo de Trabajo para Enviar un Pull Request (PR)

1. **Crea un Fork** del repositorio en GitHub.
2. **Crea una rama descriptiva** para tus cambios:
   ```bash
   git checkout -b feature/nueva-habilidad-recordatorio
   # o
   git checkout -b fix/filtro-ruido-esp32
   ```
3. **Realiza tus cambios y verifica la compilación y las pruebas**:
   ```bash
   cd android-app
   ./gradlew compileDebugKotlin testDebugUnitTest
   ./gradlew compileReleaseKotlin testReleaseUnitTest
   ```
4. **Haz commit de tus cambios** respetando las convenciones:
   ```bash
   git commit -m "feat(skills): agregar recordatorio proactivo de hidratacion"
   ```
5. **Sube la rama a tu fork**:
   ```bash
   git push origin feature/nueva-habilidad-recordatorio
   ```
6. **Abre un Pull Request** hacia la rama `main` del repositorio oficial describiendo con claridad:
   - ¿Qué problema resuelve el PR?
   - ¿Qué cambios se hicieron?
   - ¿Cómo se probó en un dispositivo real o emulador?

---

## 7. Reporte de Errores y Sugerencias

Si encuentras un error o tienes una idea para mejorar la experiencia de los adultos mayores:
- Abre un **Issue** en GitHub detallando los pasos para reproducir el comportamiento, modelo del dispositivo Android y logs relevantes (`adb logcat -s FifoVoiceService:V VoicePipelineManager:V CloudApiClient:V`).
- Sé empático y constructivo en la discusión.

¡Gracias por ayudarnos a construir una tecnología más humana y accesible para nuestros mayores! 👴👵💙
