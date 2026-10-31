package com.fifo.voicepipeline.data.modules

import com.fifo.voicepipeline.ui.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Pruebas unitarias en memoria para los módulos profundos de datos de Fifo:
 * 1. UserIdentityModule
 * 2. CommunityModule
 * 3. EpisodicMemoryModule (con memoria doble capa: CompactContextWindow y DeepContext)
 */
class DomainModulesTest {

    private lateinit var userStorage: InMemoryUserStorageAdapter
    private lateinit var userModule: UserIdentityModule

    private lateinit var communityStorage: InMemoryCommunityStorageAdapter
    private lateinit var communityModule: CommunityModule

    private lateinit var memoryStorage: InMemoryMemoryStorageAdapter
    private lateinit var memoryModule: EpisodicMemoryModule

    @Before
    fun setUp() {
        userStorage = InMemoryUserStorageAdapter()
        userModule = UserIdentityModule(userStorage)

        communityStorage = InMemoryCommunityStorageAdapter()
        communityModule = CommunityModule(communityStorage)

        memoryStorage = InMemoryMemoryStorageAdapter()
        memoryModule = EpisodicMemoryModule(memoryStorage)
    }

    // ══════════════════════════════════════════════════════════
    //  1. UserIdentityModule Tests
    // ══════════════════════════════════════════════════════════

    @Test
    fun testUserIdentity_DefaultAndSetProfile() {
        assertEquals("", userModule.userProfile.value.id)

        val profile = UserProfileData(
            id = "usr_carmen_01",
            fullName = "Carmen Gloria",
            username = "carmen",
            estimatedAge = 72,
            city = "Ñuñoa, Santiago"
        )
        userModule.setUserProfile(profile)

        assertEquals("usr_carmen_01", userModule.userProfile.value.id)
        assertEquals("Carmen Gloria", userModule.userProfile.value.fullName)
        assertEquals(72, userModule.userProfile.value.estimatedAge)
        assertEquals("Ñuñoa, Santiago", userModule.userProfile.value.city)

        // Verificar persistencia en el adapter
        val stored = userStorage.getUserProfile("usr_carmen_01")
        assertEquals("Carmen Gloria", stored.fullName)
    }

    @Test
    fun testUserIdentity_UpdateDemographicsAndBio() {
        val initial = UserProfileData(id = "usr_carmen_01", fullName = "Carmen")
        userModule.setUserProfile(initial)

        userModule.updateDemographics(
            fullName = "Carmen Gloria Valdés",
            username = "@carmengloria",
            birthYear = 1952,
            city = "Providencia",
            gender = "Mujer"
        )

        assertEquals("Carmen Gloria Valdés", userModule.userProfile.value.fullName)
        assertEquals("carmengloria", userModule.userProfile.value.username)
        assertEquals(74, userModule.userProfile.value.estimatedAge) // 2026 - 1952
        assertEquals("Providencia", userModule.userProfile.value.city)

        userModule.updateBio("Disfruto mucho cuidar mis plantas y escuchar boleros.")
        assertEquals("Disfruto mucho cuidar mis plantas y escuchar boleros.", userModule.userProfile.value.bioAi)

        userModule.updateInterestTags(listOf("Botánica", "Boleros", "Jardinería"))
        assertEquals(listOf("Botánica", "Boleros", "Jardinería"), userModule.userProfile.value.interestTags)
    }

    @Test
    fun testUserIdentity_FindByIdentifier() {
        val profile = UserProfileData(
            id = "usr_100",
            fullName = "Tomás Loyola",
            username = "tomasl"
        )
        userStorage.saveUserProfile(profile)
        userStorage.indexEmail("tomas@fifo.app", "usr_100")

        assertEquals("usr_100", userModule.findUserByIdentifier("@tomasl"))
        assertEquals("usr_100", userModule.findUserByIdentifier("tomas@fifo.app"))
        assertEquals("usr_100", userModule.findUserByIdentifier("Tomás Loyola"))
    }

    // ══════════════════════════════════════════════════════════
    //  2. CommunityModule Tests
    // ══════════════════════════════════════════════════════════

    @Test
    fun testCommunity_JoinAndLeaveActivity() {
        val activity = CommunityActivityItem(
            id = "act_01",
            title = "Tarde de Pintura en el Parque",
            spotsMax = 10,
            spotsTaken = 2,
            dateLabel = "Sábado 16:00",
            locationName = "Parque Bustamante",
            isJoined = false
        )
        communityModule.createCommunityActivity(activity)
        assertEquals(1, communityModule.communityActivities.value.size)

        // Inscribirse en la actividad
        communityModule.toggleJoinActivity("act_01", "usr_carmen")
        val joinedItem = communityModule.communityActivities.value.first()
        assertTrue(joinedItem.isJoined)
        assertEquals(3, joinedItem.spotsTaken)
        assertEquals("Quedan 7 cupos", joinedItem.spotsLabel)

        // Cancelar inscripción
        communityModule.toggleJoinActivity("act_01", "usr_carmen")
        val leftItem = communityModule.communityActivities.value.first()
        assertFalse(leftItem.isJoined)
        assertEquals(2, leftItem.spotsTaken)
        assertEquals("Quedan 8 cupos", leftItem.spotsLabel)
    }

    @Test
    fun testCommunity_SocialPostsFeed() {
        communityModule.addSocialPost(
            content = "¡Qué linda mañana para dar un paseo por la plaza!",
            category = "Bienestar",
            author = "Rosa",
            username = "rosita",
            age = 75,
            userId = "usr_rosa"
        )

        assertEquals(1, communityModule.socialPosts.value.size)
        val post = communityModule.socialPosts.value.first()
        assertEquals("¡Qué linda mañana para dar un paseo por la plaza!", post.content)
        assertEquals("Rosa", post.author)
        assertEquals("Bienestar", post.category)

        communityModule.removeSocialPost(post.id)
        assertTrue(communityModule.socialPosts.value.isEmpty())
    }

