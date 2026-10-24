package com.fifo.voicepipeline.data.local

/**
 * Una sentencia DDL del esquema local.
 *
 * [tolerant] marca los `ALTER TABLE ... ADD COLUMN`, que fallan cuando la
 * columna ya existe. El helper los ejecuta dentro de un `try/catch` para que
 * una base ya reparada siga abriendo; el resto de sentencias debe fallar de
 * forma visible.
 */
internal data class SchemaStatement(
    val sql: String,
    val tolerant: Boolean = false
)

/**
 * Fuente única del esquema SQLite de Fifo (`fifo_local.db`).
 *
 * El helper solo compone y ejecuta estas sentencias. Cualquier tabla, columna o
 * índice nuevo debe declararse aquí y, si se introduce en una versión nueva,
 * añadirse también a [upgradeStatements].
 *
 * `conversation_journal` no vive aquí: su ciclo de vida pertenece al módulo de
 * memoria y lo aporta [ConversationJournalSchema].
 */
internal object FifoSchema {

    /** Versión de esquema que espera esta app. */
    const val VERSION = 9

    // ══════════════════════════════════════════════════════════════
    //  DDL de tablas e índices
    // ══════════════════════════════════════════════════════════════

    private val USERS = """
        CREATE TABLE IF NOT EXISTS users (
            id TEXT PRIMARY KEY,
            full_name TEXT NOT NULL,
            username TEXT,
            email TEXT NOT NULL UNIQUE,
            birth_date TEXT NOT NULL,
            birth_year INTEGER NOT NULL,
            estimated_age INTEGER NOT NULL,
            gender_identity TEXT NOT NULL DEFAULT 'Mujer',
            city TEXT NOT NULL DEFAULT 'Santiago, Chile',
            avatar_url TEXT,
            avatar_bg_hex INTEGER NOT NULL DEFAULT 4293514751,
            short_quote TEXT,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP,
            updated_at TEXT DEFAULT CURRENT_TIMESTAMP
        )
    """.trimIndent()

    private val USER_PROFILES = """
        CREATE TABLE IF NOT EXISTS user_profiles (
            user_id TEXT PRIMARY KEY,
            bio_ai TEXT NOT NULL,
            emergency_contact_name TEXT,
            emergency_contact_phone TEXT,
            preferred_address TEXT,
            continuous_listening_enabled INTEGER NOT NULL DEFAULT 0,
            interest_tags TEXT,
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
        )
    """.trimIndent()

    private val TASTES = """
        CREATE TABLE IF NOT EXISTS tastes (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL,
            name TEXT NOT NULL,
            category TEXT NOT NULL DEFAULT 'general',
            is_active INTEGER NOT NULL DEFAULT 1,
            source_conversation_id TEXT,
            learned_at TEXT DEFAULT CURRENT_TIMESTAMP,
            UNIQUE (user_id, name)
        )
    """.trimIndent()

    private val TASTE_STORIES = """
        CREATE TABLE IF NOT EXISTS taste_stories (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL,
            title TEXT NOT NULL,
            subtitle TEXT NOT NULL,
            description TEXT NOT NULL,
            tags TEXT NOT NULL,
            icon_category TEXT NOT NULL DEFAULT 'heart',
            is_sensitive INTEGER NOT NULL DEFAULT 0,
            explicit_consent INTEGER NOT NULL DEFAULT 0,
            privacy_level TEXT NOT NULL DEFAULT 'public_profile',
            learned_from TEXT NOT NULL,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP
        )
    """.trimIndent()

    private val SOCIAL_POSTS = """
        CREATE TABLE IF NOT EXISTS social_posts (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL,
            author_name TEXT NOT NULL,
            author_age INTEGER NOT NULL,
            relation_label TEXT NOT NULL DEFAULT 'Comunidad Fifo',
            category TEXT NOT NULL DEFAULT 'Bienestar',
            content TEXT NOT NULL,
            time_ago_label TEXT NOT NULL DEFAULT 'Hace poco',
            is_sensitive INTEGER NOT NULL DEFAULT 0,
            explicit_consent INTEGER NOT NULL DEFAULT 0,
            likes_count INTEGER NOT NULL DEFAULT 0,
            comments_count INTEGER NOT NULL DEFAULT 0,
            accent_color_hex INTEGER NOT NULL DEFAULT 4281908728,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP
        )
    """.trimIndent()

