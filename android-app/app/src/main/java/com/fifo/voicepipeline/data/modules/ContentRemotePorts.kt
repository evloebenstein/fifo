package com.fifo.voicepipeline.data.modules

import com.fifo.voicepipeline.data.model.CommunityActivityItem
import com.fifo.voicepipeline.data.model.DailyRoutine
import com.fifo.voicepipeline.data.model.FifoMemoryItem
import com.fifo.voicepipeline.data.model.FifoReminderItem
import com.fifo.voicepipeline.data.model.FifoTasteStory
import com.fifo.voicepipeline.network.FifoBackendClient

/**
 * Costura de publicación de la memoria episódica y de la comunidad.
 *
 * Los módulos son dueños del estado local; la red entra por aquí para que el
 * `AppGraph` inyecte el adaptador real y las pruebas usen el no-op sin abrir
 * sockets (ADR-0049).
 *
 * Los métodos son `suspend` y el adaptador de producción absorbe el fallo: una
 * caída del backend no debe tumbar una acción que ya se guardó en SQLite.
 */
interface MemoryRemotePort {
    suspend fun postMemory(userId: String, item: FifoMemoryItem)
    suspend fun addTaste(userId: String, taste: String)
    suspend fun removeTaste(userId: String, taste: String)
    suspend fun postTasteStory(userId: String, story: FifoTasteStory)
    suspend fun postReminder(userId: String, item: FifoReminderItem)
    suspend fun completeReminder(reminderId: String)
    suspend fun deleteReminder(reminderId: String)
    suspend fun postDailyRoutine(userId: String, routine: DailyRoutine)
}

interface CommunityRemotePort {
    suspend fun toggleJoinActivity(activityId: String, userId: String, join: Boolean)
    suspend fun createCommunityActivity(item: CommunityActivityItem)
    suspend fun postSocialPost(userId: String, content: String, category: String)
}

/** Adaptador inerte: valor por defecto para pruebas y módulos sin red. */
object NoopMemoryRemote : MemoryRemotePort {
    override suspend fun postMemory(userId: String, item: FifoMemoryItem) = Unit
    override suspend fun addTaste(userId: String, taste: String) = Unit
    override suspend fun removeTaste(userId: String, taste: String) = Unit
    override suspend fun postTasteStory(userId: String, story: FifoTasteStory) = Unit
    override suspend fun postReminder(userId: String, item: FifoReminderItem) = Unit
    override suspend fun completeReminder(reminderId: String) = Unit
    override suspend fun deleteReminder(reminderId: String) = Unit
    override suspend fun postDailyRoutine(userId: String, routine: DailyRoutine) = Unit
}

object NoopCommunityRemote : CommunityRemotePort {
    override suspend fun toggleJoinActivity(activityId: String, userId: String, join: Boolean) = Unit
    override suspend fun createCommunityActivity(item: CommunityActivityItem) = Unit
    override suspend fun postSocialPost(userId: String, content: String, category: String) = Unit
}

/** Adaptador de producción sobre [FifoBackendClient]. */
object BackendMemoryRemote : MemoryRemotePort {
    override suspend fun postMemory(userId: String, item: FifoMemoryItem) {
        try {
            FifoBackendClient.postMemory(userId, item.emoji, item.title, item.detail)
        } catch (e: Exception) {
            android.util.Log.d("MemoryRemote", "Aviso al publicar recuerdo: ${e.message}")
        }
    }

    override suspend fun addTaste(userId: String, taste: String) {
        try {
            FifoBackendClient.postTaste(userId, "add", taste)
        } catch (e: Exception) {
            android.util.Log.d("MemoryRemote", "Aviso al publicar gusto: ${e.message}")
        }
    }

    override suspend fun removeTaste(userId: String, taste: String) {
        try {
            FifoBackendClient.postTaste(userId, "remove", taste)
        } catch (e: Exception) {
            android.util.Log.d("MemoryRemote", "Aviso al borrar gusto: ${e.message}")
        }
    }

    override suspend fun postTasteStory(userId: String, story: FifoTasteStory) {
        try {
            FifoBackendClient.postTasteStory(userId, story)
        } catch (e: Exception) {
            android.util.Log.d("MemoryRemote", "Aviso al publicar historia de gusto: ${e.message}")
        }
    }

    override suspend fun postReminder(userId: String, item: FifoReminderItem) {
        try {
            FifoBackendClient.postReminder(userId, item.title, item.timeStr, item.category)
        } catch (e: Exception) {
            android.util.Log.d("MemoryRemote", "Aviso al publicar recordatorio: ${e.message}")
        }
    }

    override suspend fun completeReminder(reminderId: String) {
        try {
            FifoBackendClient.patchReminderComplete(reminderId)
        } catch (e: Exception) {
            android.util.Log.d("MemoryRemote", "Aviso al completar recordatorio: ${e.message}")
        }
    }

    override suspend fun deleteReminder(reminderId: String) {
        try {
            FifoBackendClient.deleteReminder(reminderId)
        } catch (e: Exception) {
            android.util.Log.d("MemoryRemote", "Aviso al borrar recordatorio: ${e.message}")
        }
    }

    override suspend fun postDailyRoutine(userId: String, routine: DailyRoutine) {
        try {
            FifoBackendClient.postDailyRoutine(userId, routine)
        } catch (e: Exception) {
            android.util.Log.d("MemoryRemote", "Aviso al publicar rutina: ${e.message}")
        }
    }
}

object BackendCommunityRemote : CommunityRemotePort {
    override suspend fun toggleJoinActivity(activityId: String, userId: String, join: Boolean) {
        try {
            FifoBackendClient.toggleJoinActivity(activityId = activityId, userId = userId, join = join)
        } catch (e: Exception) {
            android.util.Log.d("CommunityRemote", "Aviso al sincronizar inscripción: ${e.message}")
        }
    }

    override suspend fun createCommunityActivity(item: CommunityActivityItem) {
        try {
            FifoBackendClient.createCommunityActivity(item)
        } catch (e: Exception) {
            android.util.Log.d("CommunityRemote", "Aviso al sincronizar actividad: ${e.message}")
        }
    }

    override suspend fun postSocialPost(userId: String, content: String, category: String) {
        try {
            FifoBackendClient.postSocialPost(userId, content, category)
        } catch (e: Exception) {
            android.util.Log.d("CommunityRemote", "Aviso al publicar en el muro: ${e.message}")
        }
    }
}
