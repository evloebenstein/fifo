# 🧩 UI Components & Dialogs (`com.fifo.voicepipeline.ui.components`)

Componentes reutilizables, modales interactivos y elementos visuales de la interfaz de usuario.

---

## 🗺️ Mapa de Archivos del Directorio

| Archivo | Rol y Responsabilidad |
| :--- | :--- |
| [`FifoFace.kt`](FifoFace.kt) | **Carita Animada del Robot**: Dibuja los ojos y boca expresiva de Fifo mediante Canvas de Compose. Reacciona a los estados del pipeline: parpadeo aleatorio en reposo, pupilas dilatadas al escuchar, mirada pensativa al procesar y boca modulada por el volumen del altavoz. |
| [`VoiceInteractionSheet.kt`](VoiceInteractionSheet.kt) | **Hoja Inferior de Voz**: Panel deslizable que muestra en tiempo real la transcripción del usuario, la respuesta de la IA, barras de onda de sonido, botón de silencio (*Mute*) y botón táctil para hablar (*Push-to-Talk*). |
| [`GoogleSignInDialog.kt`](GoogleSignInDialog.kt) | **Diálogo de Inicio de Sesión y Perfiles**: Permite iniciar sesión con Google mediante Credential Manager, crear nuevos perfiles en la base de datos o alternar entre perfiles existentes. |
| [`BleProvisioningDialog.kt`](BleProvisioningDialog.kt) | **Asistente de Emparejamiento BLE**: Escanea dispositivos Bluetooth cercanos, permite seleccionar al robot Fifo y le transfiere la red Wi-Fi de la casa. |
| [`FifoDeviceFinderDialog.kt`](FifoDeviceFinderDialog.kt) | **Radar de Búsqueda del Robot**: Medidor de intensidad de señal RSSI con botón para activar alarma sonora y destellos en el ESP32 para encontrar el robot en el hogar. |
| [`BreathingExerciseDialog.kt`](BreathingExerciseDialog.kt) | **Ejercicio de Respiración Guiada**: Animación relajante con guía auditiva para calmar la ansiedad en adultos mayores (inhalar, sostener, exhalar). |
| [`CallAssistantGuideDialog.kt`](CallAssistantGuideDialog.kt) | **Guía del Asistente Telefónico**: Explica de forma sencilla cómo Fifo avisa las llamadas y cómo contestar o colgar diciendo *"Fifo contesta"*. |
| [`BackgroundMicGuideDialog.kt`](BackgroundMicGuideDialog.kt) | **Guía de Optimización de Batería**: Instrucciones paso a paso para permitir que Fifo escuche con la pantalla apagada sin que Android detenga la app. |
| [`FifoBottomNav.kt`](FifoBottomNav.kt) | **Barra de Navegación Accesible**: 4 pestañas con iconos grandes y etiquetas claras: Inicio, Perfil, Amigos y Actividades. |

---

## 🧭 Reglas de Programación en `ui/components/`
1. **Estado Hoisting:** Los diálogos deben recibir lambdas `onDismissRequest` y emitir eventos hacia arriba sin mutar estados globales directamente dentro del componente.
2. **Animaciones Suaves:** Usar `animateFloatAsState` y `infiniteRepeatable` para transiciones de ojos y ondas de audio sin sobrecargar el hilo de renderizado de la GPU.
