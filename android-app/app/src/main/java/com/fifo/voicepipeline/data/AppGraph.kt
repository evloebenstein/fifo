package com.fifo.voicepipeline.data

import android.content.Context
import com.fifo.voicepipeline.data.context.BackendDeepContextPort
import com.fifo.voicepipeline.data.context.ConversationContext
import com.fifo.voicepipeline.data.contacts.AndroidContactSource
import com.fifo.voicepipeline.data.contacts.DeviceContactsSync
import com.fifo.voicepipeline.data.device.DeviceState
import com.fifo.voicepipeline.data.local.FifoDatabaseHelper
import com.fifo.voicepipeline.data.modules.CommunityModule
import com.fifo.voicepipeline.data.modules.EpisodicMemoryModule
import com.fifo.voicepipeline.data.modules.RelationalGraphModule
import com.fifo.voicepipeline.data.modules.SqliteCommunityStorageAdapter
import com.fifo.voicepipeline.data.modules.SqliteMemoryStorageAdapter
import com.fifo.voicepipeline.data.modules.SqliteUserStorageAdapter
import com.fifo.voicepipeline.data.modules.UserIdentityModule
import com.fifo.voicepipeline.data.session.UserSession
import com.fifo.voicepipeline.location.FifoLocationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Composition root de los datos de usuario (ADR-0049).
 *
 * Se construye **una vez** en [com.fifo.voicepipeline.FifoApplication] y lo
 * comparten la actividad y el servicio. Cada consumidor toma el módulo que usa;
 * no existe un `UserContext` con cuarenta métodos.
 *
 * Propiedad de cada pieza:
 * - [identity] → perfil y autenticación local.
 * - [memory] → memoria episódica, gustos, recordatorios y rutinas.
 * - [community] → actividades y muro social.
 * - [relationalGraph] → contactos, apariciones e indicios de teléfono.
 * - [session] → quién está activo, login y sincronización.
 * - [device] → robot, hardware y conexión con backend.
 * - [conversationContext] → ventana de prompt y búsqueda profunda.
 *
 * `FifoDataRepository` queda como accessor `@Deprecated` que delega aquí
 * durante la migración strangler.
 */
class AppGraph internal constructor(
    context: Context,
    val database: FifoDatabaseHelper = FifoDatabaseHelper.getInstance(context.applicationContext)
) {
    private val appContext = context.applicationContext
    internal val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val identity = UserIdentityModule(SqliteUserStorageAdapter { database })
    val community = CommunityModule(SqliteCommunityStorageAdapter { database })
    val memory = EpisodicMemoryModule(SqliteMemoryStorageAdapter { database })

    val relationalGraph = RelationalGraphModule(
        memory = memory,
        database = { database },
        scope = scope,
        userId = { identity.userProfile.value.id }
    )

    val contactsSync = DeviceContactsSync(
        source = AndroidContactSource(appContext),
        graph = relationalGraph
    )

    val device = DeviceState(
        database = { database },
        scope = scope,
        userId = { identity.userProfile.value.id }
    )

    val session = UserSession(
        context = appContext,
        identity = identity,
        memory = memory,
        community = community,
        database = database,
        device = device,
        contactsSync = contactsSync,
        scope = scope
    )

    /**
     * Contexto de navegación opcional. Lo instala `VoicePipelineManager` cuando
     * construye su `FifoSkillRegistry`; el grafo no posee el motor de rutas.
     */
    @Volatile
    var navigationContextProvider: (() -> String)? = null

    val conversationContext = ConversationContext(
        identity = identity,
        memory = memory,
        deepContext = BackendDeepContextPort(),
        location = { FifoLocationHelper.getCurrentLocation(appContext) },
        navigation = { navigationContextProvider?.invoke().orEmpty() }
    )

    companion object {
        @Volatile
        private var instance: AppGraph? = null

        /**
         * Instala el grafo si aún no existe y restaura la sesión una sola vez.
         * Es idempotente: actividad, servicio y receivers comparten la instancia.
         */
        fun install(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context).also { graph ->
                    instance = graph
                    graph.session.restore()
                }
            }

        fun requireInstance(): AppGraph =
            instance ?: error("AppGraph no instalado: llama a FifoApplication o a FifoDataRepository.initialize()")

        fun instanceOrNull(): AppGraph? = instance

        /** Solo para pruebas que necesiten aislar el grafo. */
        internal fun resetForTests() {
            instance = null
        }
    }
}
