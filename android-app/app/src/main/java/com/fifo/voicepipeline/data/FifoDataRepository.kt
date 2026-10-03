package com.fifo.voicepipeline.data

import com.fifo.voicepipeline.ui.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Repositorio central reactivo de datos de Fifo.
 *
 * Actúa como la fuente de verdad ("Single Source of Truth") para:
 * 1. La aplicación Android (que observa los StateFlows en tiempo real)
 * 2. Las mutaciones ejecutadas autónomamente por el robot Fifo mediante Function Calling / Skills
 * 3. La sincronización futura bidireccional con backend en la nube (Firestore / Supabase)
 */
object FifoDataRepository {

    // ── 1. Perfil del Usuario ──────────────────────────────────
    private val _userProfile = MutableStateFlow(
        UserProfileData(
            id = "usr_lucia_01",
            fullName = "Lucía González",
            birthDate = "14 de Mayo, 1958",
            birthYear = 1958,
            estimatedAge = 68,
            genderIdentity = "Mujer",
            city = "Santiago, Chile",
            bioAi = "Amante de las novelas de historia, la música clásica de piano y las mañanas tranquilas con café. Disfruta compartir con Fifo anécdotas de su familia, preparar recetas caseras y cuidar las orquídeas de su jardín.",
            emergencyContactName = "Carmen (Hija)",
            emergencyContactPhone = "+56987654321"
        )
    )
    val userProfile: StateFlow<UserProfileData> = _userProfile.asStateFlow()

    // ── 2. Gustos e Intereses ──────────────────────────────────
    private val _tastes = MutableStateFlow(
        listOf(
            "Lectura histórica",
            "Música clásica",
            "Paseos en el parque",
            "Jardinería",
            "Cocina tradicional"
        )
    )
    val tastes: StateFlow<List<String>> = _tastes.asStateFlow()

    // ── 3. Historias y Artículos de Blog ("Gustos en detalle") ──
    private val _tasteStories = MutableStateFlow(
        listOf(
            FifoTasteStory(
                id = "t_bday",
                title = "Cumpleaños y orígenes familiares",
                subtitle = "Nacida el 14 de Mayo de 1958 en Santiago",
                description = "Lucía nació en una fresca mañana de otoño en 1958 en el barrio tradicional de Santiago. Siempre recuerda cómo en su familia celebraban con torta de milhojas casera y música de fondo. Para Fifo es un honor acompañarla y tener siempre presente su día especial.",
                tags = listOf("Cumpleaños", "Familia", "Santiago"),
                iconCategory = "celebration",
                learnedFrom = "Charla inicial y configuración de perfil con Fifo"
            ),
            FifoTasteStory(
                id = "t_gardening",
                title = "Cuidado y amor por las orquídeas",
                subtitle = "Riego por inmersión y luz filtrada de mañana",
                description = "En sus charlas con Fifo, Lucía contó que tiene 4 maceteros en su balcón. Disfruta regarlas los miércoles y limpiar con cuidado sus hojas verdes con un paño húmedo mientras escucha la radio.",
                tags = listOf("Jardinería", "Plantas", "Paciencia"),
                iconCategory = "nature",
                learnedFrom = "Charla matutina del 2 de Octubre"
            ),
            FifoTasteStory(
                id = "t_music",
                title = "Conciertos de piano y música clásica",
                subtitle = "Chopin y las tardes tranquilas de otoño",
                description = "La música clásica le transmite una paz inmensa. Cuando le pide a Fifo acompañarla a descansar, las melodías de piano de la época romántica son sus predilectas para recordar su juventud.",
                tags = listOf("Música", "Relajo", "Piano"),
                iconCategory = "music",
                learnedFrom = "Conversación sobre melodías relajantes"
            ),
            FifoTasteStory(
                id = "t_food",
                title = "Recetas tradicionales de la abuela",
                subtitle = "Cazuela de ave y pan amasado con pebre",
                description = "Un gusto que Fifo descubrió con alegría: Lucía atesora un recetario escrito a mano con más de 40 años de antigüedad. Cocinar despacio y con ingredientes frescos es su forma de dar cariño.",
                tags = listOf("Cocina", "Recetas", "Tradición"),
                iconCategory = "restaurant",
                learnedFrom = "Charla de mediodía sobre el almuerzo"
            ),
            FifoTasteStory(
                id = "t_books",
                title = "Novelas históricas y biografías",
                subtitle = "Lecturas pausadas con té de manzanilla",
                description = "Le fascina la historia de Chile y las memorias del siglo XX. Le gusta comentarle a Fifo capítulos interesantes de libros que lee en su sillón favorito cerca de la ventana.",
                tags = listOf("Lectura", "Historia", "Tranquilidad"),
                iconCategory = "book",
                learnedFrom = "Charla nocturna de bienestar"
            ),
            FifoTasteStory(
                id = "t_park",
                title = "Caminatas respirando aire puro",
                subtitle = "Paseos de 20 minutos por plazas con sombra",
                description = "El hábito saludable que Fifo más motiva: salir por las mañanas a caminar a paso tranquilo, sintiendo el aire fresco y saludando a los vecinos del barrio.",
                tags = listOf("Salud", "Paseos", "Bienestar"),
                iconCategory = "walk",
                learnedFrom = "Plan de envejecimiento activo de Fifo"
            )
        )
    )
    val tasteStories: StateFlow<List<FifoTasteStory>> = _tasteStories.asStateFlow()

