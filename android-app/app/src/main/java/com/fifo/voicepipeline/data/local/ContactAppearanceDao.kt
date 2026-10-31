package com.fifo.voicepipeline.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.fifo.voicepipeline.data.model.ContactAppearance
import com.fifo.voicepipeline.data.model.ContactSource

/**
 * DAO de `contact_appearances`: dónde apareció cada persona del grafo.
 *
 * Local y append-only: el mismo origen no se duplica gracias a la PK
 * determinística que genera `RelationalGraphCoordinator`. No conoce `Context`.
 */
class ContactAppearanceDao(
    private val readableDatabase: () -> SQLiteDatabase,
    private val writableDatabase: () -> SQLiteDatabase
) {
    fun recordAll(userId: String, appearances: List<ContactAppearance>) {
        if (appearances.isEmpty()) return
        val db = writableDatabase()
        db.beginTransaction()
        try {
            appearances.forEach { appearance ->
                val values = ContentValues().apply {
                    put("id", appearance.id)
                    put("user_id", userId)
                    put("contact_id", appearance.contactId)
                    put("source", appearance.source.name)
                    put("source_id", appearance.sourceId)
                    put("snippet", appearance.snippet)
                    if (appearance.occurredAt.isNotBlank()) {
                        put("occurred_at", appearance.occurredAt)
                    }
                }
                db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            Log.e(TAG, "Error registrando apariciones del grafo relacional: ${e.message}", e)
        } finally {
            db.endTransaction()
        }
    }

    fun getAppearances(userId: String, contactId: String): List<ContactAppearance> {
        val list = mutableListOf<ContactAppearance>()
        readableDatabase().rawQuery(
            "SELECT id, contact_id, source, source_id, snippet, occurred_at " +
                "FROM $TABLE WHERE user_id = ? AND contact_id = ? " +
                "ORDER BY occurred_at DESC",
            arrayOf(userId, contactId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursor.toAppearance())
            }
        }
        return list
    }

    fun getAllAppearances(userId: String): List<ContactAppearance> {
        val list = mutableListOf<ContactAppearance>()
        readableDatabase().rawQuery(
            "SELECT id, contact_id, source, source_id, snippet, occurred_at " +
                "FROM $TABLE WHERE user_id = ? ORDER BY occurred_at DESC",
            arrayOf(userId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursor.toAppearance())
            }
        }
        return list
    }

    private fun android.database.Cursor.toAppearance(): ContactAppearance {
        val source = try {
            ContactSource.valueOf(getString(2) ?: "")
        } catch (_: Exception) {
            ContactSource.MANUAL
        }
        return ContactAppearance(
            id = getString(0) ?: "",
            contactId = getString(1) ?: "",
            source = source,
            sourceId = getString(3),
            snippet = getString(4),
            occurredAt = getString(5) ?: ""
        )
    }

    private companion object {
        const val TABLE = "contact_appearances"
        const val TAG = "ContactAppearanceDao"
    }
}
