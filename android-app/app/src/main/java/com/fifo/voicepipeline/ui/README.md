# 🎨 User Interface Architecture (`com.fifo.voicepipeline.ui`)

Capa de interfaz visual de usuario, construida íntegramente con **Jetpack Compose** y **Material 3**, con tipografías grandes, alto contraste y animaciones cálidas adaptadas para personas mayores.

---

## 🗺️ Mapa de Archivos y Subpaquetes

| Archivo / Subpaquete | Tipo | Rol Principal |
| :--- | :--- | :--- |
| [`MainScreen.kt`](MainScreen.kt) | Composable Raíz | Orquesta la navegación principal de la app mediante una barra inferior accesible (`FifoBottomNav`), controla las transiciones entre pantallas principales (`Home`, `Profile`, `Friends`, `Activities`), y muestra la hoja interactiva de conversación por voz (`VoiceInteractionSheet`). |
| [`components/`](components/README.md) | Subpaquete | Diálogos modales y componentes reutilizables: carita animada de Fifo, selector de perfiles con Google, buscador del robot, etc. |
| [`model/`](model/README.md) | Subpaquete | Modelos de datos para la interfaz (`FifoModels.kt`). |
| [`screens/`](screens/README.md) | Subpaquete | Vistas completas de la aplicación: Inicio, Perfil, Red de Amigos, Actividades, Bienvenida y Onboarding. |
| [`theme/`](theme/README.md) | Subpaquete | Paleta de colores cálidos, tokens de elevación y tipografías Material 3. |

---

## 🧭 Reglas de Accesibilidad en `ui/`
1. **Tamaño de Toque:** Todo botón o elemento cliqueable debe tener un área táctil mínima de 48dp x 48dp (idealmente 56dp o más).
2. **Contraste:** Los textos deben cumplir con el estándar WCAG AAA para garantizar legibilidad óptima para personas con disminución visual o cataratas.