    // ── 4. Publicaciones Sociales de Lucía ("Fifo Amigos") ────
    private val _socialPosts = MutableStateFlow(
        listOf(
            UserSocialPost(
                id = "post_1",
                author = "Lucía",
                age = 68,
                category = "Jardinería",
                timeAgo = "Hoy, 10:30",
                content = "¡Qué felicidad! Hoy abrió la primera flor de mi orquídea blanca. Fifo me recordó regarla ayer por la tarde y valió toda la pena la paciencia.",
                likes = 14,
                commentsCount = 4,
                accentColorHex = 0xFF10B981
            ),
            UserSocialPost(
                id = "post_2",
                author = "Lucía",
                age = 68,
                category = "Música",
                timeAgo = "Ayer",
                content = "Escuchando los nocturnos de Chopin mientras me tomo un té tibio. Qué lindo es encontrar momentos de calma en la tarde.",
                likes = 19,
                commentsCount = 6,
                accentColorHex = 0xFF8B5CF6
            ),
            UserSocialPost(
                id = "post_3",
                author = "Lucía",
                age = 68,
                category = "Cocina",
                timeAgo = "Hace 3 días",
                content = "Preparé una sopita casera con zapallo camote y cilantro fresco de la feria. Quedó deliciosa para este día fresco.",
                likes = 23,
                commentsCount = 8,
                accentColorHex = 0xFFF59E0B
            )
        )
    )
    val socialPosts: StateFlow<List<UserSocialPost>> = _socialPosts.asStateFlow()

    // ── 5. Recuerdos de Conversaciones ─────────────────────────
    private val _memories = MutableStateFlow(
        listOf(
            FifoMemoryItem(
                id = "m1",
                emoji = "🌺",
                title = "Flor favorita: Orquídeas blancas",
                detail = "Le recuerdan el patio iluminado de su infancia en el sur.",
                learnedDate = "Aprendido hace 2 días"
            ),
            FifoMemoryItem(
                id = "m2",
                emoji = "☕",
                title = "Costumbre de café suave a las 11:00",
                detail = "Siempre con poca azúcar y una galleta de avena.",
                learnedDate = "Aprendido ayer"
            ),
            FifoMemoryItem(
                id = "m3",
                emoji = "📻",
                title = "Radio Beethoven por las tardes",
                detail = "Le acompaña a descansar mientras teje o lee.",
                learnedDate = "Aprendido hoy"
            )
        )
    )
    val memories: StateFlow<List<FifoMemoryItem>> = _memories.asStateFlow()

    // ── 6. Recordatorios y Alarmas del Sistema ──────────────────
    private val _reminders = MutableStateFlow(
        listOf(
            FifoReminderItem(
                id = "rem_1",
                title = "Tomar pastilla de la presión (Enalapril)",
                timeStr = "20:00",
                category = "medication",
                isCompleted = false
            ),
            FifoReminderItem(
                id = "rem_2",
                title = "Llamar a Carmen (Hija)",
                timeStr = "17:30",
                category = "family",
                isCompleted = false
            ),
            FifoReminderItem(
                id = "rem_3",
                title = "Paseo de 15 minutos por la plaza",
                timeStr = "11:00",
                category = "health",
                isCompleted = true
            )
        )
    )
    val reminders: StateFlow<List<FifoReminderItem>> = _reminders.asStateFlow()

    // ── 7. Conversaciones Pasadas ───────────────────────────────
    private val _conversations = MutableStateFlow(
        listOf(
            PastConversationItem(
                id = "c1",
                title = "Charla sobre orquídeas y flores",
                date = "Hoy",
                duration = "4 min",
                summary = "Lucía le contó a Fifo cómo cuida sus plantas en maceteros de greda y lo feliz que le hace verlas florecer.",
                tag = "Jardinería",
                iconName = "heart"
            ),
            PastConversationItem(
                id = "c2",
                title = "Planes de fin de semana y música",
                date = "Ayer",
                duration = "6 min",
                summary = "Conversaron sobre conciertos de piano clásico y la visita que le hará su nieto este domingo.",
                tag = "Música",
                iconName = "music"
            ),
            PastConversationItem(
                id = "c3",
                title = "Recetas de cocina tradicional",
                date = "Hace 3 días",
                duration = "5 min",
                summary = "Fifo aprendió sobre la cazuela de ave casera y el secreto de dorar la cebolla con comino suave.",
                tag = "Cocina",
                iconName = "restaurant"
            )
        )
    )
    val conversations: StateFlow<List<PastConversationItem>> = _conversations.asStateFlow()

