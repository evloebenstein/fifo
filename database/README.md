# 📦 Database Directory (`database/`)

Este directorio contiene una versión desacoplada de la API REST de persistencia de datos.

---

## 🗺️ Mapa de Archivos del Directorio

| Archivo / Carpeta | Tipo | Rol Principal |
| :--- | :--- | :--- |
| [`api/`](api/README.md) | Subdirectorio | Contiene la implementación ligera de FastAPI para entornos de pruebas independientes de base de datos sin levantar el orquestador principal de `backend/`. |

---

## ⚠️ Nota Arquitectónica
- Para la infraestructura en producción y desarrollo activo estándar con Docker Compose, MySQL 8.4 y Adminer, consulta y utiliza el directorio principal [`backend/`](../backend/README.md).
- Este directorio `database/` se conserva como entorno aislado de testing o despliegues serverless independientes.
