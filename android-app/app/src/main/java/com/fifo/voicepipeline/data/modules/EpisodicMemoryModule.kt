package com.fifo.voicepipeline.data.modules

import com.fifo.voicepipeline.data.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Costura de almacenamiento para la memoria episódica, gustos, recordatorios y grafo relacional.
 */
interface MemoryStorageAdapter {
    fun getMemories(userId: String): List<FifoMemoryItem>
    fun addMemory(userId: String, item: FifoMemoryItem)
    fun removeMemory(userId: String, id: String)
    fun getReminders(userId: String): List<FifoReminderItem>
    fun addReminder(userId: String, item: FifoReminderItem)
    fun updateReminderCompleted(userId: String, id: String, completed: Boolean)
    fun removeReminder(userId: String, id: String)
    fun getConversations(userId: String): List<PastConversationItem>
    fun addConversation(userId: String, item: PastConversationItem)
    fun removeConversation(userId: String, id: String)
    fun getTastes(userId: String): List<String>
    fun addTaste(userId: String, taste: String)
    fun removeTaste(userId: String, taste: String)
    fun getTasteStories(userId: String): List<FifoTasteStory>
    fun addTasteStory(userId: String, story: FifoTasteStory)
    fun removeTasteStory(userId: String, id: String)
    fun getRelationalContacts(userId: String): List<RelationalContact>
    fun addOrUpdateRelationalContact(userId: String, contact: RelationalContact)
    fun getDailyRoutines(userId: String): List<DailyRoutine>
    fun addOrUpdateDailyRoutine(userId: String, routine: DailyRoutine)
    fun getConversationFragments(userId: String): List<ConversationFragment>
    fun addConversationFragment(userId: String, fragment: ConversationFragment)
}

/**
 * Adaptador en memoria para pruebas unitarias sin emulador ni SQLite.
 */
class InMemoryMemoryStorageAdapter : MemoryStorageAdapter {
    val memories = mutableListOf<FifoMemoryItem>()
    val reminders = mutableListOf<FifoReminderItem>()
    val conversations = mutableListOf<PastConversationItem>()
    val tastes = mutableListOf<String>()
    val tasteStories = mutableListOf<FifoTasteStory>()
    val relationalContacts = mutableListOf<RelationalContact>()
    val dailyRoutines = mutableListOf<DailyRoutine>()
    val conversationFragments = mutableListOf<ConversationFragment>()

    override fun getMemories(userId: String): List<FifoMemoryItem> = memories.toList()
    override fun addMemory(userId: String, item: FifoMemoryItem) { memories.add(0, item) }
    override fun removeMemory(userId: String, id: String) { memories.removeAll { it.id == id } }

    override fun getReminders(userId: String): List<FifoReminderItem> = reminders.toList()
    override fun addReminder(userId: String, item: FifoReminderItem) { reminders.add(0, item) }
    override fun updateReminderCompleted(userId: String, id: String, completed: Boolean) {
        val idx = reminders.indexOfFirst { it.id == id }
        if (idx != -1) reminders[idx] = reminders[idx].copy(isCompleted = completed)
    }
    override fun removeReminder(userId: String, id: String) { reminders.removeAll { it.id == id } }

    override fun getConversations(userId: String): List<PastConversationItem> = conversations.toList()
    override fun addConversation(userId: String, item: PastConversationItem) { conversations.add(0, item) }
    override fun removeConversation(userId: String, id: String) { conversations.removeAll { it.id == id } }

    override fun getTastes(userId: String): List<String> = tastes.toList()
    override fun addTaste(userId: String, taste: String) { if (!tastes.contains(taste)) tastes.add(taste) }
    override fun removeTaste(userId: String, taste: String) { tastes.remove(taste) }

    override fun getTasteStories(userId: String): List<FifoTasteStory> = tasteStories.toList()
    override fun addTasteStory(userId: String, story: FifoTasteStory) { tasteStories.add(0, story) }
    override fun removeTasteStory(userId: String, id: String) { tasteStories.removeAll { it.id == id } }

    override fun getRelationalContacts(userId: String): List<RelationalContact> = relationalContacts.toList()
    override fun addOrUpdateRelationalContact(userId: String, contact: RelationalContact) {
        relationalContacts.removeAll { it.contactName.equals(contact.contactName, ignoreCase = true) }
        relationalContacts.add(contact)
    }

    override fun getDailyRoutines(userId: String): List<DailyRoutine> = dailyRoutines.toList()
    override fun addOrUpdateDailyRoutine(userId: String, routine: DailyRoutine) {
        dailyRoutines.removeAll { it.id == routine.id }
        dailyRoutines.add(routine)
    }

    override fun getConversationFragments(userId: String): List<ConversationFragment> = conversationFragments.toList()
    override fun addConversationFragment(userId: String, fragment: ConversationFragment) {
        conversationFragments.add(0, fragment)
    }
}

