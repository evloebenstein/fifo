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


    // ══════════════════════════════════════════════════════════
    //  LECTURAS Y ESCRITURAS DE CONTACTOS RELACIONALES Y RUTINAS
    // ══════════════════════════════════════════════════════════

    private fun parseStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(raw, type) ?: emptyList()
        } catch (_: Exception) {
            raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    fun getRelationalContacts(userId: String): List<RelationalContact> {
        val list = mutableListOf<RelationalContact>()
        readableDatabase.rawQuery(
            "SELECT id, contact_name, phone_number, relationship_role, closeness_score, trust_tier, emotional_valence, contextual_memory, mention_count, last_mentioned_at, linked_conversation_ids FROM user_contacts_relational WHERE user_id = ? ORDER BY closeness_score DESC, mention_count DESC",
            arrayOf(userId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val linkedIds = parseStringList(if (cursor.columnCount > 10) cursor.getString(10) else null)
                list.add(
                    RelationalContact(
                        id = cursor.getString(0) ?: "",
                        contactName = cursor.getString(1) ?: "",
                        phoneNumber = cursor.getString(2),
                        relationshipRole = cursor.getString(3) ?: "",
                        closenessScore = cursor.getFloat(4),
                        trustTier = cursor.getInt(5),
                        emotionalValence = cursor.getString(6) ?: "afectuoso",
                        contextualMemory = cursor.getString(7),
                        mentionCount = cursor.getInt(8),
                        lastMentionedAt = cursor.getString(9) ?: "",
                        linkedConversationIds = linkedIds
                    )
                )
            }
        }
        return list
    }

    fun addOrUpdateRelationalContact(userId: String, contact: RelationalContact) {
        val db = writableDatabase
        // 1. Verificar si ya existe por nombre o número telefónico para este usuario
        var existingId: String? = null
        var existingScore = 0.5f
        var existingMentions = 0
        var existingMemory: String? = null
        var existingLinkedStr: String? = null

        val queryName = contact.contactName.trim()
        val queryPhone = contact.phoneNumber?.trim()

        val selectSql = if (!queryPhone.isNullOrBlank()) {
            "SELECT id, closeness_score, mention_count, contextual_memory, linked_conversation_ids FROM user_contacts_relational WHERE user_id = ? AND (LOWER(contact_name) = LOWER(?) OR phone_number = ?) LIMIT 1"
        } else {
            "SELECT id, closeness_score, mention_count, contextual_memory, linked_conversation_ids FROM user_contacts_relational WHERE user_id = ? AND LOWER(contact_name) = LOWER(?) LIMIT 1"
        }
        val selectArgs = if (!queryPhone.isNullOrBlank()) arrayOf(userId, queryName, queryPhone) else arrayOf(userId, queryName)

        try {
            db.rawQuery(selectSql, selectArgs).use { cursor ->
                if (cursor.moveToFirst()) {
                    existingId = cursor.getString(0)
                    existingScore = cursor.getFloat(1)
                    existingMentions = cursor.getInt(2)
                    existingMemory = cursor.getString(3)
                    if (cursor.columnCount > 4) existingLinkedStr = cursor.getString(4)
                }
            }
        } catch (_: Exception) {
            // Fallback si la columna aún se está migrando
            val fallbackSql = "SELECT id, closeness_score, mention_count, contextual_memory FROM user_contacts_relational WHERE user_id = ? AND LOWER(contact_name) = LOWER(?) LIMIT 1"
            db.rawQuery(fallbackSql, arrayOf(userId, queryName)).use { cursor ->
                if (cursor.moveToFirst()) {
                    existingId = cursor.getString(0)
                    existingScore = cursor.getFloat(1)
                    existingMentions = cursor.getInt(2)
                    existingMemory = cursor.getString(3)
                }
            }
        }

        val targetId = existingId ?: contact.id.ifBlank { "ctc_rel_${UUID.randomUUID().toString().take(8)}" }
        val finalScore = if (existingId != null && contact.closenessScore <= 0.5f && existingScore > 0.5f) existingScore else contact.closenessScore
        val finalMentions = if (existingId != null && contact.mentionCount == 0 && existingMentions > 0) existingMentions else contact.mentionCount
        val finalMemory = if (contact.contextualMemory.isNullOrBlank()) existingMemory else contact.contextualMemory
        val existingLinked = parseStringList(existingLinkedStr)
        val mergedLinked = (existingLinked + contact.linkedConversationIds).distinct()

        val cv = ContentValues().apply {
            put("id", targetId)
            put("user_id", userId)
            put("contact_name", contact.contactName)
            put("phone_number", contact.phoneNumber)
            put("relationship_role", contact.relationshipRole)
            put("closeness_score", finalScore)
            put("trust_tier", contact.trustTier)
            put("emotional_valence", contact.emotionalValence)
            put("contextual_memory", finalMemory)
            put("mention_count", finalMentions)
            put("linked_conversation_ids", gson.toJson(mergedLinked))
        }

        db.insertWithOnConflict("user_contacts_relational", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Ingesta o sincroniza por lotes los contactos reales del teléfono en SQLite de forma atómica.
     */
    fun syncDeviceContactsBatch(userId: String, contacts: List<RelationalContact>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            contacts.forEach { c ->
                addOrUpdateRelationalContact(userId, c)
            }
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            Log.e(TAG, "Error en syncDeviceContactsBatch: ${e.message}", e)
        } finally {
            db.endTransaction()
        }
    }

    fun getDailyRoutines(userId: String): List<DailyRoutine> {
        val list = mutableListOf<DailyRoutine>()
        readableDatabase.rawQuery(
            "SELECT id, routine_name, category, time_anchor, typical_time_str, frequency_rule, confidence_score, notes, is_active FROM user_daily_routines WHERE user_id = ? AND is_active = 1 ORDER BY typical_time_str ASC, confidence_score DESC",
            arrayOf(userId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    DailyRoutine(
                        id = cursor.getString(0) ?: "",
                        routineName = cursor.getString(1) ?: "",
                        category = cursor.getString(2) ?: "medication",
                        timeAnchor = cursor.getString(3) ?: "morning",
                        typicalTimeStr = cursor.getString(4),
                        frequencyRule = cursor.getString(5) ?: "daily",
                        confidenceScore = cursor.getFloat(6),
                        notes = cursor.getString(7),
                        isActive = cursor.getInt(8) == 1
                    )
                )
            }
        }
        return list
    }

    fun addOrUpdateDailyRoutine(userId: String, routine: DailyRoutine) {
        val cv = ContentValues().apply {
            put("id", routine.id.ifBlank { "rtn_${UUID.randomUUID().toString().take(8)}" })
            put("user_id", userId)
            put("routine_name", routine.routineName)
            put("category", routine.category)
            put("time_anchor", routine.timeAnchor)
            put("typical_time_str", routine.typicalTimeStr)
            put("frequency_rule", routine.frequencyRule)
            put("confidence_score", routine.confidenceScore)
            put("notes", routine.notes)
            put("is_active", if (routine.isActive) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("user_daily_routines", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }


    // ── Recordatorios ────────────────────────────────────────

    fun getReminders(userId: String): List<FifoReminderItem> {
        val list = mutableListOf<FifoReminderItem>()
        readableDatabase.rawQuery("SELECT id, title, time_str, category, is_completed FROM reminders_and_events WHERE user_id = ? ORDER BY is_completed ASC, time_str ASC", arrayOf(userId)).use { cur ->
            while (cur.moveToNext()) {
                list.add(
                    FifoReminderItem(
                        id = cur.getString(0),
                        title = cur.getString(1),
                        timeStr = cur.getString(2),
                        category = cur.getString(3) ?: "medication",
                        isCompleted = cur.getInt(4) == 1
                    )
                )
            }
        }
        return list
    }

    fun addReminder(userId: String, reminder: FifoReminderItem) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("id", reminder.id.ifBlank { "rem_${UUID.randomUUID().toString().take(8)}" })
            put("user_id", userId)
            put("title", reminder.title)
            put("time_str", reminder.timeStr)
            put("category", reminder.category)
            put("is_completed", if (reminder.isCompleted) 1 else 0)
            put("spoken_text", "Es hora de su recordatorio: ${reminder.title}")
        }
        db.insertWithOnConflict("reminders_and_events", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun completeReminder(userId: String, reminderId: String, completed: Boolean = true) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("is_completed", if (completed) 1 else 0)
        }
        db.update("reminders_and_events", cv, "user_id = ? AND id = ?", arrayOf(userId, reminderId))
    }

    fun deleteReminder(userId: String, reminderId: String) {
        writableDatabase.delete("reminders_and_events", "user_id = ? AND id = ?", arrayOf(userId, reminderId))
    }

    // ── Recuerdos ────────────────────────────────────────────

    fun getMemories(userId: String): List<FifoMemoryItem> {
        val list = mutableListOf<FifoMemoryItem>()
        readableDatabase.rawQuery("SELECT id, emoji, title, detail, learned_date_label FROM memories WHERE user_id = ? ORDER BY created_at DESC", arrayOf(userId)).use { cur ->
            while (cur.moveToNext()) {
                list.add(
                    FifoMemoryItem(
                        id = cur.getString(0),
                        emoji = cur.getString(1) ?: "⭐",
                        title = cur.getString(2),
                        detail = cur.getString(3),
                        learnedDate = cur.getString(4) ?: ""
                    )
                )
            }
        }
        return list
    }

    fun addMemory(userId: String, item: FifoMemoryItem) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("id", item.id.ifBlank { "mem_${UUID.randomUUID().toString().take(8)}" })
            put("user_id", userId)
            put("emoji", item.emoji)
            put("title", item.title)
            put("detail", item.detail)
            put("learned_date_label", item.learnedDate)
        }
        db.insertWithOnConflict("memories", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ── Publicaciones Sociales ───────────────────────────────

    fun getSocialPosts(): List<UserSocialPost> {
        val list = mutableListOf<UserSocialPost>()
        readableDatabase.rawQuery("SELECT id, author_name, author_age, category, time_ago_label, content, likes_count, comments_count, accent_color_hex FROM social_posts ORDER BY created_at DESC", null).use { cur ->
            while (cur.moveToNext()) {
                list.add(
                    UserSocialPost(
                        id = cur.getString(0),
                        author = cur.getString(1),
                        age = cur.getInt(2),
                        category = cur.getString(3),
                        timeAgo = cur.getString(4),
                        content = cur.getString(5),
                        likes = cur.getInt(6),
                        commentsCount = cur.getInt(7),
                        accentColorHex = cur.getLong(8)
                    )
                )
            }
        }
        return list
    }

    fun addSocialPost(post: UserSocialPost, userId: String) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("id", post.id.ifBlank { "post_${UUID.randomUUID().toString().take(8)}" })
            put("user_id", userId)
            put("author_name", post.author)
            put("author_age", post.age)
            put("category", post.category)
            put("time_ago_label", post.timeAgo)
            put("content", post.content)
            put("likes_count", post.likes)
            put("comments_count", post.commentsCount)
            put("accent_color_hex", post.accentColorHex)
        }
        db.insertWithOnConflict("social_posts", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteSocialPost(postId: String) {
        writableDatabase.delete("social_posts", "id = ?", arrayOf(postId))
    }

    // ── Ubicación del Robot ───────────────────────────────────

    fun getDeviceLocation(deviceId: String = "FIFO-S3-ESP32"): FifoDeviceLocation {
        val db = readableDatabase
        db.rawQuery("SELECT is_connected, last_connected_time, last_known_latitude, last_known_longitude, last_known_address, last_known_room, signal_strength_rssi, is_beeping FROM device_locations WHERE device_id = ?", arrayOf(deviceId)).use { cur ->
            if (cur.moveToFirst()) {
                return FifoDeviceLocation(
                    isConnected = cur.getInt(0) == 1,
                    lastConnectedTime = cur.getString(1) ?: "Desconectado",
                    lastKnownLatitude = cur.getDouble(2),
                    lastKnownLongitude = cur.getDouble(3),
                    lastKnownAddress = cur.getString(4) ?: "Sin registro de ubicación",
                    lastKnownRoom = cur.getString(5) ?: "Sin registrar",
                    signalStrengthRssi = cur.getInt(6),
                    isBeeping = cur.getInt(7) == 1
                )
            }
        }
        return FifoDeviceLocation()
    }

    fun updateDeviceLocation(deviceId: String = "FIFO-S3-ESP32", loc: FifoDeviceLocation, userId: String) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("device_id", deviceId)
            put("user_id", userId)
            put("is_connected", if (loc.isConnected) 1 else 0)
            put("last_connected_time", loc.lastConnectedTime)
            put("last_known_latitude", loc.lastKnownLatitude)
            put("last_known_longitude", loc.lastKnownLongitude)
            put("last_known_address", loc.lastKnownAddress)
            put("last_known_room", loc.lastKnownRoom)
            put("signal_strength_rssi", loc.signalStrengthRssi)
            put("is_beeping", if (loc.isBeeping) 1 else 0)
        }
        db.insertWithOnConflict("device_locations", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ── Conversaciones Pasadas ─────────────────────────────────

    fun getPastConversations(userId: String): List<PastConversationItem> {
        val list = mutableListOf<PastConversationItem>()
        readableDatabase.rawQuery(
            "SELECT id, title, date_label, duration_label, summary, topic_tag, icon_name FROM past_conversations WHERE user_id = ? ORDER BY recorded_at DESC",
            arrayOf(userId)
        ).use { cur ->
            while (cur.moveToNext()) {
                list.add(
                    PastConversationItem(
                        id = cur.getString(0),
                        title = cur.getString(1),
                        date = cur.getString(2) ?: "Hoy",
                        duration = cur.getString(3) ?: "5 min",
                        summary = cur.getString(4) ?: "",
                        tag = cur.getString(5) ?: "Conversación",
                        iconName = cur.getString(6) ?: "heart"
                    )
                )
            }
        }
        return list
    }

    fun addPastConversation(userId: String, item: PastConversationItem) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("id", item.id.ifBlank { "c_${UUID.randomUUID().toString().take(8)}" })
            put("user_id", userId)
            put("title", item.title)
            put("date_label", item.date)
            put("duration_label", item.duration)
            put("summary", item.summary)
            put("topic_tag", item.tag)
            put("icon_name", item.iconName)
        }
        db.insertWithOnConflict("past_conversations", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deletePastConversation(userId: String, id: String) {
        writableDatabase.delete("past_conversations", "user_id = ? AND id = ?", arrayOf(userId, id))
    }

    // ── Fragmentos de Conversaciones (Doble Capa) ───────────────

    fun getConversationFragments(userId: String): List<ConversationFragment> {
        val list = mutableListOf<ConversationFragment>()
        readableDatabase.rawQuery(
            "SELECT id, server_conversation_id, key_topics, named_entities, detected_mood, compact_summary, primary_tag, duration_seconds, recorded_at FROM conversation_fragments WHERE user_id = ? ORDER BY recorded_at DESC",
            arrayOf(userId)
        ).use { cur ->
            while (cur.moveToNext()) {
                val topicsJson = cur.getString(2) ?: "[]"
                val entitiesJson = cur.getString(3) ?: "[]"
                val topics: List<String> = try {
                    gson.fromJson(topicsJson, object : TypeToken<List<String>>() {}.type)
                } catch (e: Exception) {
                    emptyList()
                }
                val entities: List<String> = try {
                    gson.fromJson(entitiesJson, object : TypeToken<List<String>>() {}.type)
                } catch (e: Exception) {
                    emptyList()
                }
                list.add(
                    ConversationFragment(
                        id = cur.getString(0),
                        serverConversationId = cur.getString(1) ?: "",
                        keyTopics = topics,
                        namedEntities = entities,
                        detectedMood = cur.getString(4) ?: "neutral",
                        compactSummary = cur.getString(5) ?: "",
                        primaryTag = cur.getString(6) ?: "General",
                        durationSeconds = cur.getInt(7),
                        recordedAt = cur.getString(8) ?: ""
                    )
                )
            }
        }
        return list
    }

    fun addConversationFragment(userId: String, fragment: ConversationFragment) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("id", fragment.id.ifBlank { "frag_${UUID.randomUUID().toString().take(8)}" })
            put("user_id", userId)
            put("server_conversation_id", fragment.serverConversationId)
            put("key_topics", gson.toJson(fragment.keyTopics))
            put("named_entities", gson.toJson(fragment.namedEntities))
            put("detected_mood", fragment.detectedMood)
            put("compact_summary", fragment.compactSummary)
            put("primary_tag", fragment.primaryTag)
            put("duration_seconds", fragment.durationSeconds)
            if (fragment.recordedAt.isNotBlank()) put("recorded_at", fragment.recordedAt)
        }
        db.insertWithOnConflict("conversation_fragments", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ── Sincronización Remota (MySQL API -> SQLite) ───────────

    fun bulkUpsertSyncBundle(userId: String, bundle: com.fifo.voicepipeline.network.FifoBackendClient.SyncedUserBundle) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // 1. Perfil
            val p = bundle.userProfile
            val cvUser = ContentValues().apply {
                put("id", p.id)
                put("full_name", p.fullName)
                put("birth_date", p.birthDate)
                put("birth_year", p.birthYear)
                put("estimated_age", p.estimatedAge)
                put("gender_identity", p.genderIdentity)
                put("city", p.city)
            }
            db.insertWithOnConflict("users", null, cvUser, SQLiteDatabase.CONFLICT_REPLACE)

            val cvProfile = ContentValues().apply {
                put("user_id", p.id)
                put("bio_ai", p.bioAi)
                put("emergency_contact_name", p.emergencyContactName)
                put("emergency_contact_phone", p.emergencyContactPhone)
                put("preferred_address", p.preferredAddress)
                put("interest_tags", gson.toJson(p.interestTags))
            }
            db.insertWithOnConflict("user_profiles", null, cvProfile, SQLiteDatabase.CONFLICT_REPLACE)

            // 2. Gustos
            for (taste in bundle.tastes) {
                if (taste.isNotBlank()) {
                    tastes.addTaste(userId, taste)
                }
            }

            // 3. Historias de gustos
            for (story in bundle.tasteStories) {
                tastes.addTasteStory(userId, story)
            }

            // 4. Recordatorios
            for (rem in bundle.reminders) {
                addReminder(userId, rem)
            }

            // 5. Recuerdos
            for (mem in bundle.memories) {
                addMemory(userId, mem)
            }

            // 6. Posts sociales
            for (post in bundle.socialPosts) {
                addSocialPost(post, userId)
            }

            // 7. Conversaciones pasadas
            for (conv in bundle.pastConversations) {
                addPastConversation(userId, conv)
            }

            // 8. Fragmentos compactos
            for (frag in bundle.conversationFragments) {
                addConversationFragment(userId, frag)
            }

            // 9. Ubicación dispositivo
            bundle.deviceLocation?.let {
                updateDeviceLocation("FIFO-S3-ESP32", it, userId)
            }

            // 10. Contactos relacionales
            bundle.relationalContacts?.forEach { ctc ->
                addOrUpdateRelationalContact(userId, ctc)
            }

            // 11. Rutinas diarias
            bundle.dailyRoutines?.forEach { rtn ->
                addOrUpdateDailyRoutine(userId, rtn)
            }

            db.setTransactionSuccessful()
            Log.i(TAG, "Bundle remoto sincronizado exitosamente en SQLite local para $userId")
        } catch (e: Exception) {
            Log.e(TAG, "Error en bulkUpsertSyncBundle: ${e.message}", e)
        } finally {
            db.endTransaction()
        }
    }


}

