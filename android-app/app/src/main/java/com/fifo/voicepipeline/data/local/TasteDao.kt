package com.fifo.voicepipeline.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.fifo.voicepipeline.ui.model.FifoTasteStory
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * DAO de gustos e historias de gustos: `tastes` + `taste_stories`.
 *
 * No conoce `Context` ni el ciclo de vida de la base. El helper le pasa las
 * conexiones ya abiertas y comparte el `Gson` para serializar los tags.
 */
class TasteDao(
    private val readableDatabase: () -> SQLiteDatabase,
    private val writableDatabase: () -> SQLiteDatabase,
    private val gson: Gson = Gson()
) {
    fun getTastes(userId: String): List<String> {
        val list = mutableListOf<String>()
        readableDatabase().rawQuery(
            "SELECT name FROM tastes WHERE user_id = ? AND is_active = 1 ORDER BY learned_at ASC",
            arrayOf(userId)
        ).use { cur ->
            while (cur.moveToNext()) {
                list.add(cur.getString(0))
            }
        }
        return list
    }

    fun addTaste(userId: String, name: String, category: String = "general"): Boolean {
        val db = writableDatabase()
        val cv = ContentValues().apply {
            put("id", "t_${UUID.randomUUID().toString().take(8)}")
            put("user_id", userId)
            put("name", name.trim())
            put("category", category)
            put("is_active", 1)
        }
        return db.insertWithOnConflict("tastes", null, cv, SQLiteDatabase.CONFLICT_REPLACE) != -1L
    }

    fun removeTaste(userId: String, name: String): Boolean {
        val db = writableDatabase()
        val cv = ContentValues().apply {
            put("is_active", 0)
        }
        return db.update("tastes", cv, "user_id = ? AND name = ?", arrayOf(userId, name.trim())) > 0
    }

    fun getTasteStories(userId: String): List<FifoTasteStory> {
        val list = mutableListOf<FifoTasteStory>()
        readableDatabase().rawQuery(
            "SELECT id, title, subtitle, description, tags, icon_category, learned_from " +
                "FROM taste_stories WHERE user_id = ? ORDER BY created_at DESC",
            arrayOf(userId)
        ).use { cur ->
            while (cur.moveToNext()) {
                val tagsJson = cur.getString(4) ?: "[]"
                val tags: List<String> = try {
                    gson.fromJson(tagsJson, object : TypeToken<List<String>>() {}.type)
                } catch (e: Exception) {
                    emptyList()
                }
                list.add(
                    FifoTasteStory(
                        id = cur.getString(0),
                        title = cur.getString(1),
                        subtitle = cur.getString(2),
                        description = cur.getString(3),
                        tags = tags,
                        iconCategory = cur.getString(5) ?: "heart",
                        learnedFrom = cur.getString(6) ?: ""
                    )
                )
            }
        }
        return list
    }

    fun addTasteStory(userId: String, story: FifoTasteStory) {
        val db = writableDatabase()
        val cv = ContentValues().apply {
            put("id", story.id.ifBlank { "story_${UUID.randomUUID().toString().take(8)}" })
            put("user_id", userId)
            put("title", story.title)
            put("subtitle", story.subtitle)
            put("description", story.description)
            put("tags", gson.toJson(story.tags))
            put("icon_category", story.iconCategory)
            put("learned_from", story.learnedFrom)
        }
        db.insertWithOnConflict("taste_stories", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteTasteStory(userId: String, storyId: String) {
        writableDatabase().delete("taste_stories", "user_id = ? AND id = ?", arrayOf(userId, storyId))
    }
}
