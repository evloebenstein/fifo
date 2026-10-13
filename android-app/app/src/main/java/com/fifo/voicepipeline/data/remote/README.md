# 🌐 Remote Data Client (`com.fifo.voicepipeline.data.remote`)

Cliente de red HTTP para sincronización de datos con el servidor backend FastAPI.

---

## 🗺️ Mapa de Archivos del Directorio

| Archivo | Rol y Responsabilidad |
| :--- | :--- |
| [`FifoApiClient.kt`](FifoApiClient.kt) | **Cliente REST para Datos**: Realiza peticiones HTTP (GET, POST, PUT) usando `OkHttpClient` hacia los endpoints `/api/v1/` del backend. Sincroniza perfiles, recuerdos, recordatorios y amigos en segundo plano. |

---

## 🧭 Reglas de Programación en `remote/`
1. **Manejo de Errores Silencioso / No Bloqueante:** Si el servidor backend está fuera de línea o el usuario no tiene Wi-Fi, `FifoApiClient` debe fallar silenciosamente o reintentar sin interrumpir la experiencia de usuario (Local-First).
2. **Timeouts:** Configurar timeouts cortos (5 a 10 segundos) para no retener recursos si el host de la API no responde.
