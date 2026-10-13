# 📋 UI Data Models (`com.fifo.voicepipeline.ui.model`)

Clases de datos (*Data Classes*) que representan las entidades del dominio consumidas por los componentes visuales de Jetpack Compose.

---

## 🗺️ Mapa de Archivos del Directorio

| Archivo | Rol y Responsabilidad |
| :--- | :--- |
| [`FifoModels.kt`](FifoModels.kt) | **Modelos del Dominio**: Define las clases inmutables: `UserProfile` (datos del usuario activo, biografía, tags de intereses), `MemoryItem` (recuerdos vivos categorizados), `TasteStoryItem` (historias profundas de gustos), `ReminderItem` (recordatorios y alarmas), `ContactItem` (contactos familiares de apoyo), `FriendItem` (perfiles de amigos de la red Fifo), `ActivityItem` (rutinas y estimulación diaria) y `IncomingCallInfo` (estado de llamada telefónica activa). |

---

## 🧭 Reglas de Programación en `ui/model/`
1. **Inmutabilidad:** Todas las propiedades de las data classes deben declararse con `val`. Para modificaciones, utilizar el método `.copy()`.
2. **Serialización:** Proporcionar valores por defecto para permitir deserialización segura desde JSON o cursores SQLite sin `NullPointerException`.
