# 📱 Local Database Engine (`com.fifo.voicepipeline.data.local`)

Motor de base de datos relacional local en **SQLite** integrado en la app Android.
La versión actual del esquema es **v8**.

---

## 🗺️ Mapa de Archivos del Directorio

| Archivo | Rol y Responsabilidad |
| :--- | :--- |
| [`FifoDatabaseHelper.kt`](FifoDatabaseHelper.kt) | **Manejador SQLite (`SQLiteOpenHelper`)**: crea y migra las tablas locales, mantiene el singleton y compone los DAOs. En v8 agrega `contact_appearances` sin tocar las tablas existentes; ya no contiene SQL operativo de dominio ni la sincronización del bundle. |
| [`UserProfileDao.kt`](UserProfileDao.kt) | DAO de `users` y `user_profiles`: perfil, búsqueda por identificador, demografía, bio, intereses, verificación de usernames y upsert del perfil sincronizado (ADR-0017, ADR-0028). Delega la recomendación en `UsernameRecommender`. |
| [`TasteDao.kt`](TasteDao.kt) | DAO de `tastes` y `taste_stories`: gustos activos, altas, baja lógica, historias y tags JSON (ADR-0019). |
| [`ReminderDao.kt`](ReminderDao.kt) | DAO de `reminders_and_events`: consulta, alta, completar y borrar (ADR-0020). |
| [`MemoryDao.kt`](MemoryDao.kt) | DAO de `memories`: consulta por usuario y alta con ID generado (ADR-0021). |
| [`SocialPostDao.kt`](SocialPostDao.kt) | DAO de `social_posts`: listado, alta y borrado por ID (ADR-0022). |
| [`DeviceLocationDao.kt`](DeviceLocationDao.kt) | DAO de `device_locations`: lectura por dispositivo y upsert (ADR-0023). |
| [`PastConversationDao.kt`](PastConversationDao.kt) | DAO de `past_conversations`: listado, alta y borrado por usuario (ADR-0024). |
| [`ConversationFragmentDao.kt`](ConversationFragmentDao.kt) | DAO de `conversation_fragments`: listado, alta y codec JSON (ADR-0025). |
| [`DailyRoutineDao.kt`](DailyRoutineDao.kt) | DAO de `user_daily_routines`: rutinas activas y upsert (ADR-0026). |
| [`RelationalContactDao.kt`](RelationalContactDao.kt) | DAO de `user_contacts_relational`: lectura, fusión e ingesta por lotes (ADR-0027). |
| [`ContactAppearanceDao.kt`](ContactAppearanceDao.kt) | DAO de `contact_appearances`: procedencia append-only del grafo relacional, sin FK para sobrevivir a los reemplazos del contacto (ADR-0031). |
| [`SyncBundleWriter.kt`](SyncBundleWriter.kt) | Escritor transaccional del bundle remoto: perfil y agregados en una sola transacción (ADR-0028). |
| [`UsernameRecommender.kt`](UsernameRecommender.kt) | Política pura de recomendación de usernames: normalización, buckets, filtro de contenido y unicidad inyectada (ADR-0018). |
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
- `contact_appearances`: dónde apareció cada persona (libreta, FifoAmigos, conversación).
- `user_daily_routines`: hábitos y rutinas circadianas.
- `contact_conversation_mentions`: menciones de contactos en conversaciones.
- `community_activities` y `activity_participants`: actividades comunitarias e inscripciones.
- `conversation_journal`: **diario durable de turnos y cola de consolidación**.

### `conversation_journal` (v7, diario durable)

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

### `contact_appearances` (v8)

Procedencia local del grafo relacional: registra dónde apareció cada persona sin
duplicar la conversación. Es **append-only**, no tiene clave foránea y no se
sincroniza con el backend.

| Columna | Tipo | Función |
| :--- | :--- | :--- |
| `id` | `TEXT PRIMARY KEY` | ID determinístico (`app_<hash>` sobre contacto, fuente y origen). |
| `user_id` | `TEXT` | Usuario dueño del grafo. |
| `contact_id` | `TEXT` | Contacto de `user_contacts_relational`. |
| `source` | `TEXT` | `PHONE_CONTACT`, `FIFO_FRIEND`, `CONVERSATION`, `CALL` o `MANUAL`. |
| `source_id` | `TEXT?` | Teléfono, `friendId` de FifoAmigos o `conversationId`. |
| `snippet` | `TEXT?` | Contexto inmediato de la aparición. |
| `occurred_at` | `TEXT` | ISO-8601 UTC de la ingesta. |

Índice: `idx_contact_appearances_user_contact (user_id, contact_id)`.

---

## 🧭 Reglas de Programación en `local/`

1. **Paridad de esquema:** los nombres de columnas y tipos de las tablas sincronizadas con el backend deben coincidir con `backend/init/01_schema.sql` para facilitar la serialización.
2. **Migraciones incrementales:** cada versión nueva debe agregar su bloque `if (oldVersion < N)` y no recrear tablas existentes.
3. **Cierre de cursores:** usar siempre `use { cursor -> ... }` para evitar fugas al leer SQLite.
4. **Diario antes de hablar:** `SqliteConversationJournal.append()` es síncrono y se llama antes de `speakResponse`; no mover esa escritura después del TTS.
5. **DAOs por agregado:** cuando un agregado tiene un límite claro, su SQL operativo vive en un DAO del directorio; `FifoDatabaseHelper` solo lo compone y conserva el esquema/migraciones globales.
6. **Políticas puras fuera del DAO:** la lógica de decisión sin SQLite (por ejemplo `UsernameRecommender`) vive en un objeto puro y recibe la consulta de unicidad como función.
7. **Procedencia local-only:** `contact_appearances` documenta el grafo en el dispositivo; no se serializa al backend ni se usa para decidir llamadas. La fusión de fuentes vive en `RelationalGraphCoordinator` (ADR-0031).
