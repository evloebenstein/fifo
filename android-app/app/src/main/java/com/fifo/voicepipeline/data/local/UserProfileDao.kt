package com.fifo.voicepipeline.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.fifo.voicepipeline.data.model.UserProfileData
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * DAO de identidad local: `users` y `user_profiles`.
 *
 * Incluye la verificación de unicidad de usernames y delega la generación de
 * sugerencias en [UsernameRecommender]. No conoce `Context` ni el ciclo de vida
 * de la base: el helper le pasa las conexiones ya abiertas.
 */
class UserProfileDao(
    private val readableDatabase: () -> SQLiteDatabase,
    private val writableDatabase: () -> SQLiteDatabase,
    private val gson: Gson = Gson()
) {
    fun getUserProfile(userId: String): UserProfileData {
        val db = readableDatabase()
        val sql = """
            SELECT u.id, u.full_name, u.username, u.birth_date, u.birth_year, u.estimated_age,
                   u.gender_identity, u.city, p.bio_ai, p.emergency_contact_name, p.emergency_contact_phone,
                   p.preferred_address, p.interest_tags, u.email
            FROM users u
            LEFT JOIN user_profiles p ON p.user_id = u.id
            WHERE u.id = ?
        """.trimIndent()

        try {
            db.rawQuery(sql, arrayOf(userId)).use { cursor ->
                if (cursor.moveToFirst()) {
                    val rawHandle = cursor.getString(2) ?: ""
                    val prefAddr = if (cursor.columnCount > 11) cursor.getString(11) ?: "" else ""
                    val rawTags = if (cursor.columnCount > 12) cursor.getString(12) else null
                    val email = if (cursor.columnCount > 13) cursor.getString(13) ?: "" else ""
                    val interestTags = parseStringList(rawTags)
                    return UserProfileData(
                        id = cursor.getString(0) ?: userId,
                        fullName = cursor.getString(1) ?: "",
                        username = rawHandle.removePrefix("@"),
                        birthDate = cursor.getString(3) ?: "",
                        birthYear = cursor.getInt(4).takeIf { it > 0 } ?: 0,
                        estimatedAge = cursor.getInt(5).takeIf { it > 0 } ?: 0,
                        genderIdentity = cursor.getString(6) ?: "",
                        city = cursor.getString(7) ?: "",
                        bioAi = cursor.getString(8) ?: "",
                        emergencyContactName = cursor.getString(9) ?: "",
                        emergencyContactPhone = cursor.getString(10) ?: "",
                        preferredAddress = prefAddr,
                        interestTags = interestTags,
                        email = email
                    )
                }
            }
        } catch (_: Exception) {
            // Fallback si la consulta extendida falla
        }
        return UserProfileData()
    }

    /**
     * Upsert del perfil recibido desde el backend.
     *
     * Escribe `users` y `user_profiles` con el mismo contrato que usaba la
     * sincronización masiva del helper.
     */
    fun upsertSyncedProfile(profile: UserProfileData) {
        val db = writableDatabase()
        val userValues = ContentValues().apply {
            put("id", profile.id)
            put("full_name", profile.fullName)
            put("username", profile.username)
            put("email", profile.email.ifBlank { "${profile.id}@fifo.app" })
            put("birth_date", profile.birthDate)
            put("birth_year", profile.birthYear)
            put("estimated_age", profile.estimatedAge)
            put("gender_identity", profile.genderIdentity)
            put("city", profile.city)
        }
        db.insertWithOnConflict("users", null, userValues, SQLiteDatabase.CONFLICT_REPLACE)

        val profileValues = ContentValues().apply {
            put("user_id", profile.id)
            put("bio_ai", profile.bioAi)
            put("emergency_contact_name", profile.emergencyContactName)
            put("emergency_contact_phone", profile.emergencyContactPhone)
            put("preferred_address", profile.preferredAddress)
            put("interest_tags", gson.toJson(profile.interestTags))
        }
        db.insertWithOnConflict("user_profiles", null, profileValues, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Busca el ID de un usuario local en SQLite según su ID, username (@ID), email o nombre completo.
     */
    fun findUserIdByIdentifier(identifier: String): String? {
        val clean = identifier.trim().removePrefix("@")
        if (clean.isBlank()) return null
        val db = readableDatabase()
        val sql = """
            SELECT id FROM users 
            WHERE LOWER(id) = LOWER(?) 
               OR LOWER(username) = LOWER(?) 
               OR LOWER(email) = LOWER(?) 
               OR LOWER(full_name) = LOWER(?)
               OR LOWER(full_name) LIKE LOWER(?)
            LIMIT 1
        """.trimIndent()
        try {
            db.rawQuery(sql, arrayOf(clean, clean, clean, clean, "%$clean%")).use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(0)
                }
            }
        } catch (e: Exception) {
            Log.e("UserProfileDao", "Error buscando usuario por identificador: ${e.message}")
        }
        return null
    }

    fun updateUserProfileNames(userId: String, fullName: String, username: String) {
        val db = writableDatabase()
        val cv = ContentValues().apply {
            put("full_name", fullName)
            put("username", username.removePrefix("@"))
        }
        db.update("users", cv, "id = ?", arrayOf(userId))
    }

    /**
     * Verifica si un username (@ID) ya está registrado en la base de datos local.
     */
    fun isUsernameTaken(username: String): Boolean {
        val clean = username.trim().removePrefix("@").lowercase()
        if (clean.isBlank()) return false
        val db = readableDatabase()
        return try {
            db.rawQuery("SELECT 1 FROM users WHERE LOWER(username) = ? LIMIT 1", arrayOf(clean)).use { cursor ->
                cursor.moveToFirst()
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Recomienda una lista de IDs únicos disponibles comparando el nombre real, apellido
     * y tokens de correo contra los usuarios ya registrados en la base de datos local.
     * Genera sugerencias diversas, naturales y verifica que no contengan términos ofensivos o reservados.
     * Soporta diferentes lotes (batchIndex) para permitir que el usuario explore nuevas opciones.
     */
    fun recommendAvailableUsernames(fullName: String, email: String? = null, batchIndex: Int = 0): List<String> =
        UsernameRecommender.recommend(fullName, email, batchIndex, ::isUsernameTaken)

    fun updateDemographics(
        userId: String,
        fullName: String? = null,
        username: String? = null,
        birthDate: String? = null,
        birthYear: Int? = null,
        estimatedAge: Int? = null,
        gender: String? = null,
        city: String? = null
    ) {
        val db = writableDatabase()
        val cv = ContentValues()
        fullName?.let { cv.put("full_name", it) }
        username?.let { cv.put("username", it.removePrefix("@")) }
        birthDate?.let { cv.put("birth_date", it) }
        birthYear?.let { cv.put("birth_year", it) }
        estimatedAge?.let { cv.put("estimated_age", it) }
        gender?.let { cv.put("gender_identity", it) }
        city?.let { cv.put("city", it) }

        if (cv.size() > 0) {
            db.update("users", cv, "id = ?", arrayOf(userId))
        }
    }

    fun updateBio(userId: String, newBio: String) {
        val db = writableDatabase()
        val cv = ContentValues().apply {
            put("bio_ai", newBio)
        }
        db.update("user_profiles", cv, "user_id = ?", arrayOf(userId))
    }

    fun updateInterestTags(userId: String, tags: List<String>) {
        val db = writableDatabase()
        val cv = ContentValues().apply {
            put("interest_tags", gson.toJson(tags))
        }
        db.update("user_profiles", cv, "user_id = ?", arrayOf(userId))
    }

    private fun parseStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val type = object : TypeToken<List<String>>() {}.type
        return try {
            gson.fromJson<List<String>>(raw, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
