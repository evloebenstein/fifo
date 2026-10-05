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
        private const val DATABASE_VERSION = 1
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

        // Sembrar datos iniciales (Lucía González)
        seedInitialData(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.w(TAG, "Actualizando base de datos local de v$oldVersion a v$newVersion")
    }

    private fun seedInitialData(db: SQLiteDatabase) {
        val userId = "usr_lucia_01"

        // Usuario
        db.execSQL("""
            INSERT OR REPLACE INTO users (id, full_name, email, birth_date, birth_year, estimated_age, gender_identity, city, short_quote)
            VALUES ('$userId', 'Lucía González', 'lucia.gonzalez@fifo.cl', '14 de Mayo, 1958', 1958, 68, 'Mujer', 'Santiago, Chile', 'Disfrutando de las pequeñas alegrías de cada día con mi robot Fifo.')
        """.trimIndent())

        // Perfil
        db.execSQL("""
            INSERT OR REPLACE INTO user_profiles (user_id, bio_ai, emergency_contact_name, emergency_contact_phone, preferred_address, continuous_listening_enabled)
            VALUES ('$userId', 'Amante de las novelas de historia, la música clásica de piano y las mañanas tranquilas con café. Disfruta compartir con Fifo anécdotas de su familia, preparar recetas caseras y cuidar las orquídeas de su jardín.', 'Carmen (Hija)', '+56987654321', 'Av. Providencia 1234, Depto 402, Santiago', 0)
        """.trimIndent())

        // Gustos
        val tastes = listOf(
            Triple("t_01", "Lectura histórica", "lectura"),
            Triple("t_02", "Música clásica", "musica"),
            Triple("t_03", "Paseos en el parque", "bienestar"),
            Triple("t_04", "Jardinería", "naturaleza"),
            Triple("t_05", "Cocina tradicional", "cocina")
        )
        for ((id, name, cat) in tastes) {
            db.execSQL("INSERT OR IGNORE INTO tastes (id, user_id, name, category, is_active) VALUES ('$id', '$userId', '$name', '$cat', 1)")
        }

        // Recordatorios
        val reminders = listOf(
            Triple("rem_1", "Tomar pastilla de la presión (Enalapril)", "20:00"),
            Triple("rem_2", "Llamar a Carmen (Hija)", "17:30"),
            Triple("rem_3", "Paseo de 15 minutos por la plaza", "11:00")
        )
        for ((id, title, time) in reminders) {
            val completed = if (id == "rem_3") 1 else 0
            val spoken = "Lucía, es hora de su recordatorio: $title"
            db.execSQL("INSERT OR IGNORE INTO reminders_and_events (id, user_id, title, time_str, is_completed, spoken_text) VALUES ('$id', '$userId', '$title', '$time', $completed, '$spoken')")
        }

        // Historias de gustos
        insertSeedStory(db, userId, "t_bday", "Cumpleaños y orígenes familiares", "Nacida el 14 de Mayo de 1958 en Santiago",
            "Lucía nació en una fresca mañana de otoño en 1958 en el barrio tradicional de Santiago. Siempre recuerda cómo en su familia celebraban con torta de milhojas casera y música de fondo.",
            listOf("Cumpleaños", "Familia", "Santiago"), "celebration", "Charla inicial con Fifo")

        insertSeedStory(db, userId, "t_gardening", "Cuidado y amor por las orquídeas", "Riego por inmersión y luz filtrada de mañana",
            "En sus charlas con Fifo, Lucía contó que tiene 4 maceteros en su balcón. Disfruta regarlas los miércoles y limpiar con cuidado sus hojas verdes con un paño húmedo.",
            listOf("Jardinería", "Plantas", "Paciencia"), "nature", "Charla matutina del 2 de Octubre")

        insertSeedStory(db, userId, "t_music", "Conciertos de piano y música clásica", "Chopin y las tardes tranquilas de otoño",
            "La música clásica le transmite una paz inmensa. Las melodías de piano de la época romántica son sus predilectas para recordar su juventud.",
            listOf("Música", "Relajo", "Piano"), "music", "Conversación sobre melodías relajantes")

        // Recuerdos
        db.execSQL("INSERT OR IGNORE INTO memories (id, user_id, emoji, title, detail, learned_date_label) VALUES ('m1', '$userId', '🌺', 'Flor favorita: Orquídeas blancas', 'Le recuerdan el patio iluminado de su infancia en el sur.', 'Aprendido hace 2 días')")
        db.execSQL("INSERT OR IGNORE INTO memories (id, user_id, emoji, title, detail, learned_date_label) VALUES ('m2', '$userId', '☕', 'Costumbre de café suave a las 11:00', 'Siempre con poca azúcar y una galleta de avena.', 'Aprendido ayer')")
        db.execSQL("INSERT OR IGNORE INTO memories (id, user_id, emoji, title, detail, learned_date_label) VALUES ('m3', '$userId', '📻', 'Radio Beethoven por las tardes', 'Le acompaña a descansar mientras teje o lee.', 'Aprendido hoy')")

        // Posts Sociales
        db.execSQL("INSERT OR IGNORE INTO social_posts (id, user_id, author_name, author_age, category, content, time_ago_label, likes_count, comments_count, accent_color_hex) VALUES ('post_1', '$userId', 'Lucía', 68, 'Jardinería', '¡Qué felicidad! Hoy abrió la primera flor de mi orquídea blanca. Fifo me recordó regarla ayer.', 'Hoy, 10:30', 14, 4, 4279318913)")
        db.execSQL("INSERT OR IGNORE INTO social_posts (id, user_id, author_name, author_age, category, content, time_ago_label, likes_count, comments_count, accent_color_hex) VALUES ('post_2', '$userId', 'Lucía', 68, 'Música', 'Escuchando los nocturnos de Chopin mientras me tomo un té tibio. Momentos de calma.', 'Ayer', 19, 6, 4287323382)")

        // Dispositivo
        db.execSQL("INSERT OR IGNORE INTO device_locations (device_id, user_id, is_connected, last_connected_time, last_known_latitude, last_known_longitude, last_known_address, last_known_room) VALUES ('FIFO-S3-ESP32', '$userId', 0, 'Desconectado', -33.4255, -70.6143, 'Av. Providencia 1234, Santiago', 'Cerca del Living / Mesa de noche')")
    }

    private fun insertSeedStory(db: SQLiteDatabase, userId: String, id: String, title: String, subtitle: String, desc: String, tags: List<String>, icon: String, source: String) {
        val cv = ContentValues().apply {
            put("id", id)
            put("user_id", userId)
            put("title", title)
            put("subtitle", subtitle)
            put("description", desc)
            put("tags", gson.toJson(tags))
            put("icon_category", icon)
            put("learned_from", source)
        }
        db.insertWithOnConflict("taste_stories", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    // ══════════════════════════════════════════════════════════
    //  LECTURAS Y ESCRITURAS DE USUARIO
    // ══════════════════════════════════════════════════════════

    fun getUserProfile(userId: String = "usr_lucia_01"): UserProfileData {
        val db = readableDatabase
        val sql = """
            SELECT u.id, u.full_name, u.birth_date, u.birth_year, u.estimated_age,
                   u.gender_identity, u.city, p.bio_ai, p.emergency_contact_name, p.emergency_contact_phone
            FROM users u
            LEFT JOIN user_profiles p ON p.user_id = u.id
            WHERE u.id = ?
        """.trimIndent()

        db.rawQuery(sql, arrayOf(userId)).use { cursor ->
            if (cursor.moveToFirst()) {
                return UserProfileData(
                    id = cursor.getString(0) ?: userId,
                    fullName = cursor.getString(1) ?: "Lucía González",
                    birthDate = cursor.getString(2) ?: "14 de Mayo, 1958",
                    birthYear = cursor.getInt(3).takeIf { it > 0 } ?: 1958,
                    estimatedAge = cursor.getInt(4).takeIf { it > 0 } ?: 68,
                    genderIdentity = cursor.getString(5) ?: "Mujer",
                    city = cursor.getString(6) ?: "Santiago, Chile",
                    bioAi = cursor.getString(7) ?: "",
                    emergencyContactName = cursor.getString(8) ?: "Carmen (Hija)",
                    emergencyContactPhone = cursor.getString(9) ?: "+56987654321"
                )
            }
        }
        return UserProfileData()
    }

    fun updateDemographics(
        userId: String = "usr_lucia_01",
        fullName: String? = null,
        birthDate: String? = null,
        birthYear: Int? = null,
        estimatedAge: Int? = null,
        gender: String? = null,
        city: String? = null
    ) {
        val db = writableDatabase
        val cv = ContentValues()
        fullName?.let { cv.put("full_name", it) }
        birthDate?.let { cv.put("birth_date", it) }
        birthYear?.let { cv.put("birth_year", it) }
        estimatedAge?.let { cv.put("estimated_age", it) }
        gender?.let { cv.put("gender_identity", it) }
        city?.let { cv.put("city", it) }

        if (cv.size() > 0) {
            db.update("users", cv, "id = ?", arrayOf(userId))
        }
    }

    fun updateBio(userId: String = "usr_lucia_01", newBio: String) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("bio_ai", newBio)
        }
        db.update("user_profiles", cv, "user_id = ?", arrayOf(userId))
    }

    // ── Gustos ───────────────────────────────────────────────

    fun getTastes(userId: String = "usr_lucia_01"): List<String> {
        val list = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT name FROM tastes WHERE user_id = ? AND is_active = 1 ORDER BY learned_at ASC", arrayOf(userId)).use { cur ->
            while (cur.moveToNext()) {
                list.add(cur.getString(0))
            }
        }
        return list
    }

    fun addTaste(userId: String = "usr_lucia_01", name: String, category: String = "general"): Boolean {
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

    fun removeTaste(userId: String = "usr_lucia_01", name: String): Boolean {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("is_active", 0)
        }
        return db.update("tastes", cv, "user_id = ? AND name = ?", arrayOf(userId, name.trim())) > 0
    }

    // ── Historias de Gustos ───────────────────────────────────

    fun getTasteStories(userId: String = "usr_lucia_01"): List<FifoTasteStory> {
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

    fun addTasteStory(userId: String = "usr_lucia_01", story: FifoTasteStory) {
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

    // ── Recordatorios ────────────────────────────────────────

    fun getReminders(userId: String = "usr_lucia_01"): List<FifoReminderItem> {
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

    fun addReminder(userId: String = "usr_lucia_01", reminder: FifoReminderItem) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("id", reminder.id.ifBlank { "rem_${UUID.randomUUID().toString().take(8)}" })
            put("user_id", userId)
            put("title", reminder.title)
            put("time_str", reminder.timeStr)
            put("category", reminder.category)
            put("is_completed", if (reminder.isCompleted) 1 else 0)
            put("spoken_text", "Lucía, es hora de su recordatorio: ${reminder.title}")
        }
        db.insertWithOnConflict("reminders_and_events", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun completeReminder(userId: String = "usr_lucia_01", reminderId: String, completed: Boolean = true) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("is_completed", if (completed) 1 else 0)
        }
        db.update("reminders_and_events", cv, "user_id = ? AND id = ?", arrayOf(userId, reminderId))
    }

    fun deleteReminder(userId: String = "usr_lucia_01", reminderId: String) {
        writableDatabase.delete("reminders_and_events", "user_id = ? AND id = ?", arrayOf(userId, reminderId))
    }

    // ── Recuerdos ────────────────────────────────────────────

    fun getMemories(userId: String = "usr_lucia_01"): List<FifoMemoryItem> {
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

    fun addMemory(userId: String = "usr_lucia_01", item: FifoMemoryItem) {
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

    fun addSocialPost(post: UserSocialPost, userId: String = "usr_lucia_01") {
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
                    lastKnownAddress = cur.getString(4) ?: "Av. Providencia 1234, Santiago",
                    lastKnownRoom = cur.getString(5) ?: "Cerca del Living / Mesa de noche",
                    signalStrengthRssi = cur.getInt(6),
                    isBeeping = cur.getInt(7) == 1
                )
            }
        }
        return FifoDeviceLocation()
    }

    fun updateDeviceLocation(deviceId: String = "FIFO-S3-ESP32", loc: FifoDeviceLocation, userId: String = "usr_lucia_01") {
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
}
