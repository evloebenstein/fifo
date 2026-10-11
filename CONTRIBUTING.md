# Guía de Contribución a Fifo 

¡Gracias por tu interés en colaborar con **Fifo**! Este proyecto tiene como misión brindar compañía empática, autonomía y asistencia integral a personas mayores mediante interacción por voz fluida, inteligencia artificial y hardware accesible.

Toda contribución —ya sea en código, documentación, corrección de bugs o ideas de diseño accesible— es muy bienvenida.

---

## 1. Principios Éticos y de Producto (Lectura Obligatoria)

Antes de realizar cambios, por favor revisa [PRODUCT.md](PRODUCT.md) y [FIFO_DATABASE_MAP.md](FIFO_DATABASE_MAP.md). Cualquier contribución debe alinearse con estos principios:

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
  - `pipeline/`: Canalización de voz en tiempo real (`VoicePipelineManager.kt`), VAD, Whisper STT, TTS y control de estados.
  - `audio/`: Grabadores de micrófono (`PhoneMicRecorder.kt`), TTS nativo (`AndroidTtsSpeaker.kt`), cancelador de eco y validación de coherencia.
  - `skills/`: Registro de herramientas ejecutables por voz (`FifoSkillRegistry.kt`) como mapas, llamadas, recordatorios, linterna, etc.
  - `telephony/`: Gestor de telefonía inteligente (`FifoCallManager.kt`) con anuncios por voz y captura concurrente.
  - `data/`: Modelos de datos y repositorio reactivo (`FifoDataRepository.kt`).
- `esp32-firmware/`: Código C++ para microcontroladores ESP32 con audio I2S (micrófono INMP441, amplificador MAX98357A), pantalla OLED SSD1306 y enlace BLE.
- `database/`: Definiciones de esquemas SQLite, mapeos relacionales y memoria a largo plazo.
- `backend/`: Servicios auxiliares en la nube (si aplica).

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
Fifo utiliza servicios de IA (Groq LPU para inferencia ultra-rápida, Anthropic Claude para razonamiento y OpenAI Whisper para STT).

>  **IMPORTANTE: NUNCA subas claves de API al repositorio.**
> Las claves se configuran localmente en la app a través del menú de Ajustes o en archivos locales no rastreados por git.

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

Si realizas cambios en `VoicePipelineManager.kt` o en la captura de audio, verifica siempre:
1. **Wake-Word estricto**: Fifo no debe activarse ni responder a personas hablando en segundo plano a menos que digan *"Fifo"*, se presione el botón manual, esté en modo continuo o responda a una pregunta directa que Fifo formuló.
2. **Barge-In inteligente**: Mientras Fifo habla, ruidos fuertes o murmullos no deben cortar su voz. Solo debe callarse si el usuario dice *"Fifo silencio"*, *"cállate"*, *"para"*, o inicia una nueva consulta con *"Fifo ..."*.
3. **Filtro Anti-Eco**: La propia voz de Fifo saliendo del altavoz no debe activar el reconocedor de voz.
4. **Llamadas telefónicas**: La captura de voz debe seguir operativa durante llamadas sin interferir con la conversación del usuario.

---

## 6. Flujo de Trabajo para Enviar un Pull Request (PR)

1. **Crea un Fork** del repositorio en GitHub.
2. **Crea una rama descriptiva** para tus cambios:
   ```bash
   git checkout -b feature/nueva-habilidad-recordatorio
   # o
   git checkout -b fix/filtro-ruido-esp32
   ```
3. **Realiza tus cambios y verifica la compilación**:
   ```bash
   cd android-app
   ./gradlew compileDebugKotlin
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
- Abre un **Issue** en GitHub detallando los pasos para reproducir el comportamiento, modelo del dispositivo Android y logs relevantes (`adb logcat -s FifoVoicePipeline`).
- Sé empático y constructivo en la discusión.

¡Gracias por ayudarnos a construir una tecnología más humana y accesible para nuestros mayores! 👴👵💙
