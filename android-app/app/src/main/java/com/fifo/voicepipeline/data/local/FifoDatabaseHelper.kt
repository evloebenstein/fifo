package com.fifo.voicepipeline.data.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.fifo.voicepipeline.ui.model.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * Base de datos relacional SQLite local de FIFO para Android.
 *
 * Implementa el esquema relacional de 01_schema.sql en el dispositivo móvil:
 * - Tablas: users, user_profiles, tastes, taste_stories, social_posts,
 *           reminders_and_events, past_conversations, memories, device_locations,
 *           conversation_journal (diario durable de turnos).
 * - Persistencia permanente de los datos del adulto mayor (100% offline).
 * - Sincronizable con el backend MySQL contenerizado.
 */
class FifoDatabaseHelper private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "FifoDatabaseHelper"
        private const val DATABASE_NAME = "fifo_local.db"
        private const val DATABASE_VERSION = 7
        private val gson = Gson()

        @Volatile
        private var instance: FifoDatabaseHelper? = null

        fun getInstance(context: Context): FifoDatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: FifoDatabaseHelper(context).also { instance = it }
            }
        }
    }

    val communityActivities = CommunityActivityDao(
        readableDatabase = { readableDatabase },
        writableDatabase = { writableDatabase },
        gson = gson
    )

    val userProfile = UserProfileDao(
        readableDatabase = { readableDatabase },
        writableDatabase = { writableDatabase },
        gson = gson
    )

    val tastes = TasteDao(
        readableDatabase = { readableDatabase },
        writableDatabase = { writableDatabase },
        gson = gson
    )

    val reminders = ReminderDao(
        readableDatabase = { readableDatabase },
        writableDatabase = { writableDatabase }
    )

    val memories = MemoryDao(
        readableDatabase = { readableDatabase },
        writableDatabase = { writableDatabase }
    )

    val socialPosts = SocialPostDao(
        readableDatabase = { readableDatabase },
        writableDatabase = { writableDatabase }
    )

    val deviceLocation = DeviceLocationDao(
        readableDatabase = { readableDatabase },
        writableDatabase = { writableDatabase }
    )

    val pastConversations = PastConversationDao(
        readableDatabase = { readableDatabase },
        writableDatabase = { writableDatabase }
    )

    val conversationFragments = ConversationFragmentDao(
        readableDatabase = { readableDatabase },
        writableDatabase = { writableDatabase },
        gson = gson
    )

    val dailyRoutines = DailyRoutineDao(
        readableDatabase = { readableDatabase },
        writableDatabase = { writableDatabase }
    )

    val relationalContacts = RelationalContactDao(
        readableDatabase = { readableDatabase },
        writableDatabase = { writableDatabase },
        gson = gson
    )

    val syncBundleWriter = SyncBundleWriter(
        writableDatabase = { writableDatabase },
        userProfile = userProfile,
        tastes = tastes,
        reminders = reminders,
        memories = memories,
        socialPosts = socialPosts,
        pastConversations = pastConversations,
        conversationFragments = conversationFragments,
        deviceLocation = deviceLocation,
        relationalContacts = relationalContacts,
        dailyRoutines = dailyRoutines
    )

    override fun onCreate(db: SQLiteDatabase) {
        Log.i(TAG, "Creando tablas relacionales de Fifo en SQLite...")

        // 1. users
        db.execSQL("""
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
        """.trimIndent())

        // 2. user_profiles
        db.execSQL("""
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
        """.trimIndent())

        // 3. tastes
        db.execSQL("""
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
        """.trimIndent())

        // 4. taste_stories
        db.execSQL("""
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
        """.trimIndent())

        // 5. social_posts
        db.execSQL("""
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
        """.trimIndent())

        // 6. reminders_and_events
        db.execSQL("""
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
        """.trimIndent())

        // 7. past_conversations
        db.execSQL("""
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
        """.trimIndent())

        // 8. memories
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS memories (
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL,
                emoji TEXT NOT NULL DEFAULT '⭐',
                title TEXT NOT NULL,
                detail TEXT NOT NULL,
                learned_date_label TEXT NOT NULL DEFAULT 'Aprendido recientemente',
                created_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """.trimIndent())

        // 9. device_locations
        db.execSQL("""
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
        """.trimIndent())

        // 10. conversation_fragments (doble capa local)
        db.execSQL("""
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
        """.trimIndent())

        // 11. user_contacts_relational (grafo social & cercanía)
        db.execSQL("""
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
        """.trimIndent())

        // 12. user_daily_routines (hábitos y rutinas circadianas)
        db.execSQL("""
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
        """.trimIndent())

        // 13. contact_conversation_mentions (puente relacional local)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS contact_conversation_mentions (
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL,
                contact_id TEXT NOT NULL,
                conversation_id TEXT NOT NULL,
                snippet_mention TEXT,
                mentioned_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
        """.trimIndent())

        // 14. community_activities & activity_participants (Encuentros y actividades comunitarias)
        db.execSQL("""
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
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS activity_participants (
                activity_id TEXT NOT NULL,
                user_id TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'joined',
                joined_at TEXT DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (activity_id, user_id)
            )
        """.trimIndent())

        // 15. conversation_journal (diario durable de turnos + cola pendiente)
        ConversationJournalSchema.create(db)

        // Sembrar datos iniciales
        seedInitialData(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.w(TAG, "Actualizando base de datos local de v$oldVersion a v$newVersion")
        if (oldVersion < 2) {
            db.execSQL("""
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
            """.trimIndent())
        }
        if (oldVersion < 3) {
            db.execSQL("""
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
                    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("""
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
            """.trimIndent())
        }
        if (oldVersion < 4) {
            try {
                db.execSQL("ALTER TABLE user_contacts_relational ADD COLUMN linked_conversation_ids TEXT")
            } catch (e: Exception) {
                Log.w(TAG, "Columna linked_conversation_ids ya existía o error: ${e.message}")
            }
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS contact_conversation_mentions (
                    id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    contact_id TEXT NOT NULL,
                    conversation_id TEXT NOT NULL,
                    snippet_mention TEXT,
                    mentioned_at TEXT DEFAULT CURRENT_TIMESTAMP
                )
            """.trimIndent())
        }
        if (oldVersion < 5) {
            try {
                db.execSQL("ALTER TABLE users ADD COLUMN username TEXT")
            } catch (e: Exception) {
                Log.w(TAG, "Columna username ya existía o error: ${e.message}")
            }
        }
        if (oldVersion < 6) {
            db.execSQL("""
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
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS activity_participants (
                    activity_id TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'joined',
                    joined_at TEXT DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (activity_id, user_id)
                )
            """.trimIndent())
        }
        if (oldVersion < 7) {
            ConversationJournalSchema.create(db)
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        db.execSQL("""
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
        """.trimIndent())
        db.execSQL("""
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
                FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
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
        """.trimIndent())
        try {
            db.execSQL("ALTER TABLE user_profiles ADD COLUMN interest_tags TEXT")
        } catch (_: Exception) {
            // Columna ya existe
        }
        try {
            db.execSQL("ALTER TABLE users ADD COLUMN username TEXT")
        } catch (_: Exception) {
            // Columna ya existe
        }
        ConversationJournalSchema.create(db)
    }


    private fun seedInitialData(db: SQLiteDatabase) {
        // Base de datos limpia: los datos reales se crean dinámicamente en el onboarding o se sincronizan desde el backend.
    }


}

