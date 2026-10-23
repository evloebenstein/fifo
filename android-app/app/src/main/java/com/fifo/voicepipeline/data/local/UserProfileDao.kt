package com.fifo.voicepipeline.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.fifo.voicepipeline.data.FifoContentFilter
import com.fifo.voicepipeline.ui.model.UserProfileData
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * DAO de identidad local: `users` y `user_profiles`.
 *
 * Incluye la generación de usernames disponibles porque depende de la
 * unicidad en la tabla `users`, pero no conoce `Context` ni el ciclo de vida
 * de la base. El helper le pasa las conexiones ya abiertas.
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
                   p.preferred_address, p.interest_tags
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
                        interestTags = interestTags
                    )
                }
            }
        } catch (_: Exception) {
            // Fallback si la consulta extendida falla
        }
        return UserProfileData()
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
    fun recommendAvailableUsernames(fullName: String, email: String? = null, batchIndex: Int = 0): List<String> {
        val cleanName = java.text.Normalizer.normalize(fullName.trim(), java.text.Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .lowercase(java.util.Locale.ROOT)
        val nameTokens = cleanName.split(Regex("[\\s._-]+")).filter { it.isNotBlank() && it.length > 1 }
        val first = nameTokens.firstOrNull()?.filter { it.isLetterOrDigit() }?.take(18) ?: ""
        val lastTokens = nameTokens.drop(1).map { it.filter { ch -> ch.isLetterOrDigit() } }.filter { it.isNotBlank() }
        val last = lastTokens.firstOrNull()?.take(18) ?: ""

        // Extraer prefijo y tokens del correo
        val emailPrefix = email?.substringBefore("@")?.lowercase(java.util.Locale.ROOT)?.filter { it.isLetterOrDigit() || it == '.' || it == '_' } ?: ""
        val emailCleanAlpha = emailPrefix.filter { it.isLetterOrDigit() }
        val emailTokens = emailPrefix.split(Regex("[._]+")).filter { it.isNotBlank() && it.length > 1 }

        // Token secundario (apellido real, o segundo token del correo)
        val secondaryToken = when {
            last.isNotBlank() -> last
            emailTokens.size > 1 -> {
                val candidateToken = emailTokens.firstOrNull { eToken ->
                    !first.contains(eToken) && !eToken.contains(first)
                } ?: ""
                candidateToken.take(16)
            }
            else -> ""
        }

        val baseName = if (first.isNotBlank() && !first.equals("usuario", ignoreCase = true)) {
            first
        } else if (emailTokens.isNotEmpty()) {
            emailTokens.first().take(18)
        } else {
            "amigo"
        }

        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) % 100 // ej. 26

        // 5 BUCKETS ESTILÍSTICOS INDEPENDIENTES (Garantizan máxima diversidad estética sin repetición)

        // Bucket 1: Clásico / Canónico Completo
        val bucketCanonical = mutableListOf<String>()
        if (secondaryToken.isNotBlank()) {
            bucketCanonical.add("${baseName}_${secondaryToken}")
            bucketCanonical.add("${baseName}.${secondaryToken}")
            if (baseName.length + secondaryToken.length <= 18) {
                bucketCanonical.add("${baseName}${secondaryToken}")
            }
        } else {
            bucketCanonical.add(baseName)
            bucketCanonical.add("${baseName}_cl")
            bucketCanonical.add("${baseName}_dev")
        }

        // Bucket 2: Minimalista / Inicial Compacta
        val bucketCompact = mutableListOf<String>()
        if (secondaryToken.isNotBlank()) {
            bucketCompact.add("${baseName}_${secondaryToken.take(1)}")
            bucketCompact.add("${baseName.take(1)}_${secondaryToken}")
            bucketCompact.add("${baseName}.${secondaryToken.take(1)}")
            bucketCompact.add("${baseName.take(1)}${secondaryToken}")
        } else {
            bucketCompact.add("${baseName}.ok")
            bucketCompact.add("${baseName}.me")
            bucketCompact.add("the_${baseName}")
        }

        // Bucket 3: Conversacional & Cercano ("Soy", "Hola", "Es")
        val bucketConversational = mutableListOf<String>()
        bucketConversational.add("soy${baseName}")
        bucketConversational.add("hola_${baseName}")
        bucketConversational.add("soy_${baseName}")
        bucketConversational.add("es_${baseName}")

        // Bucket 4: Moniker Personal / Derivado de Correo
        val bucketMoniker = mutableListOf<String>()
        if (emailCleanAlpha.isNotBlank() && emailCleanAlpha != baseName && emailCleanAlpha.length in 3..18) {
            bucketMoniker.add(emailCleanAlpha)
        }
        bucketMoniker.add("${baseName}_real")
        bucketMoniker.add("${baseName}.now")
        bucketMoniker.add("${baseName}.hub")
        bucketMoniker.add("iam${baseName}")

        // Bucket 5: Insignia Temporal / Badge Moderno
        val bucketBadge = mutableListOf<String>()
        bucketBadge.add("${baseName}${currentYear}")
        bucketBadge.add("${baseName}.club")
        bucketBadge.add("${baseName}_vip")
        bucketBadge.add("${baseName}24")
        bucketBadge.add("${baseName}_99")

        val buckets = listOf(bucketCanonical, bucketCompact, bucketConversational, bucketMoniker, bucketBadge)
        val selected = mutableListOf<String>()

        // Seleccionar 1 de cada bucket según el batchIndex, rotando si es necesario
        for (i in buckets.indices) {
            val bucket = buckets[i]
            for (step in 0 until bucket.size) {
                val candidateIdx = (batchIndex + step) % bucket.size
                val rawCand = bucket[candidateIdx]
                val cand = rawCand.filter { it.isLetterOrDigit() || it == '_' || it == '.' }.lowercase(java.util.Locale.ROOT)
                if (cand.length in 3..20 &&
                    !isUsernameTaken(cand) &&
                    cand !in selected &&
                    FifoContentFilter.validateUsername(cand).isValid
                ) {
                    selected.add(cand)
                    break
                }
            }
        }

        // Si algún bucket quedó vacío (por duplicado o disponibilidad), rellenar con candidatos limpios no usados
        val fallbackPool = mutableListOf<String>()
        buckets.flatten().forEach { raw ->
            val cand = raw.filter { it.isLetterOrDigit() || it == '_' || it == '.' }.lowercase(java.util.Locale.ROOT)
            if (cand !in selected && cand.length in 3..20 && !isUsernameTaken(cand) && FifoContentFilter.validateUsername(cand).isValid) {
                fallbackPool.add(cand)
            }
        }
        var fIdx = 0
        while (selected.size < 5 && fIdx < fallbackPool.size) {
            val cand = fallbackPool[fIdx]
            if (cand !in selected) {
                selected.add(cand)
            }
            fIdx++
        }

        // Si aún así no llegamos a 5, generar sufijos únicos con saltos no repetitivos
        val saltSuffixes = listOf("ok", currentYear.toString(), "real", "pro", "cl", "top", "plus", "7", "x")
        var sIdx = 0
        while (selected.size < 5 && sIdx < saltSuffixes.size) {
            val suf = saltSuffixes[(batchIndex + sIdx) % saltSuffixes.size]
            val cand = if (baseName.length + suf.length + 1 <= 20) "${baseName}_$suf" else "${baseName.take(20 - suf.length)}$suf"
            if (!isUsernameTaken(cand) && cand !in selected && FifoContentFilter.validateUsername(cand).isValid) {
                selected.add(cand)
            }
            sIdx++
        }

        return selected.take(5).map { "@$it" }
    }

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
