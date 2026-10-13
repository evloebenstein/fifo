# 🗄️ Database Standalone API (`database/api/`)

Servicio REST independiente para entornos de pruebas desacoplados del Docker Compose central.

---

## 🗺️ Mapa de Archivos

| Archivo | Rol y Responsabilidad |
| :--- | :--- |
| [`main.py`](main.py) | Endpoints FastAPI independientes para operaciones CRUD sobre tablas de usuario, recuerdos y gustos. |
| [`Dockerfile`](Dockerfile) | Configuración para empaquetar el microservicio independiente en un contenedor Docker. |
| [`requirements.txt`](requirements.txt) | Especificación de librerías (`fastapi`, `uvicorn`, `mysql-connector-python`). |

Para la versión principal completa con soporte multi-usuario dinámico y Google Sign-In, refiérase a [`backend/api/`](../../backend/api/README.md).
