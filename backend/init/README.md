# 🗄️ Database Initialization Scripts (`backend/init/`)

Scripts SQL ejecutados automáticamente por el contenedor oficial de MySQL (`/docker-entrypoint-initdb.d/`) en orden alfabético cuando el volumen persistente se crea por primera vez.

---

## 🗺️ Mapa de Archivos del Directorio

| Archivo | Rol y Responsabilidad |
| :--- | :--- |
| [`01_schema.sql`](01_schema.sql) | **Definición de Esquema (DDL)**: Crea la base de datos `fifo_db`, usuarios con permisos estrictos, tablas relacionales normalizadas en 3FN, índices compuestos para búsqueda ultrarrápida, claves foráneas con cascadas controladas y procedimientos almacenados (`sp_recall_past_context`). |
| [`02_seed_lucia.sql`](02_seed_lucia.sql) | **Semilla de Datos Inicial (DML)**: Carga datos de prueba realistas para 5 perfiles adultos mayores (Doña Lucía, Doña Sofía, Don Mateo, Valentina y Diego), con recuerdos categorizados, historias de gustos en detalle, contactos de emergencia y enlaces de compatibilidad en la red Fifo Amigos. |

---

## 📊 Tablas Clave Definidas en `01_schema.sql`

1. **`users`**: Cuenta principal, nombre, fecha y año de nacimiento, edad estimada, ciudad, avatar y frase de presentación.
2. **`user_profiles`**: Biografía enriquecida por IA (`bio_ai`), contacto de emergencia y etiquetas JSON de intereses (`interest_tags`).
3. **`user_memories`**: Recuerdos vivos del usuario indexados por categoría (`familiar`, `gusto`, `salud`, `rutina`) y relevancia emocional.
4. **`taste_stories`**: Fragmentos narrativos detallados sobre los gustos del usuario (anécdotas, motivaciones).
5. **`user_reminders`**: Recordatorios con hora, recurrencia, estado de completado y prioridad.
6. **`user_contacts_relational`**: Agenda de apoyo del adulto mayor (relación familiar, teléfono, notas).
7. **`user_friends`**: Red social de pares "Fifo Amigos" con puntuaciones de compatibilidad.
8. **`daily_activities`**: Actividades cognitivas, paseos o rutinas sugeridas.
9. **`conversations` & `conversation_turns`**: Historial cronológico de interacciones de voz entre el usuario y Fifo.

---

## 🧭 Reglas de Programación en `backend/init/`
1. **Orden de Ejecución:** El prefijo numérico (`01_`, `02_`) determina el orden de ejecución estricto. Los DDL de tablas y procedimientos deben ir en `01_` y los `INSERT` en `02_`.
2. **Compatibilidad con SQLite:** La aplicación móvil Android replica localmente en `FifoDatabaseHelper.kt` la misma estructura de campos para garantizar paridad entre la nube y el teléfono. Si añades un campo aquí, agrégalo también a la base de datos SQLite local del teléfono.
