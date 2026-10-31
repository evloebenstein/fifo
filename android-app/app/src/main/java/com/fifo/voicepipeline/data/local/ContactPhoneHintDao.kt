package com.fifo.voicepipeline.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.fifo.voicepipeline.data.model.ContactPhoneHint
import com.fifo.voicepipeline.data.model.PhoneHintStatus

/**
 * DAO de `contact_phone_hints`: cola local de teléfonos mencionados que esperan
 * confirmación del usuario.
 *
 * Los indicios no se copian a `user_contacts_relational.phone_number` hasta que
 * se confirman, por lo que no se sincronizan ni se usan para llamar. Un indicio
 * descartado conserva su fila con estado `DISCARDED` para no volver a
 * preguntarlo en la misma conversación.
 */
class ContactPhoneHintDao(
    private val readableDatabase: () -> SQLiteDatabase,
    private val writableDatabase: () -> SQLiteDatabase
) {
    fun recordAll(userId: String, hints: List<ContactPhoneHint>) {
        if (hints.isEmpty()) return
        val db = writableDatabase()
        db.beginTransaction()
        try {
            hints.forEach { hint ->
                val values = ContentValues().apply {
                    put("id", hint.id)
                    put("user_id", userId)
                    put("contact_id", hint.contactId)
                    put("phone_number", hint.phoneNumber)
                    put("snippet", hint.snippet)
                    put("status", hint.status.name)
                    if (hint.createdAt.isNotBlank()) {
                        put("created_at", hint.createdAt)
                    }
                }
                db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            Log.e(TAG, "Error registrando teléfonos mencionados: ${e.message}", e)
        } finally {
            db.endTransaction()
        }
    }

    fun getAllPending(userId: String): List<ContactPhoneHint> {
        val list = mutableListOf<ContactPhoneHint>()
        readableDatabase().rawQuery(
            "SELECT id, contact_id, phone_number, snippet, created_at, status " +
                "FROM $TABLE WHERE user_id = ? AND status = ? ORDER BY created_at DESC",
            arrayOf(userId, PhoneHintStatus.PENDING.name)
        ).use { cursor ->
            while (cursor.moveToNext()) list.add(cursor.toHint())
        }
        return list
    }

    fun getPendingById(userId: String, hintId: String): ContactPhoneHint? {
        readableDatabase().rawQuery(
            "SELECT id, contact_id, phone_number, snippet, created_at, status " +
                "FROM $TABLE WHERE user_id = ? AND id = ? AND status = ? LIMIT 1",
            arrayOf(userId, hintId, PhoneHintStatus.PENDING.name)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toHint() else null
        }
    }

    fun markDiscarded(userId: String, hintId: String) {
        val values = ContentValues().apply {
            put("status", PhoneHintStatus.DISCARDED.name)
        }
        writableDatabase().update(
            TABLE,
            values,
            "user_id = ? AND id = ?",
            arrayOf(userId, hintId)
        )
    }

    fun deleteForContact(userId: String, contactId: String) {
        writableDatabase().delete(
            TABLE,
            "user_id = ? AND contact_id = ?",
            arrayOf(userId, contactId)
        )
    }

    private fun android.database.Cursor.toHint(): ContactPhoneHint {
        val status = try {
            PhoneHintStatus.valueOf(getString(5) ?: PhoneHintStatus.PENDING.name)
        } catch (_: Exception) {
            PhoneHintStatus.PENDING
        }
        return ContactPhoneHint(
            id = getString(0) ?: "",
            contactId = getString(1) ?: "",
            phoneNumber = getString(2) ?: "",
            snippet = getString(3),
            createdAt = getString(4) ?: "",
            status = status
        )
    }

    private companion object {
        const val TABLE = "contact_phone_hints"
        const val TAG = "ContactPhoneHintDao"
    }
}