/**
 * Módulo profundo de Memoria Episódica y Contexto (EpisodicMemoryModule).
 *
 * Encapsula la memoria doble capa de Fifo:
 * - Capa 1: Ventana de contexto compacta local (<80 tokens) para inferencia ultra-rápida sin consumir tokens excesivos.
 * - Capa 2: Búsqueda profunda multinivel con conexión al agente del servidor backend (Grafo Relacional) y fallback local.
 */
class EpisodicMemoryModule(
    private var storageAdapter: MemoryStorageAdapter = InMemoryMemoryStorageAdapter()
) {
    private val _memories = MutableStateFlow<List<FifoMemoryItem>>(emptyList())
    val memories: StateFlow<List<FifoMemoryItem>> = _memories.asStateFlow()

    private val _reminders = MutableStateFlow<List<FifoReminderItem>>(emptyList())
    val reminders: StateFlow<List<FifoReminderItem>> = _reminders.asStateFlow()

    private val _conversations = MutableStateFlow<List<PastConversationItem>>(emptyList())
    val conversations: StateFlow<List<PastConversationItem>> = _conversations.asStateFlow()

    private val _tastes = MutableStateFlow<List<String>>(emptyList())
    val tastes: StateFlow<List<String>> = _tastes.asStateFlow()

    private val _tasteStories = MutableStateFlow<List<FifoTasteStory>>(emptyList())
    val tasteStories: StateFlow<List<FifoTasteStory>> = _tasteStories.asStateFlow()

    private val _relationalContacts = MutableStateFlow<List<RelationalContact>>(emptyList())
    val relationalContacts: StateFlow<List<RelationalContact>> = _relationalContacts.asStateFlow()

    private val _dailyRoutines = MutableStateFlow<List<DailyRoutine>>(emptyList())
    val dailyRoutines: StateFlow<List<DailyRoutine>> = _dailyRoutines.asStateFlow()

    private val _conversationFragments = MutableStateFlow<List<ConversationFragment>>(emptyList())
    val conversationFragments: StateFlow<List<ConversationFragment>> = _conversationFragments.asStateFlow()

    fun setStorageAdapter(adapter: MemoryStorageAdapter) {
        this.storageAdapter = adapter
    }

    fun loadUserData(userId: String) {
        _memories.value = storageAdapter.getMemories(userId)
        _reminders.value = storageAdapter.getReminders(userId)
        _conversations.value = storageAdapter.getConversations(userId)
        _tastes.value = storageAdapter.getTastes(userId)
        _tasteStories.value = storageAdapter.getTasteStories(userId)
        _relationalContacts.value = storageAdapter.getRelationalContacts(userId)
        _dailyRoutines.value = storageAdapter.getDailyRoutines(userId)
        _conversationFragments.value = storageAdapter.getConversationFragments(userId)
    }

    // ── CAPA 1: Memoria compacta en el dispositivo (<80 tokens) ──────────────────────────
    fun buildCompactContextWindow(profile: UserProfileData, maxFragments: Int = 1): String {
        val topTastes = _tastes.value.take(4)
        val topContacts = _relationalContacts.value.sortedByDescending { it.closenessScore }.take(2)
        val activeReminders = _reminders.value.filter { !it.isCompleted }.take(2)
        val fragments = if (maxFragments > 0) _conversationFragments.value.take(maxFragments) else emptyList()

        return buildString {
            appendLine("=== CONTEXTO DEL USUARIO ===")
            appendLine("Nombre: ${profile.fullName.ifBlank { "Usuario" }} | Edad: ${profile.estimatedAge} | Ciudad: ${profile.city}")
            if (topTastes.isNotEmpty()) {
                appendLine("Intereses y gustos: ${topTastes.joinToString(", ")}")
            }
            if (topContacts.isNotEmpty()) {
                append("Contactos clave: ")
                appendLine(topContacts.joinToString("; ") { "${it.contactName} (${it.relationshipRole})" })
            }
            if (activeReminders.isNotEmpty()) {
                append("Recordatorios pendientes: ")
                appendLine(activeReminders.joinToString("; ") { "${it.timeStr}: ${it.title}" })
            }
            if (fragments.isNotEmpty()) {
                val lastFrag = fragments.first()
                if (lastFrag.compactSummary.isNotBlank()) {
                    appendLine("Última charla: ${lastFrag.compactSummary.take(120)}")
                }
            }
        }.trim()
    }

    // ── CAPA 2: Memoria profunda (fallback local; el backend lo orquesta ConversationContext) ──
    /**
     * Índice local de la capa 2.
     *
     * Es una función pura sobre la memoria del dispositivo: no toca la red ni
     * bloquea con `runBlocking`. `ConversationContext.searchDeep()` consulta
     * primero el backend y cae aquí solo si no hay respuesta o no hay
     * coincidencias remotas (ADR-0049).
     */
    fun searchDeepLocal(query: String): DeepContextResult {
        return EpisodicContextSearch.search(
            query = query,
            snapshot = EpisodicContextSnapshot(
                fragments = _conversationFragments.value,
                memories = _memories.value,
                conversations = _conversations.value,
                tastes = _tastes.value,
                tasteStories = _tasteStories.value,
                reminders = _reminders.value,
                relationalContacts = _relationalContacts.value,
                dailyRoutines = _dailyRoutines.value
            )
        )
    }

    // ── Mutaciones de datos ─────────────────────────────────────────────────────────────
    fun addMemory(userId: String, emoji: String, title: String, detail: String): FifoMemoryItem {
        val item = FifoMemoryItem(
            id = "m_${UUID.randomUUID()}",
            emoji = emoji.ifBlank { "⭐" },
            title = title.trim(),
            detail = detail.trim(),
            learnedDate = "Aprendido recién"
        )
        _memories.value = listOf(item) + _memories.value
        storageAdapter.addMemory(userId, item)
        return item
    }

    fun removeMemory(userId: String, id: String) {
        _memories.value = _memories.value.filter { it.id != id }
        storageAdapter.removeMemory(userId, id)
    }

    fun addReminder(userId: String, title: String, timeStr: String, category: String = "medication"): FifoReminderItem {
        val item = FifoReminderItem(
            id = "rem_${UUID.randomUUID()}",
            title = title.trim(),
            timeStr = timeStr.trim(),
            category = category,
            isCompleted = false,
            createdAt = "Hoy"
        )
        _reminders.value = listOf(item) + _reminders.value
        storageAdapter.addReminder(userId, item)
        return item
    }

    fun completeReminder(userId: String, id: String) {
        _reminders.value = _reminders.value.map {
            if (it.id == id) it.copy(isCompleted = true) else it
        }
        storageAdapter.updateReminderCompleted(userId, id, true)
    }

    fun removeReminder(userId: String, id: String) {
        _reminders.value = _reminders.value.filter { it.id != id }
        storageAdapter.removeReminder(userId, id)
    }

    fun addTaste(userId: String, tasteName: String): Boolean {
        val trimmed = tasteName.trim()
        if (trimmed.isBlank() || _tastes.value.any { it.equals(trimmed, ignoreCase = true) }) {
            return false
        }
        _tastes.value = _tastes.value + trimmed
        storageAdapter.addTaste(userId, trimmed)
        return true
    }

    fun removeTaste(userId: String, tasteName: String): Boolean {
        val trimmed = tasteName.trim()
        val filtered = _tastes.value.filterNot { it.equals(trimmed, ignoreCase = true) }
        if (filtered.size != _tastes.value.size) {
            _tastes.value = filtered
            storageAdapter.removeTaste(userId, trimmed)
            return true
        }
        return false
    }

    fun addTasteStory(userId: String, story: FifoTasteStory) {
        _tasteStories.value = listOf(story) + _tasteStories.value.filter { it.id != story.id }
        storageAdapter.addTasteStory(userId, story)
    }

    fun removeTasteStory(userId: String, id: String) {
        _tasteStories.value = _tasteStories.value.filter { it.id != id }
        storageAdapter.removeTasteStory(userId, id)
    }

    fun addConversation(userId: String, item: PastConversationItem) {
        _conversations.value = listOf(item) + _conversations.value
        storageAdapter.addConversation(userId, item)
    }

    fun removeConversation(userId: String, id: String) {
        _conversations.value = _conversations.value.filter { it.id != id }
        storageAdapter.removeConversation(userId, id)
    }

    fun addOrUpdateRelationalContact(userId: String, contact: RelationalContact) {
        val current = _relationalContacts.value.toMutableList()
        current.removeAll { it.contactName.equals(contact.contactName, ignoreCase = true) }
        current.add(0, contact)
        _relationalContacts.value = current.sortedByDescending { it.closenessScore }
        storageAdapter.addOrUpdateRelationalContact(userId, contact)
    }

    fun addOrUpdateDailyRoutine(userId: String, routine: DailyRoutine) {
        val current = _dailyRoutines.value.toMutableList()
        current.removeAll { it.id == routine.id }
        current.add(routine)
        _dailyRoutines.value = current
        storageAdapter.addOrUpdateDailyRoutine(userId, routine)
    }

    fun addConversationFragment(userId: String, fragment: ConversationFragment) {
        _conversationFragments.value = listOf(fragment) + _conversationFragments.value
        storageAdapter.addConversationFragment(userId, fragment)
    }

    fun setDirectMemories(list: List<FifoMemoryItem>) { _memories.value = list }
    fun setDirectReminders(list: List<FifoReminderItem>) { _reminders.value = list }
    fun setDirectConversations(list: List<PastConversationItem>) { _conversations.value = list }
    fun setDirectTastes(list: List<String>) { _tastes.value = list }
    fun setDirectTasteStories(list: List<FifoTasteStory>) { _tasteStories.value = list }
    fun setDirectRelationalContacts(list: List<RelationalContact>) { _relationalContacts.value = list }
    fun setDirectDailyRoutines(list: List<DailyRoutine>) { _dailyRoutines.value = list }
    fun setDirectFragments(list: List<ConversationFragment>) { _conversationFragments.value = list }
}
