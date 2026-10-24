package com.fifo.voicepipeline.data.local

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.fifo.voicepipeline.network.FifoBackendClient.SyncedUserBundle

/**
 * Escritor local de los bundles que llegan del backend.
 *
 * Orquesta los DAOs existentes dentro de una sola transacción. No conoce
 * `Context`, HTTP ni la UI: solo recibe el paquete ya descargado y las
 * conexiones/DAOs que compone `FifoDatabaseHelper`.
 */
class SyncBundleWriter(
    private val writableDatabase: () -> SQLiteDatabase,
    private val userProfile: UserProfileDao,
    private val tastes: TasteDao,
    private val reminders: ReminderDao,
    private val memories: MemoryDao,
    private val socialPosts: SocialPostDao,
    private val pastConversations: PastConversationDao,
    private val conversationFragments: ConversationFragmentDao,
    private val deviceLocation: DeviceLocationDao,
    private val relationalContacts: RelationalContactDao,
    private val dailyRoutines: DailyRoutineDao
) {
    fun upsertBundle(userId: String, bundle: SyncedUserBundle) {
        val db = writableDatabase()
        db.beginTransaction()
        try {
            // 1. Perfil
            userProfile.upsertSyncedProfile(bundle.userProfile)

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
            for (reminder in bundle.reminders) {
                reminders.addReminder(userId, reminder)
            }

            // 5. Recuerdos
            for (memory in bundle.memories) {
                memories.addMemory(userId, memory)
            }

            // 6. Posts sociales
            for (post in bundle.socialPosts) {
                socialPosts.addSocialPost(post, userId)
            }

            // 7. Conversaciones pasadas
            for (conversation in bundle.pastConversations) {
                pastConversations.addPastConversation(userId, conversation)
            }

            // 8. Fragmentos compactos
            for (fragment in bundle.conversationFragments) {
                conversationFragments.addConversationFragment(userId, fragment)
            }

            // 9. Ubicación dispositivo
            bundle.deviceLocation?.let { location ->
                deviceLocation.updateDeviceLocation("FIFO-S3-ESP32", location, userId)
            }

            // 10. Contactos relacionales
            bundle.relationalContacts?.forEach { contact ->
                relationalContacts.addOrUpdateRelationalContact(userId, contact)
            }

            // 11. Rutinas diarias
            bundle.dailyRoutines?.forEach { routine ->
                dailyRoutines.addOrUpdateDailyRoutine(userId, routine)
            }

            db.setTransactionSuccessful()
            Log.i(TAG, "Bundle remoto sincronizado exitosamente en SQLite local para $userId")
        } catch (e: Exception) {
            Log.e(TAG, "Error en upsertBundle: ${e.message}", e)
        } finally {
            db.endTransaction()
        }
    }

    private companion object {
        const val TAG = "SyncBundleWriter"
    }
}
