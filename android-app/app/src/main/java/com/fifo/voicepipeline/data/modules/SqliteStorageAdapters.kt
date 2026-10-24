package com.fifo.voicepipeline.data.modules

import com.fifo.voicepipeline.data.local.FifoDatabaseHelper
import com.fifo.voicepipeline.ui.model.*

/**
 * Adaptador SQLite para la identidad y perfil del usuario.
 */
class SqliteUserStorageAdapter(private val getDbHelper: () -> FifoDatabaseHelper?) : UserStorageAdapter {
    override fun getUserProfile(userId: String): UserProfileData {
        return getDbHelper()?.userProfile?.getUserProfile(userId) ?: UserProfileData(id = userId)
    }

    override fun saveUserProfile(profile: UserProfileData) {
        val helper = getDbHelper() ?: return
        helper.userProfile.updateDemographics(
            userId = profile.id,
            fullName = profile.fullName,
            username = profile.username,
            birthDate = profile.birthDate,
            birthYear = profile.birthYear,
            estimatedAge = profile.estimatedAge,
            gender = profile.genderIdentity,
            city = profile.city
        )
        if (profile.bioAi.isNotBlank()) {
            helper.userProfile.updateBio(profile.id, profile.bioAi)
        }
        if (profile.interestTags.isNotEmpty()) {
            helper.userProfile.updateInterestTags(profile.id, profile.interestTags)
        }
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
        val estimatedAge = if (birthYear != null) (2026 - birthYear) else null
        getDbHelper()?.userProfile?.updateDemographics(
            userId = userId,
            fullName = fullName,
            username = username,
            birthDate = birthDate,
            birthYear = birthYear,
            estimatedAge = estimatedAge,
            gender = gender,
            city = city
        )
    }

    override fun updateBio(userId: String, bio: String) {
        getDbHelper()?.userProfile?.updateBio(userId, bio)
    }

    override fun updateInterestTags(userId: String, tags: List<String>) {
        getDbHelper()?.userProfile?.updateInterestTags(userId, tags)
    }

    override fun findUserByEmailOrId(email: String, googleId: String): String? {
        val helper = getDbHelper() ?: return null
        return try {
            val db = helper.readableDatabase
            val cursor = db.rawQuery(
                "SELECT id FROM users WHERE email = ? OR id = ? OR id = ? LIMIT 1",
                arrayOf(email, googleId, "usr_g_" + googleId.takeLast(6))
            )
            cursor.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun findUserByIdentifier(identifier: String): String? {
        return getDbHelper()?.userProfile?.findUserIdByIdentifier(identifier)
    }

    override fun getAllUsers(): List<UserProfileData> {
        val helper = getDbHelper() ?: return emptyList()
        val list = mutableListOf<UserProfileData>()
        try {
            val db = helper.readableDatabase
            db.rawQuery("SELECT id FROM users", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    if (!id.isNullOrBlank()) {
                        list.add(helper.userProfile.getUserProfile(id))
                    }
                }
            }
        } catch (_: Exception) {}
        return list
    }
}

/**
 * Adaptador SQLite para actividades comunitarias y publicaciones sociales.
 */
class SqliteCommunityStorageAdapter(private val getDbHelper: () -> FifoDatabaseHelper?) : CommunityStorageAdapter {
    override fun getCommunityActivities(userId: String): List<CommunityActivityItem> {
        return getDbHelper()?.communityActivities?.getCommunityActivities(userId) ?: emptyList()
    }

    override fun saveCommunityActivity(activity: CommunityActivityItem): Boolean {
        getDbHelper()?.communityActivities?.addCommunityActivity(activity)
        return true
    }

    override fun deleteCommunityActivity(activityId: String): Boolean {
        getDbHelper()?.communityActivities?.deleteCommunityActivity(activityId)
        return true
    }

    override fun joinActivity(activityId: String, userId: String): Boolean {
        getDbHelper()?.communityActivities?.joinCommunityActivity(activityId, userId)
        return true
    }

