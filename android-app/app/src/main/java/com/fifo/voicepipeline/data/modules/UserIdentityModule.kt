package com.fifo.voicepipeline.data.modules

import com.fifo.voicepipeline.ui.model.UserProfileData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Costura de almacenamiento para la identidad y perfil del usuario.
 */
interface UserStorageAdapter {
    fun getUserProfile(userId: String): UserProfileData
    fun saveUserProfile(profile: UserProfileData)
    fun updateDemographics(
        userId: String,
        fullName: String? = null,
        username: String? = null,
        birthDate: String? = null,
        birthYear: Int? = null,
        gender: String? = null,
        city: String? = null
    )
    fun updateBio(userId: String, bio: String)
    fun updateInterestTags(userId: String, tags: List<String>)
    fun findUserByEmailOrId(email: String, googleId: String): String?
    fun findUserByIdentifier(identifier: String): String?
    fun getAllUsers(): List<UserProfileData>
}

/**
 * Adaptador en memoria para pruebas unitarias sin Android Context ni SQLite.
 */
class InMemoryUserStorageAdapter : UserStorageAdapter {
    private val users = mutableMapOf<String, UserProfileData>()
    private val emailOrGoogleIndex = mutableMapOf<String, String>()

    override fun getUserProfile(userId: String): UserProfileData {
        return users[userId] ?: UserProfileData(id = userId)
    }

    override fun saveUserProfile(profile: UserProfileData) {
        users[profile.id] = profile
    }

    override fun updateDemographics(
        userId: String,
        fullName: String?,
        username: String?,
        birthDate: String?,
        birthYear: Int?,
        gender: String?,
        city: String?
    ) {
        val current = getUserProfile(userId)
        val newYear = birthYear ?: current.birthYear
        val newAge = if (birthYear != null) (2026 - birthYear) else current.estimatedAge
        users[userId] = current.copy(
            fullName = fullName?.trim()?.ifBlank { current.fullName } ?: current.fullName,
            username = username?.trim()?.removePrefix("@")?.ifBlank { current.username } ?: current.username,
            birthDate = birthDate?.trim()?.ifBlank { current.birthDate } ?: current.birthDate,
            birthYear = newYear,
            estimatedAge = newAge,
            genderIdentity = gender?.trim()?.ifBlank { current.genderIdentity } ?: current.genderIdentity,
            city = city?.trim()?.ifBlank { current.city } ?: current.city
        )
    }

    override fun updateBio(userId: String, bio: String) {
        val current = getUserProfile(userId)
        users[userId] = current.copy(bioAi = bio.trim())
    }

    override fun updateInterestTags(userId: String, tags: List<String>) {
        val current = getUserProfile(userId)
        users[userId] = current.copy(interestTags = tags)
    }

    fun indexEmail(identifier: String, userId: String) {
        emailOrGoogleIndex[identifier.lowercase().trim()] = userId
    }

    override fun findUserByEmailOrId(email: String, googleId: String): String? {
        val emailId = emailOrGoogleIndex[email.lowercase().trim()]
        if (emailId != null) return emailId
        val gId = emailOrGoogleIndex[googleId.trim()]
        if (gId != null) return gId
        return users.values.firstOrNull { it.id == googleId }?.id
    }

    override fun findUserByIdentifier(identifier: String): String? {
        val clean = identifier.trim()
        val handleTarget = clean.removePrefix("@").lowercase()
        val user = users.values.firstOrNull {
            it.username.equals(handleTarget, ignoreCase = true) ||
            it.fullName.equals(clean, ignoreCase = true) ||
            it.id == clean
        }
        return user?.id ?: emailOrGoogleIndex[clean.lowercase()]
    }

    override fun getAllUsers(): List<UserProfileData> = users.values.toList()
}

/**
 * Módulo profundo de Identidad de Usuario (UserIdentityModule).
 * Encapsula la gestión de sesión, datos demográficos, gustos e identificadores de usuario.
 */
class UserIdentityModule(
    private var storageAdapter: UserStorageAdapter = InMemoryUserStorageAdapter(),
    initialUser: UserProfileData = UserProfileData()
) {
    private val _userProfile = MutableStateFlow(initialUser)
    val userProfile: StateFlow<UserProfileData> = _userProfile.asStateFlow()

    fun setStorageAdapter(adapter: UserStorageAdapter) {
        this.storageAdapter = adapter
    }

    fun loadUser(userId: String) {
        val loaded = storageAdapter.getUserProfile(userId)
        if (loaded.id.isNotBlank()) {
            _userProfile.value = loaded
        }
    }

    fun setUserProfile(profile: UserProfileData) {
        _userProfile.value = profile
        storageAdapter.saveUserProfile(profile)
    }

    fun updateDemographics(
        fullName: String? = null,
        username: String? = null,
        birthDate: String? = null,
        birthYear: Int? = null,
        gender: String? = null,
        city: String? = null
    ) {
        val current = _userProfile.value
        val newYear = birthYear ?: current.birthYear
        val newAge = if (birthYear != null) (2026 - birthYear) else current.estimatedAge
        val cleanUsername = username?.trim()?.removePrefix("@")?.filter { it.isLetterOrDigit() || it == '_' }?.lowercase()

        val updated = current.copy(
            fullName = fullName?.trim()?.ifBlank { current.fullName } ?: current.fullName,
            username = cleanUsername?.ifBlank { current.username } ?: current.username,
            birthDate = birthDate?.trim()?.ifBlank { current.birthDate } ?: current.birthDate,
            birthYear = newYear,
            estimatedAge = newAge,
            genderIdentity = gender?.trim()?.ifBlank { current.genderIdentity } ?: current.genderIdentity,
            city = city?.trim()?.ifBlank { current.city } ?: current.city
        )
        _userProfile.value = updated
        storageAdapter.updateDemographics(
            userId = current.id,
            fullName = fullName,
            username = cleanUsername,
            birthDate = birthDate,
            birthYear = newYear,
            gender = gender,
            city = city
        )
    }

    fun updateBio(newBio: String) {
        if (newBio.isNotBlank()) {
            val clean = newBio.trim()
            _userProfile.value = _userProfile.value.copy(bioAi = clean)
            storageAdapter.updateBio(_userProfile.value.id, clean)
        }
    }

    fun updateInterestTags(tags: List<String>) {
        if (tags.isNotEmpty()) {
            val cleanTags = tags.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            _userProfile.value = _userProfile.value.copy(interestTags = cleanTags)
            storageAdapter.updateInterestTags(_userProfile.value.id, cleanTags)
        }
    }

    fun findUserByEmailOrId(email: String, googleId: String): String? {
        return storageAdapter.findUserByEmailOrId(email, googleId)
    }

    fun findUserByIdentifier(identifier: String): String? {
        return storageAdapter.findUserByIdentifier(identifier)
    }
}
