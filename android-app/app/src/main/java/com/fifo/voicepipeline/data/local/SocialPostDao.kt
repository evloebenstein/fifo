package com.fifo.voicepipeline.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.fifo.voicepipeline.data.model.UserSocialPost
import java.util.UUID

/**
 * DAO de publicaciones sociales: `social_posts`.
 *
 * No conoce `Context` ni el ciclo de vida de la base. El helper le pasa las
 * conexiones ya abiertas.
 */
class SocialPostDao(
    private val readableDatabase: () -> SQLiteDatabase,
    private val writableDatabase: () -> SQLiteDatabase
) {
    fun getSocialPosts(): List<UserSocialPost> {
        val list = mutableListOf<UserSocialPost>()
        readableDatabase().rawQuery(
            "SELECT id, author_name, author_age, category, time_ago_label, content, " +
                "likes_count, comments_count, accent_color_hex " +
                "FROM social_posts ORDER BY created_at DESC",
            null
        ).use { cur ->
            while (cur.moveToNext()) {
                list.add(
                    UserSocialPost(
                        id = cur.getString(0),
                        author = cur.getString(1),
                        age = cur.getInt(2),
                        category = cur.getString(3),
                        timeAgo = cur.getString(4),
                        content = cur.getString(5),
                        likes = cur.getInt(6),
                        commentsCount = cur.getInt(7),
                        accentColorHex = cur.getLong(8)
                    )
                )
            }
        }
        return list
    }

    fun addSocialPost(post: UserSocialPost, userId: String) {
        val db = writableDatabase()
        val cv = ContentValues().apply {
            put("id", post.id.ifBlank { "post_${UUID.randomUUID().toString().take(8)}" })
            put("user_id", userId)
            put("author_name", post.author)
            put("author_age", post.age)
            put("category", post.category)
            put("time_ago_label", post.timeAgo)
            put("content", post.content)
            put("likes_count", post.likes)
            put("comments_count", post.commentsCount)
            put("accent_color_hex", post.accentColorHex)
        }
        db.insertWithOnConflict("social_posts", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteSocialPost(postId: String) {
        writableDatabase().delete("social_posts", "id = ?", arrayOf(postId))
    }
}