    override fun leaveActivity(activityId: String, userId: String): Boolean {
        getDbHelper()?.communityActivities?.leaveCommunityActivity(activityId, userId)
        return true
    }

    override fun getSocialPosts(): List<UserSocialPost> {
        return getDbHelper()?.socialPosts?.getSocialPosts() ?: emptyList()
    }

    override fun addSocialPost(post: UserSocialPost, userId: String): Boolean {
        getDbHelper()?.socialPosts?.addSocialPost(post, userId)
        return true
    }

    override fun removeSocialPost(postId: String): Boolean {
        getDbHelper()?.socialPosts?.deleteSocialPost(postId)
        return true
    }
}

/**
 * Adaptador SQLite para la memoria episódica, recordatorios, gustos y grafo relacional.
 */
class SqliteMemoryStorageAdapter(private val getDbHelper: () -> FifoDatabaseHelper?) : MemoryStorageAdapter {
    override fun getMemories(userId: String): List<FifoMemoryItem> = getDbHelper()?.memories?.getMemories(userId) ?: emptyList()
    override fun addMemory(userId: String, item: FifoMemoryItem) { getDbHelper()?.memories?.addMemory(userId, item) }
    override fun removeMemory(userId: String, id: String) {}

    override fun getReminders(userId: String): List<FifoReminderItem> = getDbHelper()?.reminders?.getReminders(userId) ?: emptyList()
    override fun addReminder(userId: String, item: FifoReminderItem) { getDbHelper()?.reminders?.addReminder(userId, item) }
    override fun updateReminderCompleted(userId: String, id: String, completed: Boolean) { getDbHelper()?.reminders?.completeReminder(userId, id, completed) }
    override fun removeReminder(userId: String, id: String) { getDbHelper()?.reminders?.deleteReminder(userId, id) }

    override fun getConversations(userId: String): List<PastConversationItem> = getDbHelper()?.pastConversations?.getPastConversations(userId) ?: emptyList()
    override fun addConversation(userId: String, item: PastConversationItem) { getDbHelper()?.pastConversations?.addPastConversation(userId, item) }
    override fun removeConversation(userId: String, id: String) { getDbHelper()?.pastConversations?.deletePastConversation(userId, id) }

    override fun getTastes(userId: String): List<String> = getDbHelper()?.tastes?.getTastes(userId) ?: emptyList()
    override fun addTaste(userId: String, taste: String) { getDbHelper()?.tastes?.addTaste(userId, taste) }
    override fun removeTaste(userId: String, taste: String) { getDbHelper()?.tastes?.removeTaste(userId, taste) }

    override fun getTasteStories(userId: String): List<FifoTasteStory> = getDbHelper()?.tastes?.getTasteStories(userId) ?: emptyList()
    override fun addTasteStory(userId: String, story: FifoTasteStory) { getDbHelper()?.tastes?.addTasteStory(userId, story) }
    override fun removeTasteStory(userId: String, id: String) { getDbHelper()?.tastes?.deleteTasteStory(userId, id) }

    override fun getRelationalContacts(userId: String): List<RelationalContact> = getDbHelper()?.getRelationalContacts(userId) ?: emptyList()
    override fun addOrUpdateRelationalContact(userId: String, contact: RelationalContact) { getDbHelper()?.addOrUpdateRelationalContact(userId, contact) }

    override fun getDailyRoutines(userId: String): List<DailyRoutine> = getDbHelper()?.getDailyRoutines(userId) ?: emptyList()
    override fun addOrUpdateDailyRoutine(userId: String, routine: DailyRoutine) { getDbHelper()?.addOrUpdateDailyRoutine(userId, routine) }

    override fun getConversationFragments(userId: String): List<ConversationFragment> = getDbHelper()?.getConversationFragments(userId) ?: emptyList()
    override fun addConversationFragment(userId: String, fragment: ConversationFragment) { getDbHelper()?.addConversationFragment(userId, fragment) }
}
