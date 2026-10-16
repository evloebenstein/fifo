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
 *           reminders_and_events, past_conversations, memories, device_locations.
 * - Persistencia permanente de los datos del adulto mayor (100% offline).
 * - Sincronizable con el backend MySQL contenerizado.
 */
class FifoDatabaseHelper private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "FifoDatabaseHelper"
        private const val DATABASE_NAME = "fifo_local.db"
        private const val DATABASE_VERSION = 6
        private val gson = Gson()

        @Volatile
        private var instance: FifoDatabaseHelper? = null

        fun getInstance(context: Context): FifoDatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: FifoDatabaseHelper(context).also { instance = it }
            }
        }
    }

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


    // ══════════════════════════════════════════════════════════
    //  LECTURAS Y ESCRITURAS DE USUARIO
    // ══════════════════════════════════════════════════════════

    fun getUserProfile(userId: String): UserProfileData {
        val db = readableDatabase
        val sql = """
            SELECT u.id, u.full_name, u.username, u.birth_date, u.birth_year, u.estimated_age,
                   u.gender_identity, u.city, p.bio_ai, p.emergency_contact_name, p.emergency_contact_phone,
                   p.preferred_address, p.interest_tags
            FROM users u
            LEFT JOIN user_profiles p ON p.user_id = u.id
            WHERE u.id = ?
        """.trimIndent()

        try {
            db.rawQuery(sql, arrayOf(userId)).use { cursor ->
                if (cursor.moveToFirst()) {
                    val rawHandle = cursor.getString(2) ?: ""
                    val prefAddr = if (cursor.columnCount > 11) cursor.getString(11) ?: "" else ""
                    val rawTags = if (cursor.columnCount > 12) cursor.getString(12) else null
                    val interestTags = parseStringList(rawTags)
                    return UserProfileData(
                        id = cursor.getString(0) ?: userId,
                        fullName = cursor.getString(1) ?: "",
                        username = rawHandle.removePrefix("@"),
                        birthDate = cursor.getString(3) ?: "",
                        birthYear = cursor.getInt(4).takeIf { it > 0 } ?: 0,
                        estimatedAge = cursor.getInt(5).takeIf { it > 0 } ?: 0,
                        genderIdentity = cursor.getString(6) ?: "",
                        city = cursor.getString(7) ?: "",
                        bioAi = cursor.getString(8) ?: "",
                        emergencyContactName = cursor.getString(9) ?: "",
                        emergencyContactPhone = cursor.getString(10) ?: "",
                        preferredAddress = prefAddr,
                        interestTags = interestTags
                    )
                }
            }
        } catch (_: Exception) {
            // Fallback si la consulta extendida falla
        }
        return UserProfileData()
    }

    /**
     * Busca el ID de un usuario local en SQLite según su ID, username (@ID), email o nombre completo.
     */
    fun findUserIdByIdentifier(identifier: String): String? {
        val clean = identifier.trim().removePrefix("@")
        if (clean.isBlank()) return null
        val db = readableDatabase
        val sql = """
            SELECT id FROM users 
            WHERE LOWER(id) = LOWER(?) 
               OR LOWER(username) = LOWER(?) 
               OR LOWER(email) = LOWER(?) 
               OR LOWER(full_name) = LOWER(?)
               OR LOWER(full_name) LIKE LOWER(?)
            LIMIT 1
        """.trimIndent()
        try {
            db.rawQuery(sql, arrayOf(clean, clean, clean, clean, "%$clean%")).use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(0)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("FifoDatabaseHelper", "Error buscando usuario por identificador: ${e.message}")
        }
        return null
    }

    fun updateUserProfileNames(userId: String, fullName: String, username: String) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("full_name", fullName)
            put("username", username.removePrefix("@"))
        }
        db.update("users", cv, "id = ?", arrayOf(userId))
    }

    /**
     * Verifica si un username (@ID) ya está registrado en la base de datos local.
     */
    fun isUsernameTaken(username: String): Boolean {
        val clean = username.trim().removePrefix("@").lowercase()
        if (clean.isBlank()) return false
        val db = readableDatabase
        return try {
            db.rawQuery("SELECT 1 FROM users WHERE LOWER(username) = ? LIMIT 1", arrayOf(clean)).use { cursor ->
                cursor.moveToFirst()
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Recomienda una lista de IDs únicos disponibles comparando el nombre real, apellido
     * y tokens de correo contra los usuarios ya registrados en la base de datos local.
     * Genera sugerencias diversas, naturales y verifica que no contengan términos ofensivos o reservados.
     * Soporta diferentes lotes (batchIndex) para permitir que el usuario explore nuevas opciones.
     */
    fun recommendAvailableUsernames(fullName: String, email: String? = null, batchIndex: Int = 0): List<String> {
        val cleanName = java.text.Normalizer.normalize(fullName.trim(), java.text.Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .lowercase(java.util.Locale.ROOT)
        val nameTokens = cleanName.split(Regex("[\\s._-]+")).filter { it.isNotBlank() && it.length > 1 }
        val first = nameTokens.firstOrNull()?.filter { it.isLetterOrDigit() }?.take(18) ?: ""
        val lastTokens = nameTokens.drop(1).map { it.filter { ch -> ch.isLetterOrDigit() } }.filter { it.isNotBlank() }
        val last = lastTokens.firstOrNull()?.take(18) ?: ""

        // Extraer prefijo y tokens del correo
        val emailPrefix = email?.substringBefore("@")?.lowercase(java.util.Locale.ROOT)?.filter { it.isLetterOrDigit() || it == '.' || it == '_' } ?: ""
        val emailCleanAlpha = emailPrefix.filter { it.isLetterOrDigit() }
        val emailTokens = emailPrefix.split(Regex("[._]+")).filter { it.isNotBlank() && it.length > 1 }

        // Token secundario (apellido real, o segundo token del correo)
        val secondaryToken = when {
            last.isNotBlank() -> last
            emailTokens.size > 1 -> {
                val candidateToken = emailTokens.firstOrNull { eToken ->
                    !first.contains(eToken) && !eToken.contains(first)
                } ?: ""
                candidateToken.take(16)
            }
            else -> ""
        }

        val baseName = if (first.isNotBlank() && !first.equals("usuario", ignoreCase = true)) {
            first
        } else if (emailTokens.isNotEmpty()) {
            emailTokens.first().take(18)
        } else {
            "amigo"
        }

        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) % 100 // ej. 26

        // 5 BUCKETS ESTILÍSTICOS INDEPENDIENTES (Garantizan máxima diversidad estética sin repetición)

        // Bucket 1: Clásico / Canónico Completo
        val bucketCanonical = mutableListOf<String>()
        if (secondaryToken.isNotBlank()) {
            bucketCanonical.add("${baseName}_${secondaryToken}")
            bucketCanonical.add("${baseName}.${secondaryToken}")
            if (baseName.length + secondaryToken.length <= 18) {
                bucketCanonical.add("${baseName}${secondaryToken}")
            }
        } else {
            bucketCanonical.add(baseName)
            bucketCanonical.add("${baseName}_cl")
            bucketCanonical.add("${baseName}_dev")
        }

        // Bucket 2: Minimalista / Inicial Compacta
        val bucketCompact = mutableListOf<String>()
        if (secondaryToken.isNotBlank()) {
            bucketCompact.add("${baseName}_${secondaryToken.take(1)}")
            bucketCompact.add("${baseName.take(1)}_${secondaryToken}")
            bucketCompact.add("${baseName}.${secondaryToken.take(1)}")
            bucketCompact.add("${baseName.take(1)}${secondaryToken}")
        } else {
            bucketCompact.add("${baseName}.ok")
            bucketCompact.add("${baseName}.me")
            bucketCompact.add("the_${baseName}")
        }

        // Bucket 3: Conversacional & Cercano ("Soy", "Hola", "Es")
        val bucketConversational = mutableListOf<String>()
        bucketConversational.add("soy${baseName}")
        bucketConversational.add("hola_${baseName}")
        bucketConversational.add("soy_${baseName}")
        bucketConversational.add("es_${baseName}")

        // Bucket 4: Moniker Personal / Derivado de Correo
        val bucketMoniker = mutableListOf<String>()
        if (emailCleanAlpha.isNotBlank() && emailCleanAlpha != baseName && emailCleanAlpha.length in 3..18) {
            bucketMoniker.add(emailCleanAlpha)
        }
        bucketMoniker.add("${baseName}_real")
        bucketMoniker.add("${baseName}.now")
        bucketMoniker.add("${baseName}.hub")
        bucketMoniker.add("iam${baseName}")

        // Bucket 5: Insignia Temporal / Badge Moderno
        val bucketBadge = mutableListOf<String>()
        bucketBadge.add("${baseName}${currentYear}")
        bucketBadge.add("${baseName}.club")
        bucketBadge.add("${baseName}_vip")
        bucketBadge.add("${baseName}24")
        bucketBadge.add("${baseName}_99")

        val buckets = listOf(bucketCanonical, bucketCompact, bucketConversational, bucketMoniker, bucketBadge)
        val selected = mutableListOf<String>()

        // Seleccionar 1 de cada bucket según el batchIndex, rotando si es necesario
        for (i in buckets.indices) {
            val bucket = buckets[i]
            for (step in 0 until bucket.size) {
                val candidateIdx = (batchIndex + step) % bucket.size
                val rawCand = bucket[candidateIdx]
                val cand = rawCand.filter { it.isLetterOrDigit() || it == '_' || it == '.' }.lowercase(java.util.Locale.ROOT)
                if (cand.length in 3..20 &&
                    !isUsernameTaken(cand) &&
                    cand !in selected &&
                    com.fifo.voicepipeline.data.FifoContentFilter.validateUsername(cand).isValid
                ) {
                    selected.add(cand)
                    break
                }
            }
        }

        // Si algún bucket quedó vacío (por duplicado o disponibilidad), rellenar con candidatos limpios no usados
        val fallbackPool = mutableListOf<String>()
        buckets.flatten().forEach { raw ->
            val cand = raw.filter { it.isLetterOrDigit() || it == '_' || it == '.' }.lowercase(java.util.Locale.ROOT)
            if (cand !in selected && cand.length in 3..20 && !isUsernameTaken(cand) && com.fifo.voicepipeline.data.FifoContentFilter.validateUsername(cand).isValid) {
                fallbackPool.add(cand)
            }
        }
        var fIdx = 0
        while (selected.size < 5 && fIdx < fallbackPool.size) {
            val cand = fallbackPool[fIdx]
            if (cand !in selected) {
                selected.add(cand)
            }
            fIdx++
        }

        // Si aún así no llegamos a 5, generar sufijos únicos con saltos no repetitivos
        val saltSuffixes = listOf("ok", currentYear.toString(), "real", "pro", "cl", "top", "plus", "7", "x")
        var sIdx = 0
        while (selected.size < 5 && sIdx < saltSuffixes.size) {
            val suf = saltSuffixes[(batchIndex + sIdx) % saltSuffixes.size]
            val cand = if (baseName.length + suf.length + 1 <= 20) "${baseName}_$suf" else "${baseName.take(20 - suf.length)}$suf"
            if (!isUsernameTaken(cand) && cand !in selected && com.fifo.voicepipeline.data.FifoContentFilter.validateUsername(cand).isValid) {
                selected.add(cand)
            }
            sIdx++
        }

        return selected.take(5).map { "@$it" }
    }

    fun updateDemographics(
        userId: String,
        fullName: String? = null,
        username: String? = null,
        birthDate: String? = null,
        birthYear: Int? = null,
        estimatedAge: Int? = null,
        gender: String? = null,
        city: String? = null
    ) {
        val db = writableDatabase
        val cv = ContentValues()
        fullName?.let { cv.put("full_name", it) }
        username?.let { cv.put("username", it.removePrefix("@")) }
        birthDate?.let { cv.put("birth_date", it) }
        birthYear?.let { cv.put("birth_year", it) }
        estimatedAge?.let { cv.put("estimated_age", it) }
        gender?.let { cv.put("gender_identity", it) }
        city?.let { cv.put("city", it) }

        if (cv.size() > 0) {
            db.update("users", cv, "id = ?", arrayOf(userId))
        }
    }

    fun updateBio(userId: String, newBio: String) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("bio_ai", newBio)
        }
        db.update("user_profiles", cv, "user_id = ?", arrayOf(userId))
    }

    fun updateInterestTags(userId: String, tags: List<String>) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("interest_tags", gson.toJson(tags))
        }
        db.update("user_profiles", cv, "user_id = ?", arrayOf(userId))
    }

    // ── Gustos ───────────────────────────────────────────────

    fun getTastes(userId: String): List<String> {
        val list = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT name FROM tastes WHERE user_id = ? AND is_active = 1 ORDER BY learned_at ASC", arrayOf(userId)).use { cur ->
            while (cur.moveToNext()) {
                list.add(cur.getString(0))
            }
        }
        return list
    }

    fun addTaste(userId: String, name: String, category: String = "general"): Boolean {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("id", "t_${UUID.randomUUID().toString().take(8)}")
            put("user_id", userId)
            put("name", name.trim())
            put("category", category)
            put("is_active", 1)
        }
        return db.insertWithOnConflict("tastes", null, cv, SQLiteDatabase.CONFLICT_REPLACE) != -1L
    }

    fun removeTaste(userId: String, name: String): Boolean {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("is_active", 0)
        }
        return db.update("tastes", cv, "user_id = ? AND name = ?", arrayOf(userId, name.trim())) > 0
    }

    // ── Historias de Gustos ───────────────────────────────────

    fun getTasteStories(userId: String): List<FifoTasteStory> {
        val list = mutableListOf<FifoTasteStory>()
        readableDatabase.rawQuery("SELECT id, title, subtitle, description, tags, icon_category, learned_from FROM taste_stories WHERE user_id = ? ORDER BY created_at DESC", arrayOf(userId)).use { cur ->
            while (cur.moveToNext()) {
                val tagsJson = cur.getString(4) ?: "[]"
                val tags: List<String> = try {
                    gson.fromJson(tagsJson, object : TypeToken<List<String>>() {}.type)
                } catch (e: Exception) {
                    emptyList()
                }
                list.add(
                    FifoTasteStory(
                        id = cur.getString(0),
                        title = cur.getString(1),
                        subtitle = cur.getString(2),
                        description = cur.getString(3),
                        tags = tags,
                        iconCategory = cur.getString(5) ?: "heart",
                        learnedFrom = cur.getString(6) ?: ""
                    )
                )
            }
        }
        return list
    }

    fun addTasteStory(userId: String, story: FifoTasteStory) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("id", story.id.ifBlank { "story_${UUID.randomUUID().toString().take(8)}" })
            put("user_id", userId)
            put("title", story.title)
            put("subtitle", story.subtitle)
            put("description", story.description)
            put("tags", gson.toJson(story.tags))
            put("icon_category", story.iconCategory)
            put("learned_from", story.learnedFrom)
        }
        db.insertWithOnConflict("taste_stories", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteTasteStory(userId: String, storyId: String) {
        writableDatabase.delete("taste_stories", "user_id = ? AND id = ?", arrayOf(userId, storyId))
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
                    addTaste(userId, taste)
                }
            }

            // 3. Historias de gustos
            for (story in bundle.tasteStories) {
                addTasteStory(userId, story)
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

    // ═════════════════════════════════════════════════════════════════════
    //  ENCUENTROS Y ACTIVIDADES COMUNITARIAS
    // ═════════════════════════════════════════════════════════════════════

    fun getCommunityActivities(userId: String): List<CommunityActivityItem> {
        val list = mutableListOf<CommunityActivityItem>()
        val db = readableDatabase
        val cursor: Cursor? = db.rawQuery(
            """
            SELECT a.id, a.title, a.description, a.spots_label, a.spots_max, a.spots_taken,
                   a.date_label, a.location_name, a.distance_label, a.icon_category,
                   a.organizer_id, a.organizer_name, a.organizer_type, a.target_tastes,
                   a.created_at,
                   (SELECT COUNT(*) FROM activity_participants ap WHERE ap.activity_id = a.id AND ap.user_id = ? AND ap.status = 'joined') as is_user_joined,
                   (SELECT COUNT(*) FROM activity_participants ap WHERE ap.activity_id = a.id AND ap.status = 'joined') as real_joined_count
            FROM community_activities a
            ORDER BY a.created_at DESC
            """.trimIndent(),
            arrayOf(userId)
        )
        cursor?.use { c ->
            while (c.moveToNext()) {
                val tastesJson = c.getString(13) ?: "[]"
                val tastesList: List<String> = try {
                    gson.fromJson(tastesJson, object : TypeToken<List<String>>() {}.type) ?: emptyList()
                } catch (e: Exception) {
                    emptyList()
                }
                val realJoined = c.getInt(16)
                val baseSpotsTaken = c.getInt(5)
                val totalTaken = if (realJoined > baseSpotsTaken) realJoined else baseSpotsTaken
                val maxSpots = c.getInt(4).takeIf { it > 0 } ?: 15
                val remaining = (maxSpots - totalTaken).coerceAtLeast(0)

                list.add(
                    CommunityActivityItem(
                        id = c.getString(0),
                        title = c.getString(1),
                        description = c.getString(2) ?: "",
                        spotsLabel = if (remaining > 0) "Quedan $remaining cupos" else "Cupos completos",
                        spotsMax = maxSpots,
                        spotsTaken = totalTaken,
                        dateLabel = c.getString(6),
                        locationName = c.getString(7),
                        distanceLabel = c.getString(8) ?: "Cerca de ti",
                        iconCategory = c.getString(9) ?: "book",
                        organizerId = c.getString(10) ?: "",
                        organizerName = c.getString(11) ?: "Fifo",
                        organizerType = c.getString(12) ?: "fifo",
                        targetTastes = tastesList,
                        isJoined = c.getInt(15) > 0,
                        createdAt = c.getString(14) ?: ""
                    )
                )
            }
        }
        return list
    }

    fun addCommunityActivity(item: CommunityActivityItem): Boolean {
        val cv = ContentValues().apply {
            put("id", item.id)
            put("title", item.title)
            put("description", item.description)
            put("spots_label", item.spotsLabel)
            put("spots_max", item.spotsMax)
            put("spots_taken", item.spotsTaken)
            put("date_label", item.dateLabel)
            put("location_name", item.locationName)
            put("distance_label", item.distanceLabel)
            put("icon_category", item.iconCategory)
            put("organizer_id", item.organizerId)
            put("organizer_name", item.organizerName)
            put("organizer_type", item.organizerType)
            put("target_tastes", gson.toJson(item.targetTastes))
            if (item.createdAt.isNotBlank()) {
                put("created_at", item.createdAt)
            }
        }
        val result = writableDatabase.insertWithOnConflict(
            "community_activities",
            null,
            cv,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        return result != -1L
    }

    fun joinCommunityActivity(activityId: String, userId: String): Boolean {
        val cv = ContentValues().apply {
            put("activity_id", activityId)
            put("user_id", userId)
            put("status", "joined")
        }
        val res = writableDatabase.insertWithOnConflict(
            "activity_participants",
            null,
            cv,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        if (res != -1L) {
            writableDatabase.execSQL(
                "UPDATE community_activities SET spots_taken = spots_taken + 1 WHERE id = ?",
                arrayOf(activityId)
            )
            return true
        }
        return false
    }

    fun leaveCommunityActivity(activityId: String, userId: String): Boolean {
        val deleted = writableDatabase.delete(
            "activity_participants",
            "activity_id = ? AND user_id = ?",
            arrayOf(activityId, userId)
        )
        if (deleted > 0) {
            writableDatabase.execSQL(
                "UPDATE community_activities SET spots_taken = MAX(0, spots_taken - 1) WHERE id = ?",
                arrayOf(activityId)
            )
            return true
        }
        return false
    }

    fun deleteCommunityActivity(activityId: String): Boolean {
        writableDatabase.delete("activity_participants", "activity_id = ?", arrayOf(activityId))
        val rows = writableDatabase.delete("community_activities", "id = ?", arrayOf(activityId))
        return rows > 0
    }

    fun seedDefaultCommunityActivitiesIfEmpty(userId: String) {
        val count = try {
            val cursor = readableDatabase.rawQuery("SELECT COUNT(*) FROM community_activities", null)
            cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
        } catch (e: Exception) {
            0
        }
        if (count == 0) {
            val initial = listOf(
                com.fifo.voicepipeline.ui.model.CommunityActivityItem(
                    id = "act_fifo_01",
                    title = "Taller de Pintura y Acuarela Botánica",
                    description = "Un espacio relajado para pintar flores y paisajes en acuarela. Fifo proveerá los materiales y una guía paso a paso para todos los niveles.",
                    spotsLabel = "Quedan 8 cupos",
                    spotsMax = 12,
                    spotsTaken = 4,
                    dateLabel = "Jueves 16 oct · 16:30",
                    locationName = "Centro Comunitario El Roble, Sala 3",
                    distanceLabel = "A 450 m",
                    iconCategory = "palette",
                    organizerId = "fifo_official",
                    organizerName = "Fifo",
                    organizerType = "fifo",
                    targetTastes = listOf("Pintura y arte", "Jardinería y plantas", "Manualidades"),
                    isJoined = false
                ),
                com.fifo.voicepipeline.ui.model.CommunityActivityItem(
                    id = "act_comm_02",
                    title = "Intercambio de Recetas y Dulces Caseros",
                    description = "Nos reuniremos a compartir secretos culinarios tradicionales: masas, mermeladas y postres de antaño con una taza de té caliente.",
                    spotsLabel = "Quedan 8 cupos",
                    spotsMax = 15,
                    spotsTaken = 7,
                    dateLabel = "Sábado 18 oct · 17:00",
                    locationName = "Café & Taller La Casona",
                    distanceLabel = "A 1.1 km",
                    iconCategory = "coffee",
                    organizerId = "org_casona",
                    organizerName = "Club Gastronómico Vecinal",
                    organizerType = "community_group",
                    targetTastes = listOf("Cocina tradicional", "Historias de vida", "Café y tertulias"),
                    isJoined = false
                ),
                com.fifo.voicepipeline.ui.model.CommunityActivityItem(
                    id = "act_user_03",
                    title = "Caminata y Tertulia en el Parque Botánico",
                    description = "Caminata suave de 30 minutos a paso conversado por los senderos arbolados, terminando con una charla bajo los sauces.",
                    spotsLabel = "Quedan 5 cupos",
                    spotsMax = 8,
                    spotsTaken = 3,
                    dateLabel = "Domingo 19 oct · 10:30",
                    locationName = "Acceso Principal Parque Botánico",
                    distanceLabel = "A 600 m",
                    iconCategory = "nature",
                    organizerId = "usr_carmen_g",
                    organizerName = "Carmen González",
                    organizerType = "user",
                    targetTastes = listOf("Paseos al aire libre", "Naturaleza y botánica", "Fotografía"),
                    isJoined = false
                ),
                com.fifo.voicepipeline.ui.model.CommunityActivityItem(
                    id = "act_comp_04",
                    title = "Tarde de Boleros y Música Clásica en Vivo",
                    description = "Ensamble de cuerdas en vivo interpretando clásicos de Lucho Gatica, Violeta Parra y melodías sinfónicas al atardecer.",
                    spotsLabel = "Quedan 9 cupos",
                    spotsMax = 20,
                    spotsTaken = 11,
                    dateLabel = "Miércoles 22 oct · 18:00",
                    locationName = "Teatro Municipal de Ñuñoa",
                    distanceLabel = "A 1.8 km",
                    iconCategory = "music",
                    organizerId = "org_teatro_mun",
                    organizerName = "Corporación Cultural Municipal",
                    organizerType = "company",
                    targetTastes = listOf("Música clásica", "Boleros y tango", "Teatro y cultura"),
                    isJoined = false
                )
            )
            initial.forEach { addCommunityActivity(it) }
        }
    }
}

