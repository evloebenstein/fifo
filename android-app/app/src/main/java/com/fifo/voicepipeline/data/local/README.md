# 📱 Local Database Engine (`com.fifo.voicepipeline.data.local`)

Motor de base de datos relacional local en **SQLite** integrado en la app Android.

---

## 🗺️ Mapa de Archivos del Directorio

| Archivo | Rol y Responsabilidad |
| :--- | :--- |
| [`FifoDatabaseHelper.kt`](FifoDatabaseHelper.kt) | **Manejador SQLite (`SQLiteOpenHelper`)**: Crea y administra las tablas locales (`users`, `user_profiles`, `user_memories`, `taste_stories`, `user_reminders`, `user_contacts`, `daily_activities`, `user_friends`). Proporciona métodos CRUD fuertemente tipados, transacciones seguras y soporte multi-usuario con `user_id`. Garantiza que Fifo conozca los recuerdos y recordatorios vitales instantáneamente, sin depender de conexión a internet. |

---

## 📊 Tablas Gestionadas en SQLite

- `users`: Datos demográficos, ciudad, fecha y año de nacimiento, frase de presentación.
- `user_profiles`: Biografía y tags de intereses en formato JSON.
- `user_memories`: Recuerdos con categoría, descripción emocional y fecha.
- `taste_stories`: Anécdotas profundas sobre por qué le gusta cada elemento.
- `user_reminders`: Horarios, repetición y estado completado.
- `user_contacts`: Agenda familiar y números telefónicos.
- `user_friends`: Lista de amigos de la red Fifo.
- `daily_activities`: Tareas de bienestar y estimulación cognitiva.

---

## 🧭 Reglas de Programación en `local/`
1. **Paridad de Esquema:** Los nombres de columnas y tipos en SQLite deben ser idénticos a los de `backend/init/01_schema.sql` para facilitar la serialización/deserialización.
2. **Cierre de Cursores:** Utilizar siempre el bloque `use { cursor -> ... }` de Kotlin para evitar memory leaks al leer de SQLite.