    // ══════════════════════════════════════════════════════════
    //  3. EpisodicMemoryModule Tests (Memoria Doble Capa)
    // ══════════════════════════════════════════════════════════

    @Test
    fun testEpisodicMemory_Layer1_CompactContextWindowUnder80Tokens() {
        val user = UserProfileData(
            id = "usr_carmen_01",
            fullName = "Carmen Gloria",
            estimatedAge = 72,
            city = "Santiago"
        )

        memoryModule.addTaste("usr_carmen_01", "Orquídeas")
        memoryModule.addTaste("usr_carmen_01", "Boleros")
        memoryModule.addTaste("usr_carmen_01", "Té verde")

        memoryModule.addOrUpdateRelationalContact("usr_carmen_01", RelationalContact(
            id = "ctc_01",
            contactName = "Patricia",
            relationshipRole = "Hija",
            closenessScore = 0.95f
        ))

        memoryModule.addReminder("usr_carmen_01", "Tomar Losartán 50mg", "09:00", "medication")

        memoryModule.addConversationFragment("usr_carmen_01", ConversationFragment(
            id = "frag_01",
            serverConversationId = "srv_conv_01",
            keyTopics = listOf("orquídeas", "riego"),
            namedEntities = listOf("Patricia"),
            compactSummary = "Conversamos sobre cómo regar las orquídeas dos veces por semana.",
            primaryTag = "Jardinería"
        ))

        // Generar ventana compacta de contexto (Capa 1)
        val compactContext = memoryModule.buildCompactContextWindow(user, maxFragments = 1)

        assertTrue(compactContext.contains("=== CONTEXTO DEL USUARIO ==="))
        assertTrue(compactContext.contains("Nombre: Carmen Gloria | Edad: 72 | Ciudad: Santiago"))
        assertTrue(compactContext.contains("Intereses y gustos: Orquídeas, Boleros, Té verde"))
        assertTrue(compactContext.contains("Patricia (Hija)"))
        assertTrue(compactContext.contains("09:00: Tomar Losartán 50mg"))
        assertTrue(compactContext.contains("Última charla: Conversamos sobre cómo regar las orquídeas"))

        // Verificar concisión (menos de 80 palabras / tokens para Groq)
        val wordCount = compactContext.split("\\s+".toRegex()).size
        assertTrue("La ventana de contexto debe tener menos de 80 tokens, pero tiene $wordCount", wordCount < 80)
    }

    @Test
    fun testEpisodicMemory_Layer2_DeepContextSearchFallback() {
        val userId = "usr_carmen_01"

        memoryModule.addMemory(userId, "🌺", "Premio de Orquídeas 2018", "Ganó el primer lugar en la exposición floral del Club de Jardines.")
        memoryModule.addTasteStory(userId, FifoTasteStory(
            id = "story_01",
            title = "Pasión por los Boleros de Lucho Gatica",
            subtitle = "Música de su juventud",
            description = "Su padre ponía discos de vinilo de boleros todos los domingos por la tarde.",
            tags = listOf("Música", "Boleros", "Lucho Gatica"),
            iconCategory = "music",
            learnedFrom = "charla del 12 de octubre"
        ))
        memoryModule.addReminder(userId, "Comprar abono floral", "11:30", "hobby")

        // Búsqueda profunda sobre el tema "orquídeas"
        val resultOrquideas = memoryModule.searchDeepLocal("orquídeas floral")
        assertTrue(resultOrquideas.relevantExcerpts.isNotEmpty())
        assertTrue(resultOrquideas.relevantExcerpts.any { it.contains("Premio de Orquídeas") || it.contains("abono floral") })

        // Búsqueda profunda sobre el tema "boleros"
        val resultBoleros = memoryModule.searchDeepLocal("boleros música vinilo")
        assertTrue(resultBoleros.relevantExcerpts.isNotEmpty())
        assertTrue(resultBoleros.relevantExcerpts.any { it.contains("Lucho Gatica") })

        // Búsqueda sobre tema inexistente
        val resultInexistente = memoryModule.searchDeepLocal("astronomía telescopio")
        assertTrue(resultInexistente.relevantExcerpts.isEmpty())
        assertTrue(resultInexistente.synthesizedContext.contains("no encontré registros"))
    }

    @Test
    fun testEpisodicMemory_TasteAndReminderMutations() {
        val userId = "usr_test"

        // Agregar gustos y evitar duplicados
        assertTrue(memoryModule.addTaste(userId, "Fotografía"))
        assertFalse(memoryModule.addTaste(userId, "Fotografía")) // Duplicado rechazado
        assertEquals(listOf("Fotografía"), memoryModule.tastes.value)

        assertTrue(memoryModule.removeTaste(userId, "Fotografía"))
        assertTrue(memoryModule.tastes.value.isEmpty())

        // Recordatorios y completado
        val reminder = memoryModule.addReminder(userId, "Kinesiólogo", "15:00", "health")
        assertEquals(1, memoryModule.reminders.value.size)
        assertFalse(memoryModule.reminders.value.first().isCompleted)

        memoryModule.completeReminder(userId, reminder.id)
        assertTrue(memoryModule.reminders.value.first().isCompleted)

        memoryModule.removeReminder(userId, reminder.id)
        assertTrue(memoryModule.reminders.value.isEmpty())
    }
}
