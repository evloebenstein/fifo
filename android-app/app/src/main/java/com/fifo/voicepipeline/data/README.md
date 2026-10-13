# 📦 Data Layer Architecture (`com.fifo.voicepipeline.data`)

Capa central de almacenamiento, persistencia y estado reactivo para la aplicación Fifo. Implementa un patrón **Local-First con Sincronización Nube**: la aplicación funciona 100% desconectada mediante SQLite y se sincroniza automáticamente con el backend MySQL cuando hay conexión.

---

## 🗺️ Mapa de Archivos del Directorio

| Archivo / Carpeta | Tipo | Rol Principal |
| :--- | :--- | :--- |
| [`FifoDataRepository.kt`](FifoDataRepository.kt) | **Repositorio Central (Single Source of Truth)**: Gestiona todos los `StateFlow` reactivos consumidos por la UI de Jetpack Compose: usuario activo (`userProfile`), lista de perfiles, recordatorios (`reminders`), recuerdos vivos (`memories`), historias de gustos (`tasteStories`), contactos telefónicos (`contacts`), amigos compatibles (`friends`), y actividades del día (`activities`). Coordina la persistencia en `local/FifoDatabaseHelper.kt` y la sincronización con `remote/FifoApiClient.kt`. |
| [`local/`](local/README.md) | Subpaquete | Base de datos relacional local en SQLite (`FifoDatabaseHelper.kt`). |
| [`remote/`](remote/README.md) | Subpaquete | Cliente HTTP REST (`FifoApiClient.kt`) para sincronización con la nube. |

---

## 🔄 Flujo de Datos Doble Capa

```
                 [ UI en Jetpack Compose ]
                             ▲
                             │ Escucha StateFlows
                             ▼
                  [ FifoDataRepository ]
                   /                  \
   (Instantáneo)  /                    \ (Asíncrono en segundo plano)
                 ▼                      ▼
      [ local/FifoDatabaseHelper ]   [ remote/FifoApiClient ]
          (SQLite en Teléfono)          (FastAPI / MySQL 8.4)
```

---

## 🧭 Reglas de Programación en `data/`
1. **Actualizaciones Atómicas:** Toda mutación de estado (ej: añadir un recuerdo o recordatorio) debe insertarse primero en SQLite y luego actualizar el `MutableStateFlow` con `.value = ...` para que la UI se refresque instantáneamente sin lag de red.
2. **Cambio de Perfil Limpio:** Al cambiar de perfil de usuario (`switchProfile`), deben reinicializarse todos los `StateFlow` con los datos del nuevo usuario para evitar filtraciones de datos entre perfiles.