    // ══════════════════════════════════════════════════════════
    //  MUTACIONES (Llamadas por los Skills de Fifo o por la UI)
    // ══════════════════════════════════════════════════════════

    /**
     * Actualiza la información demográfica del usuario.
     */
    fun updateDemographics(
        fullName: String? = null,
        birthDate: String? = null,
        birthYear: Int? = null,
        gender: String? = null,
        city: String? = null
    ) {
        val current = _userProfile.value
        val newYear = birthYear ?: current.birthYear
        val newAge = if (birthYear != null) (2026 - birthYear) else current.estimatedAge

        _userProfile.value = current.copy(
            fullName = fullName?.trim()?.ifBlank { current.fullName } ?: current.fullName,
            birthDate = birthDate?.trim()?.ifBlank { current.birthDate } ?: current.birthDate,
            birthYear = newYear,
            estimatedAge = newAge,
            genderIdentity = gender?.trim()?.ifBlank { current.genderIdentity } ?: current.genderIdentity,
            city = city?.trim()?.ifBlank { current.city } ?: current.city
        )
    }

    /**
     * Actualiza la biografía creada por la IA a partir de las conversaciones.
     */
    fun updateBio(newBio: String) {
        if (newBio.isNotBlank()) {
            _userProfile.value = _userProfile.value.copy(bioAi = newBio.trim())
        }
    }

    /**
     * Agrega un nuevo gusto aprendido por Fifo.
     */
    fun addTaste(tasteName: String): Boolean {
        val clean = tasteName.trim()
        if (clean.isBlank()) return false
        val current = _tastes.value
        if (!current.any { it.equals(clean, ignoreCase = true) }) {
            _tastes.value = current + clean
            return true
        }
        return false
    }

    /**
     * Elimina un gusto a petición del usuario.
     */
    fun removeTaste(tasteName: String): Boolean {
        val clean = tasteName.trim()
        val current = _tastes.value
        val filtered = current.filterNot { it.equals(clean, ignoreCase = true) }
        if (filtered.size != current.size) {
            _tastes.value = filtered
            return true
        }
        return false
    }

    /**
     * Añade una historia de vida ("Gusto en detalle").
     */
    fun addTasteStory(story: FifoTasteStory) {
        _tasteStories.value = listOf(story) + _tasteStories.value
    }

    /**
     * Publica un mensaje en Fifo Amigos.
     */
    fun addSocialPost(content: String, category: String = "Bienestar"): UserSocialPost {
        val user = _userProfile.value
        val newPost = UserSocialPost(
            id = "post_${UUID.randomUUID()}",
            author = user.fullName.split(" ").firstOrNull() ?: user.fullName,
            age = user.estimatedAge,
            category = category,
            timeAgo = "Recién publicado",
            content = content.trim(),
            likes = 1,
            commentsCount = 0,
            accentColorHex = 0xFF38BDF8
        )
        _socialPosts.value = listOf(newPost) + _socialPosts.value
        return newPost
    }

    /**
     * Añade un recuerdo aprendido por Fifo.
     */
    fun addMemory(emoji: String, title: String, detail: String) {
        val newMem = FifoMemoryItem(
            id = "m_${UUID.randomUUID()}",
            emoji = emoji.ifBlank { "⭐" },
            title = title.trim(),
            detail = detail.trim(),
            learnedDate = "Aprendido recién"
        )
        _memories.value = listOf(newMem) + _memories.value
    }

    /**
     * Programa un nuevo recordatorio en el sistema.
     */
    fun addReminder(title: String, timeStr: String, category: String = "medication"): FifoReminderItem {
        val item = FifoReminderItem(
            id = "rem_${UUID.randomUUID()}",
            title = title.trim(),
            timeStr = timeStr.trim(),
            category = category,
            isCompleted = false,
            createdAt = "Hoy"
        )
        _reminders.value = listOf(item) + _reminders.value
        return item
    }

    /**
     * Marca un recordatorio como completado.
     */
    fun completeReminder(id: String) {
        _reminders.value = _reminders.value.map {
            if (it.id == id) it.copy(isCompleted = true) else it
        }
    }

    /**
     * Registra una conversación pasada en el historial.
     */
    fun addConversation(item: PastConversationItem) {
        _conversations.value = listOf(item) + _conversations.value
    }

    /**
     * Elimina una conversación por id.
     */
    fun removeConversation(id: String) {
        _conversations.value = _conversations.value.filterNot { it.id == id }
    }

    /**
     * Elimina un recuerdo por id.
     */
    fun removeMemory(id: String) {
        _memories.value = _memories.value.filterNot { it.id == id }
    }
}