    private val REMINDERS_AND_EVENTS = """
        CREATE TABLE IF NOT EXISTS reminders_and_events (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL,
            title TEXT NOT NULL,
            time_str TEXT NOT NULL,
            due_datetime TEXT,
            repeat_rule TEXT NOT NULL DEFAULT 'daily',
            category TEXT NOT NULL DEFAULT 'medication',
            is_completed INTEGER NOT NULL DEFAULT 0,
            calendar_event_id INTEGER,
            spoken_text TEXT NOT NULL,
            created_at TEXT DEFAULT CURRENT_TIMESTAMP,
            updated_at TEXT DEFAULT CURRENT_TIMESTAMP
        )
    """.trimIndent()

    private val PAST_CONVERSATIONS = """
        CREATE TABLE IF NOT EXISTS past_conversations (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL,
            title TEXT NOT NULL,
            date_label TEXT NOT NULL DEFAULT 'Hoy',
            duration_label TEXT NOT NULL DEFAULT '5 min',
            duration_seconds INTEGER NOT NULL DEFAULT 300,
            summary TEXT NOT NULL,
            topic_tag TEXT NOT NULL DEFAULT 'Conversación',
            icon_name TEXT NOT NULL DEFAULT 'heart',
            recorded_at TEXT DEFAULT CURRENT_TIMESTAMP
        )
    """.trimIndent()

    private val MEMORIES = """
        CREATE TABLE IF NOT EXISTS memories (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL,
            emoji TEXT NOT NULL DEFAULT '⭐',
            title TEXT NOT NULL,
            detail TEXT NOT NULL,
            learned_date_label TEXT NOT NULL DEFAULT 'Aprendido recientemente',
            created_at TEXT DEFAULT CURRENT_TIMESTAMP
        )
    """.trimIndent()

    private val DEVICE_LOCATIONS = """
        CREATE TABLE IF NOT EXISTS device_locations (
            device_id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL,
            is_connected INTEGER NOT NULL DEFAULT 0,
            last_connected_time TEXT NOT NULL DEFAULT 'Desconectado',
            last_known_latitude REAL NOT NULL DEFAULT -33.4255,
            last_known_longitude REAL NOT NULL DEFAULT -70.6143,
            last_known_address TEXT NOT NULL DEFAULT 'Av. Providencia 1234, Santiago',
            last_known_room TEXT NOT NULL DEFAULT 'Cerca del Living / Mesa de noche',
            signal_strength_rssi INTEGER NOT NULL DEFAULT -64,
            is_beeping INTEGER NOT NULL DEFAULT 0,
            updated_at TEXT DEFAULT CURRENT_TIMESTAMP
        )
    """.trimIndent()

    private val CONVERSATION_FRAGMENTS = """
        CREATE TABLE IF NOT EXISTS conversation_fragments (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL,
            server_conversation_id TEXT NOT NULL,
            key_topics TEXT NOT NULL,
            named_entities TEXT NOT NULL,
            detected_mood TEXT NOT NULL DEFAULT 'neutral',
            compact_summary TEXT NOT NULL,
            primary_tag TEXT NOT NULL DEFAULT 'General',
            duration_seconds INTEGER NOT NULL DEFAULT 180,
            recorded_at TEXT DEFAULT CURRENT_TIMESTAMP
        )
    """.trimIndent()

