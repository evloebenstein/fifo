# 🌐 Remote Data Client (`com.fifo.voicepipeline.data.remote`)

Cliente de red HTTP para sincronización de datos con el servidor backend FastAPI.

---

## 🗺️ Mapa de Archivos del Directorio

| Archivo | Rol y Responsabilidad |
| :--- | :--- |
| [`FifoApiClient.kt`](FifoApiClient.kt) | **Cliente REST heredado para Datos**: Sincroniza perfiles, recuerdos, recordatorios y amigos usando `OkHttpClient`. Conserva rutas `/api/...` del contrato antiguo; para features nuevas usa `FifoBackendClient` en `network/`. |

---

## 🧭 Reglas de Programación en `remote/`
1. **Preferir `FifoBackendClient`:** este paquete se conserva por compatibilidad. No agregues endpoints nuevos a `FifoApiClient`.
2. **Manejo de Errores Silencioso / No Bloqueante:** Si el servidor backend está fuera de línea o el usuario no tiene Wi-Fi, `FifoApiClient` debe fallar silenciosamente o reintentar sin interrumpir la experiencia de usuario (Local-First).
3. **Timeouts:** Configurar timeouts cortos (5 a 10 segundos) para no retener recursos si el host de la API no responde.
