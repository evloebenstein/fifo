# 📋 UI Data Models (`com.fifo.voicepipeline.ui.model`)

Capa de **presentación** de los modelos de Fifo. Los datos de dominio viven en
`data/model/FifoModels.kt` (puros, sin Android ni Compose); aquí solo se conserva
la compatibilidad de los imports históricos y se añade lo que sí es de UI
(iconos). Ver **ADR-0049** (`docs/adr/0049-single-owner-user-state.md`).

---

## 🗺️ Mapa de Archivos del Directorio

| Archivo | Rol y Responsabilidad |
| :--- | :--- |
| [`FifoModels.kt`](FifoModels.kt) | **Typealiases + extensiones de icono.** Reexporta `UserProfileData`, `FifoMemoryItem`, `FifoTasteStory`, `FifoReminderItem`, `RelationalContact`, `CommunityActivityItem`, etc. desde `data.model`, y añade `PastConversationItem.icon` y `CommunityActivityItem.icon`. |

---

## 🧭 Reglas de Programación en `ui/model/`

1. **No declares `data class` aquí.** Un modelo nuevo de dominio nace en
   `data/model`; si necesita un `ImageVector` o cualquier tipo de Compose, añade
   una extensión en este paquete.
2. **Los imports históricos siguen funcionando.**
   `com.fifo.voicepipeline.ui.model.X` es un typealias de
   `com.fifo.voicepipeline.data.model.X`; no dupliques la definición.
3. **Inmutabilidad y valores por defecto.** Las propiedades de dominio son `val`
   y traen defaults para deserializar JSON o cursores SQLite sin `null`.
4. **La UI no depende de la red ni de SQLite.** Observa `StateFlow` de los
   módulos del `AppGraph`; no construye almacenamiento.
