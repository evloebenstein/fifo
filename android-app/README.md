# 📱 Aplicación Android FIFO (`android-app/`)

Aplicación nativa de Android para el robot y asistente personal **FIFO**, construida con **Kotlin**, **Jetpack Compose**, **Material 3**, y arquitectura modular reactiva Local-First.

Diseñada con un enfoque de **cero fricción y accesibilidad universal para personas de la tercera edad**, integrando **Google Sign-In Nativo (sin dictar ni escribir correos)**, memoria conversacional durable en SQLite y ejecución en segundo plano 24/7 mediante Bluetooth Low Energy (BLE).

---

## 🔑 Inicio de Sesión con Google Nativo (Zero Fricción)

Uno de los principales desafíos para los adultos mayores es el inicio de sesión tradicional: recordar contraseñas complejas o dictar correos letra por letra (*"l-u-c-i-a punto g-o-n..."*). 

En FIFO se diseñó un flujo de **acceso directo con un solo toque hacia los Servicios de Google**:

1. **Sin dictado ni tipeo:** La persona simplemente presiona el botón con el logotipo oficial de Google (**"Registrarme rápido con Google"** o **"Iniciar sesión con Google"**).
2. **Servicios de Google Play (`play-services-auth`):** La app invoca el flujo oficial de autenticación de Google (`GoogleSignInClient`).
3. **Selector Nativo del Sistema:** Android despliega la ventana emergente oficial del sistema operativo (*"Choose an account to continue to Fifo"*), permitiendo seleccionar la cuenta de Google vinculada al dispositivo con un solo toque.
4. **Fallback Resiliente de Android:** Si las credenciales del cliente web no están registradas en Google Cloud Console o se recibe un error de desarrollador (código 10 / 12500 en entornos de depuración), la app conmuta automáticamente al selector nativo del sistema operativo mediante `AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), null, null, null, null)`, garantizando que el usuario **nunca quede bloqueado**.
5. **Aprovisionamiento Automático:** Una vez seleccionada la cuenta, la app extrae el nombre y correo del usuario y crea/sincroniza el perfil en la base de datos (SQLite local y MySQL remoto) con una biografía y gustos iniciales generados por IA.

### 🛠️ Configuración de Google Cloud Console (OAuth 2.0)

Para vincular la app en Google Cloud Console o Firebase:

- **Nombre de paquete:** `com.fifo.voicepipeline`
- **Huella digital SHA-1 (Debug Keystore):**
  ```
  E9:50:34:D5:01:3D:C8:09:97:9B:72:4C:E6:6F:A4:C7:43:4A:F0:0A
  ```
- **Huella digital SHA-256:**
  ```
  58:0A:FB:28:6D:A1:33:D7:AE:D5:F0:98:1E:82:3D:45:90:7C:07:6F:1C:B9:93:14:19:DD:61:1E:D6:44:FA:AA
  ```

---

## ⚡ Cerebro de IA en Groq LPU & Presupuesto de Tokens

La app utiliza la infraestructura de baja latencia de **Groq LPU** (`qwen/qwen3.8-27b` y `whisper-large-v3-turbo`) bajo el diseño desacoplado `SpeechCognitionSeam` (ADR-0006):

- **Control Estricto de Cuota (<1,800 tokens):** El tier gratuito de Groq tiene un límite estricto de 6,000 Tokens Por Minuto (TPM). Para evitar errores HTTP 429:
  - Las herramientas de Function Calling fueron compactadas y optimizadas a nivel de tokens.
  - El prompt del sistema se optimizó a menos de 180 tokens.
  - El prompt usa un historial de trabajo de 2 turnos; el diario durable restaura hasta 4 turnos (8 mensajes) al arrancar.
- **Latencia de Inferencia en Vivo:** **~618 ms** por turno conversacional completo.
- **Memoria Contextual:** Fifo recuerda el contexto previo sin saturar la ventana de contexto (ejemplo: *"¿hay farmacias cerca?"* seguido de *"llévame allá"* activa la navegación a la farmacia consultada).

---

## 🧠 Arquitectura de Voz y Memoria Durable

La app contiene dos caminos excluyentes, seleccionados por `VoicePipelineModeSelector`:

| Variante | `VOICE_SESSION_ENABLED` | Camino efectivo |
| :--- | :--- | :--- |
| `debug` | `false` | `LEGACY` (camino anterior de `VoicePipelineManager`) |
| `release` | `true` | `SESSION` si hay STT de nube; si no, `LEGACY` |

En `SESSION`, `VoiceSessionPipeline` es la raíz de composición: construye `VoiceSession`,
`CaptureArbiter`, `AndroidTelephonyPort`, `CallerContextCoordinator` y
`MemoryCoordinator` + `SqliteConversationJournal` + `RepositorySessionMemoryConsolidator`.

- **Captura con un solo dueño:** `CaptureArbiter` decide entre micrófono normal, modo llamada, BLE o reconocedor nativo. Timbrado y llamada activa comparten `PHONE_CALL`.
- **VAD y pre-roll:** `SpeechSegmenter` posee el VAD, el pre-roll (~480 ms) y el WAV. El RMS solo anima la boca/OLED.
- **Barge-in por contenido:** una nueva invocación “Fifo …” o una orden de silencio corta TTS. Un ruido fuerte no; el eco se ignora antes de cualquier acción.
- **Llamadas:** anuncio local inmediato, enriquecimiento backend en paralelo (~800 ms) y comandos completos *“Fifo contesta”* / *“Fifo cuelga”*. Un desconocido sigue siendo desconocido.
- **Memoria durable:** cada turno se escribe en `conversation_journal` (SQLite v9) **antes de hablar**. La cola pendiente sobrevive reinicios y se reintenta con backoff de 30 s a 15 min.
- **Reparación:** una corrección explícita usa ±1 turno; se locuta si el turno anterior tiene ~10 s o menos y, si llega tarde, queda en el diario/OLED sin interrumpir.
- **Identidad de locutor (opcional):** `SpeakerIdentityProvider` atribuye el turno a `OWNER`/`KNOWN`/`UNKNOWN`/`UNCERTAIN`/`UNAVAILABLE` en paralelo al STT, con timeout de 400 ms. `Noop` es el valor por defecto; solo se guardan embeddings locales cifrados y **nunca autoriza llamadas ni datos privados** (ADR-0048).

