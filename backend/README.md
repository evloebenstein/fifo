# 🐳 Backend Architecture & Directory Map (`backend/`)

Infraestructura de backend contenerizada para el asistente robótico **FIFO**, con persistencia relacional en **MySQL 8.4 LTS**, panel visual **Adminer** y microservicio REST en **FastAPI**.

Provee sincronización de perfiles en tiempo real, autenticación y aprovisionamiento con Google, red comunitaria **Fifo Amigos**, grafos relacionales de contactos y síntesis profunda de biografías por IA.

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

## 🔑 Aprovisionamiento y Autenticación con Google

La API expone endpoints para la sincronización y aprovisionamiento directo de usuarios autenticados mediante Google Sign-In desde la aplicación móvil:

- `POST /auth/google` (o `POST /users`): Recibe las credenciales o datos verificados de la cuenta de Google (`email`, `full_name`, `avatar_url`).
- **Aprovisionamiento Automático por IA:** Si el usuario no existe previamente en la base de datos, el backend:
  1. Crea el registro en la tabla `users` con rol por defecto y estado activo.
  2. Genera una biografía inicial empática y personalizada mediante IA.
  3. Aprovisiona etiquetas de gustos sugeridas en `taste_stories` y `user_memories`.
  4. Conecta al usuario con la red social comunitaria **Fifo Amigos**.
- `GET /users`: Lista de usuarios disponibles para permitir la conmutación rápida de perfiles en la app.
- `GET /users/{id}/profile`: Retorna el perfil completo, biografía sintetizada y recuerdos consolidados.
- `GET /users/{id}/fifo-friends/recommendations`: Recomienda amistades afines basándose en la intersección de etiquetas de intereses compartidos.

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

## 🧭 Reglas de Arquitectura en `backend/`

1. **Idempotencia en migraciones:** La API en `api/main.py` ejecuta `ensure_schema_migrations()` al arrancar para añadir columnas faltantes sin alterar datos existentes.
2. **Charset y Collation:** Siempre usar `utf8mb4` y `utf8mb4_es_0900_ai_ci` para soporte completo de tildes en español y emojis.
3. **Persistencia y Sincronización:** Las mutaciones ejecutadas por voz desde el teléfono (recordatorios, gustos, memorias) se reflejan de forma atómica en las tablas relacionales (`users`, `user_memories`, `taste_stories`, `user_reminders` y `user_contacts_relational`).
