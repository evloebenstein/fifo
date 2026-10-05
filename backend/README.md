# 🐳 FIFO Backend Database & REST API (Docker + MySQL 8.4 + FastAPI)

Infraestructura de backend contenerizada para el asistente robótico **FIFO**, con persistencia relacional en **MySQL 8.4 LTS**, panel de administración **Adminer** y API REST en **FastAPI**.

---

## 🏗️ Servicios Incluidos

| Servicio | Contenedor | Puerto Host | Descripción |
| :--- | :--- | :--- | :--- |
| **MySQL 8.4 LTS** | `fifo-mysql` | `3306` | Base de datos relacional con soporte utf8mb4, triggers de seguridad y procedimiento almacenado `sp_recall_past_context`. |
| **Adminer** | `fifo-adminer` | `8080` | Panel web visual para administrar tablas y ejecutar consultas SQL (`http://localhost:8080`). |
| **FastAPI REST** | `fifo-api` | `8090` | API REST para sincronizar datos con la app Android móvil y ejecutar consultas de memoria profunda (`http://localhost:8090/docs`). |

---

## 🚀 Inicio Rápido

### 1. Iniciar los contenedores

Desde la carpeta `backend/`:

```bash
cd backend
docker compose up -d --build
```

Esto automáticamente:
1. Crea la red `fifo-net` y el volumen persistente `fifo_mysql_data`.
2. Ejecuta los scripts de inicialización en orden:
   - `init/01_schema.sql` (creación de todas las tablas, índices, restricciones y procedimientos).
   - `init/02_seed_lucia.sql` (carga inicial de datos de prueba multi-usuario: Lucía, Sofía, Mateo, Valentina y Diego).
3. Levanta la API REST en el puerto `8090` y Adminer en el puerto `8080`.

### 2. Acceso al Panel de Base de Datos (Adminer)

Abre en tu navegador: **[http://localhost:8080](http://localhost:8080)**

- **Sistema:** MySQL
- **Servidor:** `fifo-mysql` (o `localhost` si te conectas externamente con DBeaver/Workbench al puerto 3306)
- **Usuario:** `fifo_app` (o `root`)
- **Contraseña:** `fifo_app_secret_2026` (o `fifo_root_secret_2026`)
- **Base de datos:** `fifo_db`

### 3. Documentación Interactiva de la API (Swagger UI)

Abre en tu navegador: **[http://localhost:8090/docs](http://localhost:8090/docs)**

---

## 📱 Conexión desde la Aplicación Android

- **En el Emulador de Android Studio:** Usa la dirección `http://10.0.2.2:8090` (que apunta al `localhost` de tu computadora).
- **En un Teléfono Físico por Wi-Fi:** Usa la dirección IP local de tu PC en la red local (ej: `http://192.168.1.150:8090`).