La arquitectura completa está en [`docs/README.md`](../docs/README.md).

---

## 🧩 Funcionalidades Verificadas en Vivo

La aplicación cuenta con 4 pestañas principales y herramientas modales probadas directamente en el emulador y dispositivos físicos:

1. **🏠 Pestaña Inicio (`HomeScreen`):**
   - Mascota animada de Fifo (`FifoFace.kt`) con expresiones de ánimo y estados de escucha.
   - Acceso rápido a herramientas de asistencia: **"Baja el ritmo"** y **"¿Dónde está mi Fifo?"**.
   - Tarjetas de recordatorios del día y llamadas prioritarias a contactos frecuentes.
2. **🫁 Modal "Baja el ritmo" (`BreathingExerciseDialog.kt`):**
   - Ejercicio guiado de respiración consciente (Inhala 4s - Sostén 4s - Exhala 4s) con animación suave para calmar momentos de angustia o desorientación.
3. **📍 Modal "¿Dónde está mi Fifo?" (`DeviceFinderDialog.kt`):**
   - Radar de localización del robot extraviado en el hogar.
   - Indicador de última habitación detectada por Bluetooth LE y botón de apertura en Google Maps con coordenadas GPS.
4. **📅 Pestaña Actividades (`ActivitiesScreen`):**
   - Cartelera de actividades y talleres para adultos mayores (Club de lectura, Caminatas recreativas, Conciertos de música clásica).
5. **👥 Pestaña Fifo Amigos (`SocialScreen`):**
   - Red comunitaria basada en afinidades e intereses.
   - Publicaciones, anécdotas e historias compartidas por la comunidad.
6. **👤 Pestaña Perfil (`ProfileScreen`):**
   - Biografía sintetizada dinámicamente por IA basada en las conversaciones cotidianas.
   - Etiquetas de gustos e intereses recordados en la memoria viva (`interest_tags`).
   - Selector rápido para alternar entre perfiles y conmutar API Keys.

---

## 🗺️ Mapa de Archivos del Directorio

| Archivo / Carpeta | Tipo | Rol Principal |
| :--- | :--- | :--- |
| [`app/`](app/README.md) | Subdirectorio | Módulo principal: código Kotlin, Jetpack Compose, servicios en segundo plano y assets. |
| [`build.gradle.kts`](build.gradle.kts) | Gradle raíz | Plugins globales (Android App, Kotlin Android, Compose Compiler). |
| [`settings.gradle.kts`](settings.gradle.kts) | Configuración | Define el proyecto e incluye `:app`. |
| [`gradle.properties`](gradle.properties) | Propiedades JVM | Memoria JVM, AndroidX y paralelismo de compilación. |
| [`local.properties.example`](local.properties.example) | Plantilla | Muestra cómo configurar `sdk.dir` y `GROQ_API_KEY`. |
| [`generate_fifo_icon.py`](generate_fifo_icon.py) | Script de diseño | Genera los iconos adaptativos de Fifo en todas las densidades de pantalla. |
| [`gradlew`](gradlew) / [`gradlew.bat`](gradlew.bat) | Wrapper Gradle | Binarios para compilar en Linux/macOS y Windows. |

---

## 🛠️ Requisitos de Compilación

- **JDK:** Java 17 o superior.
- **Android SDK:** Compile SDK 34, Min SDK 26, Target SDK 34.
- **Dispositivo / Emulador:** Android 8.0 (API 26) o superior con Google Play Services y Bluetooth LE.

---

## 🚀 Comandos Rápidos de Terminal

```powershell
# 1. Compilar APK Debug
powershell -Command "Remove-Item Env:\_JAVA_OPTIONS -ErrorAction SilentlyContinue; .\gradlew.bat assembleDebug"

# 2. Instalar en el dispositivo o emulador vía ADB
adb install -r -d app\build\outputs\apk\debug\app-debug.apk

# 3. Lanzar la aplicación
adb shell am start -n com.fifo.voicepipeline/.MainActivity

# 4. Habilitar comunicación con el backend local (puerto 8090)
adb reverse tcp:8090 tcp:8090

# 5. Ver logs del asistente de voz y Groq en vivo
adb logcat -s FifoVoiceService:V CloudApiClient:V FifoSkills:V GoogleSignInDialog:V
```

### 🧪 Pruebas

```powershell
# Suite JVM de ambos build types
.\gradlew.bat testDebugUnitTest
.\gradlew.bat testReleaseUnitTest

# Compilación del camino de release (SESSION)
.\gradlew.bat compileReleaseKotlin
```

La suite actual cubre 390 pruebas por variante, incluyendo captura (`CaptureArbiterTest`),
telefonía (`TelephonyPortTest`), contexto de llamante, memoria durable, reparación,
barge-in por transcripción e identidad de locutor (`SpeakerIdentityPolicyTest`,
`SpeakerIdentityProviderTest`, `SpeakerIdentityIntegrationTest`).
