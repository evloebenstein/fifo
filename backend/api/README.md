# 🚀 REST API Service (`backend/api/`)

Microservicio REST escrito en **Python 3.11** con **FastAPI** y `mysql-connector-python`. Actúa como puente entre la aplicación Android de Fifo, los modelos de lenguaje (Claude / Gemini / Groq) y la base de datos relacional MySQL 8.4 LTS.

---

## 🗺️ Mapa de Archivos del Directorio

| Archivo | Rol y Responsabilidad |
| :--- | :--- |
| [`main.py`](main.py) | **Archivo principal del microservicio** (2,300+ líneas). Define la aplicación FastAPI, pool de conexiones a MySQL, endpoints de perfiles de usuario, autenticación Google, recuerdos vivos (`user_memories`), historias de gustos (`taste_stories`), recordatorios, contactos telefónicos enriquecidos, matching de red social Fifo Amigos, y consolidación automática de sesiones conversacionales. |
| [`Dockerfile`](Dockerfile) | Imagen Docker ligera basada en `python:3.11-slim`. Instala `requirements.txt` y expone el puerto `8090` corriendo con `uvicorn main:app --host 0.0.0.0 --port 8090`. |
| [`requirements.txt`](requirements.txt) | Dependencias de Python mínimas y rápidas: `fastapi`, `uvicorn[standard]`, `mysql-connector-python`, `pydantic`. |

---

## 📡 Endpoints Clave en `main.py`

### 1. Perfiles y Autenticación
- `GET /api/v1/profiles`: Lista todos los perfiles disponibles para cambio dinámico.
- `POST /api/v1/profiles`: Crea un nuevo usuario en la base de datos MySQL con su biografía y gustos iniciales.
- `GET /api/v1/profile/{user_id}`: Obtiene el perfil activo, tags de interés y datos de emergencia.
- `PUT /api/v1/profile/{user_id}`: Actualiza datos demográficos, gustos y contacto de emergencia.
- `POST /api/v1/auth/google`: Registro / inicio de sesión federado con Google (OpenID / OAuth2 token).

### 2. Memoria Relacional y Recuerdos Vivos
- `GET /api/v1/memories/{user_id}`: Lista los recuerdos persistidos del usuario.
- `POST /api/v1/memories`: Inserta un nuevo recuerdo categorizado (`familiar`, `gusto`, `salud`, `rutina`).
- `GET /api/v1/memories/search`: Búsqueda profunda por texto o categoría.
- `POST /api/v1/memories/recall`: Procedimiento para enriquecer el prompt del LLM con recuerdos relevantes.

### 3. Gustos e Historias Detalladas
- `GET /api/v1/tastes/stories/{user_id}`: Recupera las historias narrativas de cada gusto (ej: *"Prefiere boleros de Lucho Gatica porque su madre los ponía"*).
- `POST /api/v1/tastes/stories`: Registra una nueva historia enriquecida por la IA.

### 4. Red Social "Fifo Amigos"
- `GET /api/v1/friends/{user_id}`: Amigos conectados en la red.
- `GET /api/v1/friends/compatible/{user_id}`: Algoritmo de afinidad por coincidencia de `interest_tags`, edad y ciudad.

### 5. Consolidación de Sesión Conversacional
- `POST /api/v1/session/consolidate`: Recibe los turnos de diálogo emitidos en la app móvil y extrae automáticamente recuerdos, historias de gustos, nuevos contactos y recordatorios en un solo paso atómico.

---

## 🧭 Reglas de Programación en `backend/api/`
1. **Conexiones:** Usar siempre la función `get_db_connection()` con `autocommit=True` y cerrarla en bloques `try...finally`.
2. **Sanitización de Respuestas:** Todos los textos generados por IA o guardados en la BD deben retornar con UTF-8 limpio para que el TTS del móvil no pronuncie caracteres extraños.
3. **CORS:** El middleware CORS debe permitir orígenes `*` para compatibilidad con emulador Android (`10.0.2.2`), IP local LAN (`192.168.x.x`) y ADB Reverse (`127.0.0.1`).
