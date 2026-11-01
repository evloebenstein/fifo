package com.fifo.voicepipeline.data.modules

import com.fifo.voicepipeline.data.model.CommunityActivityItem
import com.fifo.voicepipeline.data.model.FifoMemoryItem
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
}

interface CommunityRemotePort {
    suspend fun toggleJoinActivity(activityId: String, userId: String, join: Boolean)
    suspend fun createCommunityActivity(item: CommunityActivityItem)
}

/** Adaptador inerte: valor por defecto para pruebas y módulos sin red. */
object NoopMemoryRemote : MemoryRemotePort {
    override suspend fun postMemory(userId: String, item: FifoMemoryItem) = Unit
}

object NoopCommunityRemote : CommunityRemotePort {
    override suspend fun toggleJoinActivity(activityId: String, userId: String, join: Boolean) = Unit
    override suspend fun createCommunityActivity(item: CommunityActivityItem) = Unit
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
}
