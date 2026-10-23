package com.fifo.voicepipeline.data.local

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.fifo.voicepipeline.ui.model.CommunityActivityItem
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * DAO de actividades comunitarias y sus inscripciones.
 *
 * Vive fuera de [FifoDatabaseHelper] para que el helper central no conozca el
 * SQL de cada agregado. El ciclo de vida de la base sigue siendo del helper;
 * este DAO solo recibe las funciones que abren las conexiones.
 */
class CommunityActivityDao(
    private val readableDatabase: () -> SQLiteDatabase,
    private val writableDatabase: () -> SQLiteDatabase,
    private val gson: Gson = Gson()
) {
    fun getCommunityActivities(userId: String): List<CommunityActivityItem> {
        val list = mutableListOf<CommunityActivityItem>()
        val db = readableDatabase()
        val cursor: Cursor? = db.rawQuery(
            """
            SELECT a.id, a.title, a.description, a.spots_label, a.spots_max, a.spots_taken,
                   a.date_label, a.location_name, a.distance_label, a.icon_category,
                   a.organizer_id, a.organizer_name, a.organizer_type, a.target_tastes,
                   a.created_at,
                   (SELECT COUNT(*) FROM activity_participants ap WHERE ap.activity_id = a.id AND ap.user_id = ? AND ap.status = 'joined') as is_user_joined,
                   (SELECT COUNT(*) FROM activity_participants ap WHERE ap.activity_id = a.id AND ap.status = 'joined') as real_joined_count
            FROM community_activities a
            ORDER BY a.created_at DESC
            """.trimIndent(),
            arrayOf(userId)
        )
        cursor?.use { c ->
            while (c.moveToNext()) {
                val tastesJson = c.getString(13) ?: "[]"
                val tastesList: List<String> = try {
                    gson.fromJson(tastesJson, object : TypeToken<List<String>>() {}.type) ?: emptyList()
                } catch (e: Exception) {
                    emptyList()
                }
                val realJoined = c.getInt(16)
                val baseSpotsTaken = c.getInt(5)
                val totalTaken = if (realJoined > baseSpotsTaken) realJoined else baseSpotsTaken
                val maxSpots = c.getInt(4).takeIf { it > 0 } ?: 15
                val remaining = (maxSpots - totalTaken).coerceAtLeast(0)

                list.add(
                    CommunityActivityItem(
                        id = c.getString(0),
                        title = c.getString(1),
                        description = c.getString(2) ?: "",
                        spotsLabel = if (remaining > 0) "Quedan $remaining cupos" else "Cupos completos",
                        spotsMax = maxSpots,
                        spotsTaken = totalTaken,
                        dateLabel = c.getString(6),
                        locationName = c.getString(7),
                        distanceLabel = c.getString(8) ?: "Cerca de ti",
                        iconCategory = c.getString(9) ?: "book",
                        organizerId = c.getString(10) ?: "",
                        organizerName = c.getString(11) ?: "Fifo",
                        organizerType = c.getString(12) ?: "fifo",
                        targetTastes = tastesList,
                        isJoined = c.getInt(15) > 0,
                        createdAt = c.getString(14) ?: ""
                    )
                )
            }
        }
        return list
    }

    fun addCommunityActivity(item: CommunityActivityItem): Boolean {
        val cv = ContentValues().apply {
            put("id", item.id)
            put("title", item.title)
            put("description", item.description)
            put("spots_label", item.spotsLabel)
            put("spots_max", item.spotsMax)
            put("spots_taken", item.spotsTaken)
            put("date_label", item.dateLabel)
            put("location_name", item.locationName)
            put("distance_label", item.distanceLabel)
            put("icon_category", item.iconCategory)
            put("organizer_id", item.organizerId)
            put("organizer_name", item.organizerName)
            put("organizer_type", item.organizerType)
            put("target_tastes", gson.toJson(item.targetTastes))
            if (item.createdAt.isNotBlank()) {
                put("created_at", item.createdAt)
            }
        }
        val result = writableDatabase().insertWithOnConflict(
            "community_activities",
            null,
            cv,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        return result != -1L
    }

    fun joinCommunityActivity(activityId: String, userId: String): Boolean {
        val cv = ContentValues().apply {
            put("activity_id", activityId)
            put("user_id", userId)
            put("status", "joined")
        }
        val res = writableDatabase().insertWithOnConflict(
            "activity_participants",
            null,
            cv,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        if (res != -1L) {
            writableDatabase().execSQL(
                "UPDATE community_activities SET spots_taken = spots_taken + 1 WHERE id = ?",
                arrayOf(activityId)
            )
            return true
        }
        return false
    }

    fun leaveCommunityActivity(activityId: String, userId: String): Boolean {
        val deleted = writableDatabase().delete(
            "activity_participants",
            "activity_id = ? AND user_id = ?",
            arrayOf(activityId, userId)
        )
        if (deleted > 0) {
            writableDatabase().execSQL(
                "UPDATE community_activities SET spots_taken = MAX(0, spots_taken - 1) WHERE id = ?",
                arrayOf(activityId)
            )
            return true
        }
        return false
    }

    fun deleteCommunityActivity(activityId: String): Boolean {
        writableDatabase().delete("activity_participants", "activity_id = ?", arrayOf(activityId))
        val rows = writableDatabase().delete("community_activities", "id = ?", arrayOf(activityId))
        return rows > 0
    }

    @Suppress("UNUSED_PARAMETER")
    fun seedDefaultCommunityActivitiesIfEmpty(userId: String) {
        val count = try {
            val cursor = readableDatabase().rawQuery("SELECT COUNT(*) FROM community_activities", null)
            cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
        } catch (e: Exception) {
            0
        }
        if (count == 0) {
            val initial = listOf(
                CommunityActivityItem(
                    id = "act_fifo_01",
                    title = "Taller de Pintura y Acuarela Botánica",
                    description = "Un espacio relajado para pintar flores y paisajes en acuarela. Fifo proveerá los materiales y una guía paso a paso para todos los niveles.",
                    spotsLabel = "Quedan 8 cupos",
                    spotsMax = 12,
                    spotsTaken = 4,
                    dateLabel = "Jueves 16 oct · 16:30",
                    locationName = "Centro Comunitario El Roble, Sala 3",
                    distanceLabel = "A 450 m",
                    iconCategory = "palette",
                    organizerId = "fifo_official",
                    organizerName = "Fifo",
                    organizerType = "fifo",
                    targetTastes = listOf("Pintura y arte", "Jardinería y plantas", "Manualidades"),
                    isJoined = false
                ),
                CommunityActivityItem(
                    id = "act_comm_02",
                    title = "Intercambio de Recetas y Dulces Caseros",
                    description = "Nos reuniremos a compartir secretos culinarios tradicionales: masas, mermeladas y postres de antaño con una taza de té caliente.",
                    spotsLabel = "Quedan 8 cupos",
                    spotsMax = 15,
                    spotsTaken = 7,
                    dateLabel = "Sábado 18 oct · 17:00",
                    locationName = "Café & Taller La Casona",
                    distanceLabel = "A 1.1 km",
                    iconCategory = "coffee",
                    organizerId = "org_casona",
                    organizerName = "Club Gastronómico Vecinal",
                    organizerType = "community_group",
                    targetTastes = listOf("Cocina tradicional", "Historias de vida", "Café y tertulias"),
                    isJoined = false
                ),
                CommunityActivityItem(
                    id = "act_user_03",
                    title = "Caminata y Tertulia en el Parque Botánico",
                    description = "Caminata suave de 30 minutos a paso conversado por los senderos arbolados, terminando con una charla bajo los sauces.",
                    spotsLabel = "Quedan 5 cupos",
                    spotsMax = 8,
                    spotsTaken = 3,
                    dateLabel = "Domingo 19 oct · 10:30",
                    locationName = "Acceso Principal Parque Botánico",
                    distanceLabel = "A 600 m",
                    iconCategory = "nature",
                    organizerId = "usr_carmen_g",
                    organizerName = "Carmen González",
                    organizerType = "user",
                    targetTastes = listOf("Paseos al aire libre", "Naturaleza y botánica", "Fotografía"),
                    isJoined = false
                ),
                CommunityActivityItem(
                    id = "act_comp_04",
                    title = "Tarde de Boleros y Música Clásica en Vivo",
                    description = "Ensamble de cuerdas en vivo interpretando clásicos de Lucho Gatica, Violeta Parra y melodías sinfónicas al atardecer.",
                    spotsLabel = "Quedan 9 cupos",
                    spotsMax = 20,
                    spotsTaken = 11,
                    dateLabel = "Miércoles 22 oct · 18:00",
                    locationName = "Teatro Municipal de Ñuñoa",
                    distanceLabel = "A 1.8 km",
                    iconCategory = "music",
                    organizerId = "org_teatro_mun",
                    organizerName = "Corporación Cultural Municipal",
                    organizerType = "company",
                    targetTastes = listOf("Música clásica", "Boleros y tango", "Teatro y cultura"),
                    isJoined = false
                )
            )
            initial.forEach { addCommunityActivity(it) }
        }
    }
}