    private val USER_CONTACTS_RELATIONAL = """
        CREATE TABLE IF NOT EXISTS user_contacts_relational (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL,
            contact_name TEXT NOT NULL,
            phone_number TEXT,
            relationship_role TEXT NOT NULL,
            closeness_score REAL NOT NULL DEFAULT 0.5,
            trust_tier INTEGER NOT NULL DEFAULT 2,
            emotional_valence TEXT NOT NULL DEFAULT 'afectuoso',
            contextual_memory TEXT,
            mention_count INTEGER NOT NULL DEFAULT 1,
            last_mentioned_at TEXT DEFAULT CURRENT_TIMESTAMP,
            linked_conversation_ids TEXT,
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
        )
    """.trimIndent()

    private val CONTACT_APPEARANCES = """
        CREATE TABLE IF NOT EXISTS contact_appearances (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL,
            contact_id TEXT NOT NULL,
            source TEXT NOT NULL,
            source_id TEXT,
            snippet TEXT,
            occurred_at TEXT DEFAULT CURRENT_TIMESTAMP
        )
    """.trimIndent()

    private const val CONTACT_APPEARANCES_INDEX =
        "CREATE INDEX IF NOT EXISTS idx_contact_appearances_user_contact " +
            "ON contact_appearances(user_id, contact_id)"

    private val CONTACT_PHONE_HINTS = """
        CREATE TABLE IF NOT EXISTS contact_phone_hints (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL,
            contact_id TEXT NOT NULL,
            phone_number TEXT NOT NULL,
            snippet TEXT,
            status TEXT NOT NULL DEFAULT 'PENDING',
            created_at TEXT DEFAULT CURRENT_TIMESTAMP
        )
    """.trimIndent()

    private const val CONTACT_PHONE_HINTS_INDEX =
        "CREATE INDEX IF NOT EXISTS idx_contact_phone_hints_user_contact " +
            "ON contact_phone_hints(user_id, contact_id, status)"

    private val USER_DAILY_ROUTINES = """
        CREATE TABLE IF NOT EXISTS user_daily_routines (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL,
            routine_name TEXT NOT NULL,
            category TEXT NOT NULL DEFAULT 'medication',
            time_anchor TEXT NOT NULL DEFAULT 'morning',
            typical_time_str TEXT,
            frequency_rule TEXT NOT NULL DEFAULT 'daily',
            confidence_score REAL NOT NULL DEFAULT 0.8,
            notes TEXT,
            is_active INTEGER NOT NULL DEFAULT 1,
            last_observed_at TEXT DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
        )
    """.trimIndent()

    private val CONTACT_CONVERSATION_MENTIONS = """
        CREATE TABLE IF NOT EXISTS contact_conversation_mentions (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL,
            contact_id TEXT NOT NULL,
            conversation_id TEXT NOT NULL,
            snippet_mention TEXT,
            mentioned_at TEXT DEFAULT CURRENT_TIMESTAMP
        )
    """.trimIndent()

    private val COMMUNITY_ACTIVITIES = """
        CREATE TABLE IF NOT EXISTS community_activities (
            id TEXT PRIMARY KEY,
            title TEXT NOT NULL,
            description TEXT NOT NULL DEFAULT '',
            spots_label TEXT NOT NULL,
            spots_max INTEGER NOT NULL DEFAULT 15,
            spots_taken INTEGER NOT NULL DEFAULT 0,
            date_label TEXT NOT NULL,
            location_name TEXT NOT NULL,
            distance_label TEXT NOT NULL DEFAULT 'Cerca de ti',
            icon_category TEXT NOT NULL DEFAULT 'book',
            organizer_id TEXT NOT NULL DEFAULT '',
            organizer_name TEXT NOT NULL DEFAULT 'Fifo',
            organizer_type TEXT NOT NULL DEFAULT 'fifo',
            target_tastes TEXT NOT NULL DEFAULT '[]',
            created_at TEXT DEFAULT CURRENT_TIMESTAMP
        )
    """.trimIndent()

    private val ACTIVITY_PARTICIPANTS = """
        CREATE TABLE IF NOT EXISTS activity_participants (
            activity_id TEXT NOT NULL,
            user_id TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'joined',
            joined_at TEXT DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (activity_id, user_id)
        )
    """.trimIndent()

