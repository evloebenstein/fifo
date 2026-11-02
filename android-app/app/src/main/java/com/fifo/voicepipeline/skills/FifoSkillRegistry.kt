package com.fifo.voicepipeline.skills

import android.content.Context
import android.location.LocationManager
import android.util.Log
import com.fifo.voicepipeline.data.AppGraph
import com.fifo.voicepipeline.data.context.ConversationContext
import com.fifo.voicepipeline.location.AndroidLocationAdapter
import com.fifo.voicepipeline.location.FifoNavigationManager
import com.fifo.voicepipeline.location.LocationAdapter
import com.fifo.voicepipeline.location.NavigationSessionStore
import com.fifo.voicepipeline.location.SharedPreferencesNavigationSessionStore

/**
 * Registro y despachador central de Skills de Fifo.
 *
 * Actúa como fachada desacoplada para:
 * 1. Registrar y mantener las 14 herramientas nativas del teléfono y mutaciones de BD.
 * 2. Generar el catálogo de herramientas para Groq LPU a través de [GroqToolCatalog].
 * 3. Ejecutar las herramientas solicitadas por el LLM.
 * 4. Despachar intenciones rápidas locales sin ambigüedad mediante [VoiceIntentRouter].
 */
class FifoSkillRegistry(
    private val context: Context,
    /**
     * Composition root (ADR-0049). Las skills reciben solo el módulo que usan;
     * el registro nunca consulta el accessor `FifoDataRepository` (ADR-0051).
     */
    private val graph: AppGraph = AppGraph.requireInstance(),
    private val groqCatalog: GroqToolCatalog = GroqToolCatalog(),
    private val locationAdapter: LocationAdapter = AndroidLocationAdapter(context) {
        graph.identity.userProfile.value
    },
    /**
     * Persistencia de la ruta activa y su sesión conversacional (ADR-0041).
     * Producción usa `SharedPreferences`; las pruebas pueden inyectar un doble.
     */
    sessionStore: NavigationSessionStore = SharedPreferencesNavigationSessionStore(context),
    /**
     * Motor de navegación compartido por el router, las skills y el pipeline.
     * Se construye una sola vez por registro; no existe un singleton global.
     */
    val navigationManager: FifoNavigationManager = FifoNavigationManager(
        locationAdapter = locationAdapter,
        locationManagerProvider = {
            context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        },
        sessionStore = sessionStore
    ),
    private val intentRouter: VoiceIntentRouter = VoiceIntentRouter(navigationManager, graph.listening),
    /**
     * Contexto conversacional compartido (ADR-0049). Si es `null`, la skill de
     * memoria usa el del grafo; las pruebas pueden inyectar un doble.
     */
    private val conversationContext: ConversationContext? = null
) {

    companion object {
        private const val TAG = "FifoSkillRegistry"
    }

    private val skills = mutableMapOf<String, FifoSkill>()

    /** Último lugar o local consultado (para continuidad conversacional en navegación) */
    var lastQueriedPlace: String = ""

    /** Último tema de conversación activa en el dispositivo */
    var lastTopic: String = ""

    init {
        val promptContext = conversationContext ?: graph.conversationContext
        registerSkill(ReminderSkill(
            identity = graph.identity,
            memory = graph.memory,
            relationalGraph = graph.relationalGraph
        ))
        registerSkill(CalendarSkill(context))
        registerSkill(MapsSkill(
            locationAdapter = locationAdapter,
            navigationManager = navigationManager,
            intentLauncher = { intent -> context.startActivity(intent) }
        ))
        registerSkill(PhoneCommunicationSkill(
            context = context,
            identity = graph.identity,
            relationalGraph = graph.relationalGraph
        ))
        registerSkill(ProfileDatabaseSkill(
            session = graph.session,
            identity = graph.identity,
            memory = graph.memory,
            community = graph.community
        ))
        registerSkill(ContextRecallSkill { query ->
            promptContext.searchDeep(query)
        })
        registerSkill(FindFifoDeviceSkill(
            context = context,
            device = graph.device
        ))
        registerSkill(CurrentLocationSkill(locationAdapter))
        registerSkill(NavigationDirectionsSkill(
            locationAdapter = locationAdapter,
            navigationManager = navigationManager
        ))
        registerSkill(NavigationSessionSkill(
            locationAdapter = locationAdapter,
            navigationManager = navigationManager
        ))
        registerSkill(PhoneCallSkill(
            context = context,
            telephony = graph.telephony,
            identity = graph.identity,
            relationalGraph = graph.relationalGraph
        ))
        registerSkill(PhoneDeviceControlSkill(
            context = context,
            device = graph.device
        ))
        registerSkill(ContactsSkill(
            context = context,
            contactsSync = graph.contactsSync,
            relationalGraph = graph.relationalGraph,
            identity = graph.identity
        ))
        registerSkill(WebSearchSkill(context, locationAdapter))
        Log.i(TAG, "Inicializado FifoSkillRegistry con ${skills.size} herramientas.")
    }

    fun registerSkill(skill: FifoSkill) {
        skills[skill.name] = skill
    }

    fun getSkill(name: String): FifoSkill? = skills[name]

    /**
     * Genera la lista de herramientas en formato ultra-compacto para Groq LPU,
     * optimizada para nunca superar el límite de 7,000 ITPM y con soporte GraphRAG.
     */
    fun getCompactOpenAiToolsJson(): String = groqCatalog.getCompactToolsJson()

    /**
     * Genera la lista de herramientas dinámicas sanitizadas para Groq/OpenAI.
     */
    fun getOpenAiToolsJson(): String = groqCatalog.getDynamicToolsJson(skills.values)

    /**
     * Ejecuta una herramienta por nombre con los argumentos recibidos.
     */
    suspend fun executeSkill(name: String, args: Map<String, Any?>): SkillResult {
        if (name == "search_nearby_places") {
            val q = (args["query_hint"] as? String) ?: (args["place_type"] as? String) ?: ""
            if (q.isNotBlank()) lastQueriedPlace = q
        } else if (name == "open_navigation_directions") {
            val d = (args["destination"] as? String) ?: ""
            if (d.isNotBlank()) lastQueriedPlace = d
        }
        val skill = skills[name]
        return if (skill != null) {
            try {
                skill.execute(args)
            } catch (e: Exception) {
                Log.e(TAG, "Error ejecutando skill '$name': ${e.message}", e)
                SkillResult(
                    success = false,
                    spokenFeedback = "Disculpe, tuve un inconveniente al realizar esa acción en su teléfono."
                )
            }
        } else {
            Log.w(TAG, "Skill no encontrada: '$name'")
            SkillResult(
                success = false,
                spokenFeedback = "No encontré cómo realizar esa acción específica."
            )
        }
    }

    /**
     * Enrutador de intenciones locales por voz.
     * Resuelve en microsegundos comandos de hardware o llamadas telefónicas.
     * Retorna `null` para cualquier consulta de memoria, anécdotas, recetas o temas conversacionales.
     */
    suspend fun tryExecuteVoiceIntent(transcript: String): SkillResult? {
        return intentRouter.routeFastIntent(transcript) { skillName, args ->
            executeSkill(skillName, args)
        }
    }
}
