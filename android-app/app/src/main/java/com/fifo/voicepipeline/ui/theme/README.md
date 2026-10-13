# 🎨 Theme & Design Tokens (`com.fifo.voicepipeline.ui.theme`)

Sistema de diseño y tokens de color adaptados para alta legibilidad y calidez afectuosa en adultos mayores.

---

## 🗺️ Mapa de Archivos del Directorio

| Archivo | Rol y Responsabilidad |
| :--- | :--- |
| [`Colors.kt`](Colors.kt) | **Paleta de Colores y Tokens**: Define colores cálidos no agresivos: Terracota suave (`FifoWarmOrange`), Verde salvia (`FifoMintGreen`), Azul sereno (`FifoSkyBlue`), fondos crema de alto contraste (`FifoBackgroundWarm`), y colores semánticos para estados del robot (verde para escuchando, ámbar para pensando, azul para hablando). |

---

## 🧭 Reglas de Programación en `ui/theme/`
1. **Evitar Blanco Puro (#FFFFFF) sobre Negro Puro (#000000):** Se prefieren contrastes cálidos y amables para reducir la fatiga visual en usuarios de la tercera edad.
2. **Consistencia:** Utilizar los nombres de tokens semánticos en lugar de valores hexadecimales hardcodeados en los componentes Compose.
