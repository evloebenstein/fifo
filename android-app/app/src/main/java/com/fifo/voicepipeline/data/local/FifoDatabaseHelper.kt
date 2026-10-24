package com.fifo.voicepipeline.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.google.gson.Gson

/**
 * Base de datos relacional SQLite local de FIFO para Android.
 *
 * El DDL vive en [FifoSchema]; este helper solo compone y ejecuta:
 * - [FifoSchema.createStatements] al crear la base.
 * - [FifoSchema.upgradeStatements] al migrar desde una versión anterior.
 * - [FifoSchema.ensureStatements] en cada apertura, como reparación idempotente.
 *
 * El diario durable de turnos (`conversation_journal`) lo aporta
 * [ConversationJournalSchema], dueño separado de su propio esquema.
 *
 * Persistencia permanente de los datos del adulto mayor (100% offline) y
 * sincronizable con el backend MySQL contenerizado, salvo la procedencia local
 * del grafo relacional (`contact_appearances`).
 */
class FifoDatabaseHelper private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "FifoDatabaseHelper"
        private const val DATABASE_NAME = "fifo_local.db"
        private const val DATABASE_VERSION = FifoSchema.VERSION
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

    val contactAppearances = ContactAppearanceDao(
        readableDatabase = { readableDatabase },
        writableDatabase = { writableDatabase }
    )

    val contactPhoneHints = ContactPhoneHintDao(
        readableDatabase = { readableDatabase },
        writableDatabase = { writableDatabase }
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
        applyStatements(db, FifoSchema.createStatements)
        ConversationJournalSchema.create(db)
        seedInitialData()
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.w(TAG, "Actualizando base de datos local de v$oldVersion a v$newVersion")
        applyStatements(db, FifoSchema.upgradeStatements(oldVersion))
        if (oldVersion < ConversationJournalSchema.INTRODUCED_VERSION) {
            ConversationJournalSchema.create(db)
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        applyStatements(db, FifoSchema.ensureStatements)
        ConversationJournalSchema.create(db)
    }

    /**
     * Ejecuta una lista de sentencias en orden. Las marcadas como tolerantes
     * (`ALTER TABLE ... ADD COLUMN`) se omiten si la columna ya existe.
     */
    private fun applyStatements(db: SQLiteDatabase, statements: List<SchemaStatement>) {
        statements.forEach { statement ->
            if (statement.tolerant) {
                try {
                    db.execSQL(statement.sql)
                } catch (e: Exception) {
                    Log.w(TAG, "Sentencia de esquema tolerante omitida: ${e.message}")
                }
            } else {
                db.execSQL(statement.sql)
            }
        }
    }

    private fun seedInitialData() {
        // Base de datos limpia: los datos reales se crean dinámicamente en el onboarding o se sincronizan desde el backend.
    }
}
