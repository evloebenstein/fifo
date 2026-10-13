# 📱 Android Application Root (`android-app/`)

Aplicación nativa de Android para el robot y asistente personal **FIFO**, construida con **Kotlin**, **Jetpack Compose**, **Material 3**, y arquitectura modular reactiva.

---

## 🗺️ Mapa de Archivos del Directorio

| Archivo / Carpeta | Tipo | Rol Principal |
| :--- | :--- | :--- |
| [`app/`](app/README.md) | Subdirectorio | Módulo de aplicación principal: código fuente Kotlin, manifiesto, layouts y recursos gráficos. |
| [`build.gradle.kts`](build.gradle.kts) | Configuración Gradle raíz | Define plugins del proyecto (Android Application, Kotlin Android, Compose Compiler) y repositorios Maven. |
| [`settings.gradle.kts`](settings.gradle.kts) | Configuración de Proyecto | Define el nombre del proyecto (`fifo-voice-pipeline`) e incluye el módulo `:app`. |
| [`gradle.properties`](gradle.properties) | Propiedades de compilación | JVM args, soporte para AndroidX y habilitación de Kotlin incremental. |
| [`local.properties.example`](local.properties.example) | Plantilla | Muestra cómo configurar `sdk.dir` apuntando al SDK de Android local. |
| [`generate_fifo_icon.py`](generate_fifo_icon.py) | Script de diseño | Script en Python (usando Pillow) que genera los iconos de la app en todas las resoluciones (`mipmap-mdpi`, `hdpi`, `xhdpi`, `xxhdpi`, `xxxhdpi`). |
| [`gradlew`](gradlew) / [`gradlew.bat`](gradlew.bat) | Wrapper de Gradle | Ejecutables para compilar el proyecto en Linux/macOS y Windows. |

---

## 🛠️ Requisitos de Compilación

- **JDK:** Java 17 o superior.
- **Android SDK:** Compile SDK 34, Min SDK 26, Target SDK 34.
- **Dispositivo recomendado:** Android 8.0 (API 26) en adelante, con Bluetooth LE y permisos de micrófono.

---

## 🚀 Comandos Rápidos de Terminal

```powershell
# 1. Compilar APK Debug
powershell -Command "Remove-Item Env:\_JAVA_OPTIONS -ErrorAction SilentlyContinue; .\gradlew.bat assembleDebug"

# 2. Instalar en el teléfono conectado (vía ADB)
adb install -r -d app\build\outputs\apk\debug\app-debug.apk

# 3. Lanzar la aplicación
adb shell am start -n com.fifo.voicepipeline/.MainActivity

# 4. Habilitar comunicación con backend local (puerto 8090)
adb reverse tcp:8090 tcp:8090
```
