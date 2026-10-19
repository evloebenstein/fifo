package com.fifo.voicepipeline.data.modules

import com.fifo.voicepipeline.ui.model.CommunityActivityItem
import com.fifo.voicepipeline.ui.model.UserSocialPost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Costura de almacenamiento para actividades comunitarias y muro social.
 */
interface CommunityStorageAdapter {
    fun getCommunityActivities(userId: String): List<CommunityActivityItem>
    fun saveCommunityActivity(activity: CommunityActivityItem): Boolean
    fun deleteCommunityActivity(activityId: String): Boolean
    fun joinActivity(activityId: String, userId: String): Boolean
    fun leaveActivity(activityId: String, userId: String): Boolean
    fun getSocialPosts(): List<UserSocialPost>
    fun addSocialPost(post: UserSocialPost, userId: String): Boolean
    fun removeSocialPost(postId: String): Boolean
}

/**
 * Adaptador en memoria para actividades comunitarias y muro social.
 */
class InMemoryCommunityStorageAdapter : CommunityStorageAdapter {
    val activities = mutableListOf<CommunityActivityItem>()
    val posts = mutableListOf<UserSocialPost>()

    override fun getCommunityActivities(userId: String): List<CommunityActivityItem> = activities.toList()

    override fun saveCommunityActivity(activity: CommunityActivityItem): Boolean {
        activities.removeAll { it.id == activity.id }
        activities.add(activity)
        return true
    }

    override fun deleteCommunityActivity(activityId: String): Boolean {
        return activities.removeAll { it.id == activityId }
    }

    override fun joinActivity(activityId: String, userId: String): Boolean {
        val index = activities.indexOfFirst { it.id == activityId }
        if (index != -1) {
            val item = activities[index]
            val newTaken = item.spotsTaken + 1
            val remaining = (item.spotsMax - newTaken).coerceAtLeast(0)
            activities[index] = item.copy(
                isJoined = true,
                spotsTaken = newTaken,
                spotsLabel = "Quedan $remaining cupos"
            )
            return true
        }
        return false
    }

    override fun leaveActivity(activityId: String, userId: String): Boolean {
        val index = activities.indexOfFirst { it.id == activityId }
        if (index != -1) {
            val item = activities[index]
            val newTaken = (item.spotsTaken - 1).coerceAtLeast(0)
            val remaining = (item.spotsMax - newTaken).coerceAtLeast(0)
            activities[index] = item.copy(
                isJoined = false,
                spotsTaken = newTaken,
                spotsLabel = "Quedan $remaining cupos"
            )
            return true
        }
        return false
    }

    override fun getSocialPosts(): List<UserSocialPost> = posts.toList()

    override fun addSocialPost(post: UserSocialPost, userId: String): Boolean {
        posts.add(0, post)
        return true
    }

    override fun removeSocialPost(postId: String): Boolean {
        return posts.removeAll { it.id == postId }
    }
}

/**
 * Módulo profundo de Comunidad (CommunityModule).
 * Encapsula la gestión de encuentros comunitarios para adultos mayores, inscripciones y muro social.
 */
class CommunityModule(
    private var storageAdapter: CommunityStorageAdapter = InMemoryCommunityStorageAdapter()
) {
    private val _communityActivities = MutableStateFlow<List<CommunityActivityItem>>(emptyList())
    val communityActivities: StateFlow<List<CommunityActivityItem>> = _communityActivities.asStateFlow()

    private val _socialPosts = MutableStateFlow<List<UserSocialPost>>(emptyList())
    val socialPosts: StateFlow<List<UserSocialPost>> = _socialPosts.asStateFlow()

    fun setStorageAdapter(adapter: CommunityStorageAdapter) {
        this.storageAdapter = adapter
    }

    fun loadInitialData(userId: String) {
        _communityActivities.value = storageAdapter.getCommunityActivities(userId)
        _socialPosts.value = storageAdapter.getSocialPosts()
    }

    fun setActivities(items: List<CommunityActivityItem>) {
        _communityActivities.value = items
    }

    fun setPosts(items: List<UserSocialPost>) {
        _socialPosts.value = items
    }

    fun toggleJoinActivity(activityId: String, userId: String) {
        val currentList = _communityActivities.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == activityId }
        if (index != -1) {
            val current = currentList[index]
            val newJoined = !current.isJoined
            val newTaken = if (newJoined) current.spotsTaken + 1 else (current.spotsTaken - 1).coerceAtLeast(0)
            val remaining = (current.spotsMax - newTaken).coerceAtLeast(0)
            val updated = current.copy(
                isJoined = newJoined,
                spotsTaken = newTaken,
                spotsLabel = "Quedan $remaining cupos"
            )
            currentList[index] = updated
            _communityActivities.value = currentList
            if (newJoined) {
                storageAdapter.joinActivity(activityId, userId)
            } else {
                storageAdapter.leaveActivity(activityId, userId)
            }
        }
    }

    fun createCommunityActivity(item: CommunityActivityItem) {
        _communityActivities.value = listOf(item) + _communityActivities.value.filter { it.id != item.id }
        storageAdapter.saveCommunityActivity(item)
    }

    fun deleteCommunityActivity(activityId: String) {
        _communityActivities.value = _communityActivities.value.filter { it.id != activityId }
        storageAdapter.deleteCommunityActivity(activityId)
    }

    fun addSocialPost(
        content: String,
        category: String = "Bienestar",
        author: String = "",
        username: String = "",
        age: Int = 0,
        userId: String = ""
    ): UserSocialPost {
        val newPost = UserSocialPost(
            id = "post_${UUID.randomUUID()}",
            author = author,
            username = username,
            age = age,
            category = category,
            timeAgo = "Recién publicado",
            content = content.trim(),
            likes = 1,
            commentsCount = 0,
            accentColorHex = 0xFF38BDF8
        )
        _socialPosts.value = listOf(newPost) + _socialPosts.value
        storageAdapter.addSocialPost(newPost, userId)
        return newPost
    }

    fun removeSocialPost(id: String) {
        _socialPosts.value = _socialPosts.value.filter { it.id != id }
        storageAdapter.removeSocialPost(id)
    }
}
