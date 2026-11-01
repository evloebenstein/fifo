# 🎨 User Interface Architecture (`com.fifo.voicepipeline.ui`)

Capa de interfaz visual de usuario, construida íntegramente con **Jetpack Compose** y **Material 3**, con tipografías grandes, alto contraste y animaciones cálidas adaptadas para personas mayores.

---

## 🗺️ Mapa de Archivos y Subpaquetes

| Archivo / Subpaquete | Tipo | Rol Principal |
| :--- | :--- | :--- |
| [`MainScreen.kt`](MainScreen.kt) | Composable Raíz | Orquesta la navegación principal de la app mediante una barra inferior accesible (`FifoBottomNav`), controla las transiciones entre pantallas principales (`Home`, `Profile`, `Friends`, `Activities`), y muestra la hoja interactiva de conversación por voz (`VoiceInteractionSheet`). |
| [`state/`](state/FifoStateHolders.kt) | Subpaquete | **State holders por pantalla (ADR-0050):** proyecciones de solo lectura de los módulos de `AppGraph` y comandos que delegan al dueño. `fifoStateHolder { graph -> ... }` los recuerda por composición; no poseen estado. |
| [`components/`](components/README.md) | Subpaquete | Diálogos modales y componentes reutilizables: carita animada de Fifo, selector de perfiles con Google, buscador del robot, etc. |
| [`model/`](model/README.md) | Subpaquete | Modelos de datos para la interfaz (`FifoModels.kt`). |
| [`screens/`](screens/README.md) | Subpaquete | Vistas completas de la aplicación: Inicio, Perfil, Red de Amigos, Actividades, Bienvenida y Onboarding. |
| [`theme/`](theme/README.md) | Subpaquete | Paleta de colores cálidos, tokens de elevación y tipografías Material 3. |

---

## 🧭 Reglas de Estado en `ui/`
1. **La pantalla proyecta, no posee:** lee el `StateFlow` del holder y llama a sus comandos. No crea `MutableStateFlow` de dominio ni copia datos del módulo (ADR-0050).
2. **Nada de `FifoDataRepository`:** la UI no usa el accessor deprecado; el holder recibe solo los módulos que necesita.
3. **La publicación no es de la pantalla:** guardar y publicar ocurre en el módulo por su `*RemotePort` (ADR-0050).
4. **La escucha continua se pide, no se decreta:** el comando llega a `graph.listening.request()`; el dueño real es `TurnOrchestrator`.

---

## 🧭 Reglas de Accesibilidad en `ui/`
1. **Tamaño de Toque:** Todo botón o elemento cliqueable debe tener un área táctil mínima de 48dp x 48dp (idealmente 56dp o más).
2. **Contraste:** Los textos deben cumplir con el estándar WCAG AAA para garantizar legibilidad óptima para personas con disminución visual o cataratas.
