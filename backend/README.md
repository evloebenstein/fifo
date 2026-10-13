# 🐳 Backend Architecture & Directory Map (`backend/`)

Infraestructura de backend contenerizada para el asistente robótico **FIFO**, con persistencia relacional en **MySQL 8.4 LTS**, panel visual **Adminer** y microservicio REST en **FastAPI**.

---

## 🗺️ Mapa de Archivos del Directorio

| Archivo / Carpeta | Tipo | Rol Principal |
| :--- | :--- | :--- |
| [`docker-compose.yml`](docker-compose.yml) | Docker Compose | Orquestación multicontenedor: `fifo-mysql` (3306), `fifo-adminer` (8080) y `fifo-api` (8090). Red aislada `fifo-net` y volumen `fifo_mysql_data`. |
| [`.env`](.env) | Configuración | Variables de entorno activas para credenciales MySQL, claves Groq/Gemini y puertos. *(No subir a repositorios públicos)*. |
| [`.env.example`](.env.example) | Plantilla | Ejemplo de configuración con variables requeridas para desplegar en local o en VPS/Cloud. |
| [`api/`](api/README.md) | Subdirectorio | Código fuente del microservicio Python FastAPI (`main.py`), dependencias y Dockerfile. |
| [`init/`](init/README.md) | Subdirectorio | Scripts SQL ejecutados automáticamente al arrancar el contenedor MySQL: DDL del esquema relacional y datos de prueba. |

---

## 🏗️ Servicios en Ejecución

```
+-------------------------------------------------------------+
|                      Host (Máquina / VPS)                   |
|                                                             |
|   Puerto 8090                Puerto 8080       Puerto 3306  |
|        │                          │                 │       |
|        ▼                          ▼                 ▼       |
|  +-------------+            +-----------+     +-----------+ |
|  |  fifo-api   |            |fifo-adminer|     | fifo-mysql| |
|  |  (FastAPI)  |◄──────────►| (Adminer) |◄───►| (MySQL 8) | |
|  +-------------+            +-----------+     +-----------+ |
|        ▲                                            ▲       |
|        │                   Red Docker: fifo-net     │       |
|        └────────────────────────────────────────────┘       |
|                                                             |
|   Volumen persistente: fifo_mysql_data                      |
+-------------------------------------------------------------+
```

---

## 🚀 Guía de Comandos Rápidos

```bash
# 1. Levantar backend completo en segundo plano (reconstruyendo imagen API)
docker compose up -d --build

# 2. Ver logs en tiempo real de la API
docker compose logs -f fifo-api

# 3. Ver logs de MySQL
docker compose logs -f fifo-mysql

# 4. Detener contenedores manteniendo datos intactos
docker compose down

# 5. Reinicio total borrando datos (¡CUIDADO: elimina base de datos!)
docker compose down -v && docker compose up -d --build
```

---

## 🧭 Reglas de Programación en `backend/`
1. **Idempotencia en migraciones:** La API en `api/main.py` ejecuta `ensure_schema_migrations()` al arrancar para añadir columnas faltantes sin alterar datos existentes.
2. **Charset y Collation:** Siempre usar `utf8mb4` y `utf8mb4_es_0900_ai_ci` para tildes en español y emojis.
3. **Persistencia de sesión:** Las llamadas desde la app Android se sincronizan bidireccionalmente hacia la tabla `users`, `user_memories`, `taste_stories`, `user_reminders` y `user_contacts_relational`.
