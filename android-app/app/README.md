# 📱 Android App Module (`android-app/app/`)

Módulo principal de la aplicación Android de Fifo.

---

## 🗺️ Mapa de Archivos del Directorio

| Archivo / Carpeta | Tipo | Rol Principal |
| :--- | :--- | :--- |
| [`build.gradle.kts`](build.gradle.kts) | Configuración de módulo Gradle | Define dependencias: Jetpack Compose (BOM 2024), Material 3, Navigation Compose, Coroutines, OkHttp 4, Ktor / WebSockets, Credential Manager (Google Sign-In), Google Play Services (Auth / Location), y CameraX. |
| [`src/main/`](src/main/AndroidManifest.xml) | Código fuente y recursos | Contiene el manifiesto oficial de permisos y servicios (`AndroidManifest.xml`), el paquete de código fuente en Kotlin (`java/`) y recursos XML (`res/`). |

---

## 🔐 Permisos Declarados en `AndroidManifest.xml`

1. **Audio y Voz:** `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`.
2. **Bluetooth Low Energy:** `BLUETOOTH`, `BLUETOOTH_ADMIN`, `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`.
3. **Telefonía y Contactos:** `READ_PHONE_STATE`, `READ_CALL_LOG`, `ANSWER_PHONE_CALLS`, `READ_CONTACTS`, `CALL_PHONE`.
4. **Ubicación y Navegación:** `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`.
5. **Segundo Plano:** `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`.
6. **Accesibilidad:** `FifoAccessibilityService` para control manos libres de llamadas telefónicas.
