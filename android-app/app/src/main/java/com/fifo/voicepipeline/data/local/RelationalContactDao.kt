package com.fifo.voicepipeline.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.fifo.voicepipeline.ui.model.RelationalContact
import com.google.gson.Gson
import java.util.UUID

/**
 * DAO del grafo social local: `user_contacts_relational`.
 *
 * Incluye la fusión por nombre o teléfono y la ingesta atómica de contactos del
 * dispositivo. No conoce `Context` ni el ciclo de vida de la base; el helper le
 * pasa las conexiones y comparte el `Gson` para los IDs de conversación.
 */
class RelationalContactDao(
    private val readableDatabase: () -> SQLiteDatabase,
    private val writableDatabase: () -> SQLiteDatabase,
    private val gson: Gson = Gson()
) {
    fun getRelationalContacts(userId: String): List<RelationalContact> {
        val list = mutableListOf<RelationalContact>()
        readableDatabase().rawQuery(
            "SELECT id, contact_name, phone_number, relationship_role, closeness_score, " +
                "trust_tier, emotional_valence, contextual_memory, mention_count, " +
                "last_mentioned_at, linked_conversation_ids " +
                "FROM user_contacts_relational WHERE user_id = ? " +
                "ORDER BY closeness_score DESC, mention_count DESC",
            arrayOf(userId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val linkedIds = parseStringList(
                    if (cursor.columnCount > 10) cursor.getString(10) else null
                )
                list.add(
                    RelationalContact(
                        id = cursor.getString(0) ?: "",
                        contactName = cursor.getString(1) ?: "",
                        phoneNumber = cursor.getString(2),
                        relationshipRole = cursor.getString(3) ?: "",
                        closenessScore = cursor.getFloat(4),
                        trustTier = cursor.getInt(5),
                        emotionalValence = cursor.getString(6) ?: "afectuoso",
                        contextualMemory = cursor.getString(7),
                        mentionCount = cursor.getInt(8),
                        lastMentionedAt = cursor.getString(9) ?: "",
                        linkedConversationIds = linkedIds
                    )
                )
            }
        }
        return list
    }

    fun addOrUpdateRelationalContact(userId: String, contact: RelationalContact) {
        upsert(writableDatabase(), userId, contact)
    }

    /**
     * Ingesta o sincroniza por lotes los contactos reales del teléfono en
     * SQLite de forma atómica.
     */
    fun syncDeviceContactsBatch(userId: String, contacts: List<RelationalContact>) {
        val db = writableDatabase()
        db.beginTransaction()
        try {
            contacts.forEach { contact -> upsert(db, userId, contact) }
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            Log.e(TAG, "Error en syncDeviceContactsBatch: ${e.message}", e)
        } finally {
            db.endTransaction()
        }
    }

    private fun upsert(db: SQLiteDatabase, userId: String, contact: RelationalContact) {
        // 1. Verificar si ya existe por nombre o número telefónico para este usuario
        var existingId: String? = null
        var existingScore = 0.5f
        var existingMentions = 0
        var existingMemory: String? = null
        var existingLinkedStr: String? = null

        val queryName = contact.contactName.trim()
        val queryPhone = contact.phoneNumber?.trim()

        val selectSql = if (!queryPhone.isNullOrBlank()) {
            "SELECT id, closeness_score, mention_count, contextual_memory, linked_conversation_ids " +
                "FROM user_contacts_relational WHERE user_id = ? " +
                "AND (LOWER(contact_name) = LOWER(?) OR phone_number = ?) LIMIT 1"
        } else {
            "SELECT id, closeness_score, mention_count, contextual_memory, linked_conversation_ids " +
                "FROM user_contacts_relational WHERE user_id = ? " +
                "AND LOWER(contact_name) = LOWER(?) LIMIT 1"
        }
        val selectArgs = if (!queryPhone.isNullOrBlank()) {
            arrayOf(userId, queryName, queryPhone)
        } else {
            arrayOf(userId, queryName)
        }

        try {
            db.rawQuery(selectSql, selectArgs).use { cursor ->
                if (cursor.moveToFirst()) {
                    existingId = cursor.getString(0)
                    existingScore = cursor.getFloat(1)
                    existingMentions = cursor.getInt(2)
                    existingMemory = cursor.getString(3)
                    if (cursor.columnCount > 4) existingLinkedStr = cursor.getString(4)
                }
            }
        } catch (_: Exception) {
            // Fallback si la columna aún se está migrando
            val fallbackSql = "SELECT id, closeness_score, mention_count, contextual_memory " +
                "FROM user_contacts_relational WHERE user_id = ? " +
                "AND LOWER(contact_name) = LOWER(?) LIMIT 1"
            db.rawQuery(fallbackSql, arrayOf(userId, queryName)).use { cursor ->
                if (cursor.moveToFirst()) {
                    existingId = cursor.getString(0)
                    existingScore = cursor.getFloat(1)
                    existingMentions = cursor.getInt(2)
                    existingMemory = cursor.getString(3)
                }
            }
        }

        val targetId = existingId ?: contact.id.ifBlank {
            "ctc_rel_${UUID.randomUUID().toString().take(8)}"
        }
        val finalScore = if (existingId != null && contact.closenessScore <= 0.5f && existingScore > 0.5f) {
            existingScore
        } else {
            contact.closenessScore
        }
        val finalMentions = if (existingId != null && contact.mentionCount == 0 && existingMentions > 0) {
            existingMentions
        } else {
            contact.mentionCount
        }
        val finalMemory = if (contact.contextualMemory.isNullOrBlank()) {
            existingMemory
        } else {
            contact.contextualMemory
        }
        val existingLinked = parseStringList(existingLinkedStr)
        val mergedLinked = (existingLinked + contact.linkedConversationIds).distinct()

        val cv = ContentValues().apply {
            put("id", targetId)
            put("user_id", userId)
            put("contact_name", contact.contactName)
            put("phone_number", contact.phoneNumber)
            put("relationship_role", contact.relationshipRole)
            put("closeness_score", finalScore)
            put("trust_tier", contact.trustTier)
            put("emotional_valence", contact.emotionalValence)
            put("contextual_memory", finalMemory)
            put("mention_count", finalMentions)
            put("linked_conversation_ids", gson.toJson(mergedLinked))
        }

        db.insertWithOnConflict(
            "user_contacts_relational",
            null,
            cv,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun parseStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(raw, type) ?: emptyList()
        } catch (_: Exception) {
            raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    private companion object {
        const val TAG = "RelationalContactDao"
    }
}
