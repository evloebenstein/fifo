package com.fifo.voicepipeline.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprobaciones estructurales del esquema, sin SQLite real (el módulo no usa
 * Robolectric). Garantizan que crear, migrar y reparar no diverjan.
 */
class FifoSchemaTest {

    private val tablePattern = Regex("""CREATE TABLE IF NOT EXISTS\s+(\w+)""", RegexOption.IGNORE_CASE)

    @Test
    fun `la version de esquema es la esperada`() {
        assertEquals(9, FifoSchema.VERSION)
    }

    @Test
    fun `crear define cada tabla una sola vez y en orden`() {
        assertEquals(
            listOf(
                "users",
                "user_profiles",
                "tastes",
                "taste_stories",
                "social_posts",
                "reminders_and_events",
                "past_conversations",
                "memories",
                "device_locations",
                "conversation_fragments",
                "user_contacts_relational",
                "contact_appearances",
                "contact_phone_hints",
                "user_daily_routines",
                "contact_conversation_mentions",
                "community_activities",
                "activity_participants"
            ),
            tableNames(FifoSchema.createStatements)
        )
    }

    @Test
    fun `la reparacion de apertura cubre las tablas introducidas despues de v1`() {
        assertEquals(
            listOf(
                "conversation_fragments",
                "user_contacts_relational",
                "contact_appearances",
                "contact_phone_hints",
                "user_daily_routines"
            ),
            tableNames(FifoSchema.ensureStatements)
        )
        assertTrue(
            FifoSchema.ensureStatements.any {
                it.sql.contains("idx_contact_appearances_user_contact")
            }
        )
        assertTrue(
            FifoSchema.ensureStatements.any {
                it.sql.contains("idx_contact_phone_hints_user_contact")
            }
        )
    }

    @Test
    fun `actualizar desde v9 no ejecuta sentencias`() {
        assertTrue(FifoSchema.upgradeStatements(9).isEmpty())
    }

    @Test
    fun `actualizar desde v8 solo agrega los indicios telefonicos`() {
        assertEquals(
            listOf("contact_phone_hints"),
            tableNames(FifoSchema.upgradeStatements(8))
        )
    }

    @Test
    fun `actualizar desde v7 repara procedencia e indicios`() {
        assertEquals(
            listOf("contact_appearances", "contact_phone_hints"),
            tableNames(FifoSchema.upgradeStatements(7))
        )
    }

    @Test
    fun `actualizar desde v5 agrega actividades y las tablas de contacto posteriores`() {
        assertEquals(
            listOf(
                "community_activities",
                "activity_participants",
                "contact_appearances",
                "contact_phone_hints"
            ),
            tableNames(FifoSchema.upgradeStatements(5))
        )
    }

    @Test
    fun `actualizar desde v3 agrega las menciones y todo lo posterior`() {
        assertEquals(
            listOf(
                "contact_conversation_mentions",
                "community_activities",
                "activity_participants",
                "contact_appearances",
                "contact_phone_hints"
            ),
            tableNames(FifoSchema.upgradeStatements(3))
        )
    }

    @Test
    fun `actualizar desde v2 recrea contactos y rutinas y agrega lo posterior`() {
        assertEquals(
            listOf(
                "user_contacts_relational",
                "user_daily_routines",
                "contact_conversation_mentions",
                "community_activities",
                "activity_participants",
                "contact_appearances",
                "contact_phone_hints"
            ),
            tableNames(FifoSchema.upgradeStatements(2))
        )
    }

    @Test
    fun `actualizar desde v1 agrega fragmentos y todo lo posterior`() {
        assertEquals(
            listOf(
                "conversation_fragments",
                "user_contacts_relational",
                "user_daily_routines",
                "contact_conversation_mentions",
                "community_activities",
                "activity_participants",
                "contact_appearances",
                "contact_phone_hints"
            ),
            tableNames(FifoSchema.upgradeStatements(1))
        )
    }

    @Test
    fun `contactos relacionales conservan la columna vinculada en todas las rutas`() {
        val paths = buildList {
            addAll(FifoSchema.createStatements)
            addAll(FifoSchema.ensureStatements)
            addAll(FifoSchema.upgradeStatements(1))
        }

        val contactsDdl = paths.filter {
            tableName(it.sql) == "user_contacts_relational"
        }
        assertTrue(contactsDdl.isNotEmpty())
        contactsDdl.forEach { statement ->
            assertTrue(
                "user_contacts_relational debe incluir linked_conversation_ids",
                statement.sql.contains("linked_conversation_ids")
            )
        }

        // Una base v3 antigua no tiene la columna: la migración v4 debe añadirla.
        assertTrue(
            FifoSchema.upgradeStatements(3).any {
                it.sql.contains("ALTER TABLE user_contacts_relational") &&
                    it.sql.contains("linked_conversation_ids")
            }
        )
    }

    @Test
    fun `crear reparar y migrar comparten el mismo DDL por tabla`() {
        val created = ddlByTable(FifoSchema.createStatements)

        val paths = buildList {
            addAll(FifoSchema.ensureStatements)
            (1..8).forEach { addAll(FifoSchema.upgradeStatements(it)) }
        }
        paths.forEach { statement ->
            val table = tableName(statement.sql) ?: return@forEach
            assertEquals(
                "DDL divergente para $table",
                created[table],
                normalize(statement.sql)
            )
        }
    }

    @Test
    fun `las sentencias alter se marcan como tolerantes y el resto no`() {
        val all = buildList {
            addAll(FifoSchema.createStatements)
            addAll(FifoSchema.ensureStatements)
            (1..8).forEach { addAll(FifoSchema.upgradeStatements(it)) }
        }

        val alters = all.filter { it.sql.trim().startsWith("ALTER TABLE", ignoreCase = true) }
        assertTrue(alters.isNotEmpty())
        alters.forEach { assertTrue("ALTER debe ser tolerante: ${it.sql}", it.tolerant) }
        all.filterNot { it.tolerant }.forEach {
            assertFalse(
                "Solo los ALTER pueden ser tolerantes: ${it.sql}",
                it.sql.trim().startsWith("ALTER TABLE", ignoreCase = true)
            )
        }
    }

    private fun tableNames(statements: List<SchemaStatement>): List<String> =
        statements.mapNotNull { tableName(it.sql) }

    private fun tableName(sql: String): String? =
        tablePattern.find(sql)?.groupValues?.get(1)

    private fun ddlByTable(statements: List<SchemaStatement>): Map<String, String> =
        statements.mapNotNull { statement ->
            tableName(statement.sql)?.let { it to normalize(statement.sql) }
        }.toMap()

    private fun normalize(sql: String): String =
        sql.replace(Regex("\\s+"), " ").trim()
}
