# 📱 Local Database Engine (`com.fifo.voicepipeline.data.local`)

Motor de base de datos relacional local en **SQLite** integrado en la app Android.
La versión actual del esquema es **v7**.

---

## 🗺️ Mapa de Archivos del Directorio

| Archivo | Rol y Responsabilidad |
| :--- | :--- |
| [`FifoDatabaseHelper.kt`](FifoDatabaseHelper.kt) | **Manejador SQLite (`SQLiteOpenHelper`)**: crea y migra las tablas locales, ofrece CRUD tipado de las tablas que aún no tienen DAO, transacciones y soporte multi-usuario con `user_id`. En v7 agrega `conversation_journal` sin tocar las tablas existentes; desde ADR-0015/0016 delega el diario y las actividades comunitarias. |
| [`CommunityActivityDao.kt`](CommunityActivityDao.kt) | DAO de `community_activities` y `activity_participants`: consulta, alta, join/leave, borrado y siembra inicial (ADR-0016). |
| [`ConversationJournalSchema.kt`](ConversationJournalSchema.kt) | Esquema del diario durable: `CREATE TABLE` de `conversation_journal` e índice `idx_conversation_journal_pending`. |
| [`SqliteConversationJournal.kt`](SqliteConversationJournal.kt) | Implementación durable de `ConversationJournal`: dueña del CRUD; inserta turnos, consulta recientes/pendientes, marca consolidación, reintentos y reparaciones. |

---

## 📊 Tablas Gestionadas en SQLite

- `users`: datos demográficos, ciudad, fecha y año de nacimiento, frase de presentación.
- `user_profiles`: biografía, tags de intereses, contacto de emergencia y modo de escucha continua.
- `tastes`: gustos e intereses activos.
- `taste_stories`: anécdotas profundas sobre cada gusto.
- `social_posts`: publicaciones de la comunidad Fifo Amigos.
- `reminders_and_events`: recordatorios, horarios, repetición y estado completado.
- `past_conversations`: bitácora de conversaciones consolidadas.
- `memories`: recuerdos afectivos y notas aprendidas.
- `device_locations`: última ubicación y proximidad del robot Fifo.
- `conversation_fragments`: fragmentos compactos de memoria episódica.
- `user_contacts_relational`: grafo social local (parentesco, confianza, memoria contextual).
- `user_daily_routines`: hábitos y rutinas circadianas.
- `contact_conversation_mentions`: menciones de contactos en conversaciones.
- `community_activities` y `activity_participants`: actividades comunitarias e inscripciones.
- `conversation_journal`: **diario durable de turnos y cola de consolidación**.

### `conversation_journal` (v7)

| Columna | Tipo | Función |
| :--- | :--- | :--- |
| `id` | `TEXT PRIMARY KEY` | ID estable del turno (`JournalEntry.id`). |
| `user_id` | `TEXT` | Usuario dueño del turno. |
| `session_id` | `TEXT` | Sesión que produjo el turno. |
| `user_text` | `TEXT` | Texto del usuario. |
| `assistant_text` | `TEXT` | Respuesta original de Fifo. |
| `created_at` | `INTEGER` | Epoch ms del turno; base para la ventana de reparación. |
| `status` | `TEXT` | `PENDING` o `CONSOLIDATED`. |
| `attempts` | `INTEGER` | Intentos de consolidación fallidos. |
| `next_attempt_at` | `INTEGER` | Epoch ms del próximo intento (backoff). |
| `last_error` | `TEXT?` | Último error de consolidación. |
| `needs_repair` | `INTEGER` | Marca el turno como posible error del agente. |
| `repair_text` | `TEXT?` | Respuesta corregida que debe preferir la consolidación. |
| `repaired_at` | `INTEGER?` | Epoch ms de la reparación. |

Índice: `idx_conversation_journal_pending (status, next_attempt_at, created_at)` para
drenar la cola pendiente sin escanear toda la tabla.

---

## 🧭 Reglas de Programación en `local/`

1. **Paridad de esquema:** los nombres de columnas y tipos de las tablas sincronizadas con el backend deben coincidir con `backend/init/01_schema.sql` para facilitar la serialización.
2. **Migraciones incrementales:** cada versión nueva debe agregar su bloque `if (oldVersion < N)` y no recrear tablas existentes.
3. **Cierre de cursores:** usar siempre `use { cursor -> ... }` para evitar fugas al leer SQLite.
4. **Diario antes de hablar:** `SqliteConversationJournal.append()` es síncrono y se llama antes de `speakResponse`; no mover esa escritura después del TTS.
5. **DAOs por agregado:** cuando un agregado tiene un límite claro, su SQL operativo vive en un DAO del directorio; `FifoDatabaseHelper` solo lo compone y conserva el esquema/migraciones globales.