    private const val ADD_LINKED_CONVERSATION_IDS =
        "ALTER TABLE user_contacts_relational ADD COLUMN linked_conversation_ids TEXT"

    private const val ADD_USER_PROFILES_INTEREST_TAGS =
        "ALTER TABLE user_profiles ADD COLUMN interest_tags TEXT"

    private const val ADD_USERS_USERNAME =
        "ALTER TABLE users ADD COLUMN username TEXT"

    // ══════════════════════════════════════════════════════════════
    //  Composiciones
    // ══════════════════════════════════════════════════════════════

    /**
     * Esquema completo de una base nueva, en orden de creación.
     */
    val createStatements: List<SchemaStatement> = listOf(
        USERS,
        USER_PROFILES,
        TASTES,
        TASTE_STORIES,
        SOCIAL_POSTS,
        REMINDERS_AND_EVENTS,
        PAST_CONVERSATIONS,
        MEMORIES,
        DEVICE_LOCATIONS,
        CONVERSATION_FRAGMENTS,
        USER_CONTACTS_RELATIONAL,
        CONTACT_APPEARANCES,
        CONTACT_APPEARANCES_INDEX,
        CONTACT_PHONE_HINTS,
        CONTACT_PHONE_HINTS_INDEX,
        USER_DAILY_ROUTINES,
        CONTACT_CONVERSATION_MENTIONS,
        COMMUNITY_ACTIVITIES,
        ACTIVITY_PARTICIPANTS
    ).map(::idempotent)

    /**
     * Reparación idempotente que se aplica en cada apertura. Corrige bases
     * creadas por versiones antiguas que omitían tablas o columnas.
     */
    val ensureStatements: List<SchemaStatement> = listOf(
        idempotent(CONVERSATION_FRAGMENTS),
        idempotent(USER_CONTACTS_RELATIONAL),
        idempotent(CONTACT_APPEARANCES),
        idempotent(CONTACT_APPEARANCES_INDEX),
        idempotent(CONTACT_PHONE_HINTS),
        idempotent(CONTACT_PHONE_HINTS_INDEX),
        idempotent(USER_DAILY_ROUTINES),
        SchemaStatement(ADD_USER_PROFILES_INTEREST_TAGS, tolerant = true),
        SchemaStatement(ADD_USERS_USERNAME, tolerant = true)
    )

    /**
     * Sentencias incrementales para migrar una base existente.
     *
     * Incluye únicamente lo que pertenece a este esquema; el helper añade el
     * diario durable según [ConversationJournalSchema.INTRODUCED_VERSION].
     */
    fun upgradeStatements(oldVersion: Int): List<SchemaStatement> = buildList {
        if (oldVersion < 2) {
            add(idempotent(CONVERSATION_FRAGMENTS))
        }
        if (oldVersion < 3) {
            add(idempotent(USER_CONTACTS_RELATIONAL))
            add(idempotent(USER_DAILY_ROUTINES))
        }
        if (oldVersion < 4) {
            add(SchemaStatement(ADD_LINKED_CONVERSATION_IDS, tolerant = true))
            add(idempotent(CONTACT_CONVERSATION_MENTIONS))
        }
        if (oldVersion < 5) {
            add(SchemaStatement(ADD_USERS_USERNAME, tolerant = true))
        }
        if (oldVersion < 6) {
            add(idempotent(COMMUNITY_ACTIVITIES))
            add(idempotent(ACTIVITY_PARTICIPANTS))
        }
        if (oldVersion < 8) {
            add(idempotent(CONTACT_APPEARANCES))
            add(idempotent(CONTACT_APPEARANCES_INDEX))
        }
        if (oldVersion < 9) {
            add(idempotent(CONTACT_PHONE_HINTS))
            add(idempotent(CONTACT_PHONE_HINTS_INDEX))
        }
    }

    private fun idempotent(sql: String) = SchemaStatement(sql)
}
