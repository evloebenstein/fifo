package com.fifo.voicepipeline.data.contacts

import com.fifo.voicepipeline.ui.model.RelationalContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ContactMatcherTest {

    private fun contact(
        id: String,
        name: String,
        role: String = "Contacto del teléfono",
        alias: String? = null,
        closeness: Float = 0.5f
    ) = RelationalContact(
        id = id,
        contactName = name,
        relationshipRole = role,
        alias = alias,
        closenessScore = closeness
    )

    @Test
    fun `encuentra por nombre exacto sin distinguir mayusculas ni acentos`() {
        val rosa = contact("ctc_rosa", "Rosa", "Vecina")

        assertSame(rosa, ContactMatcher.find("  ROSA  ", listOf(rosa)))
    }

    @Test
    fun `encuentra por rol exacto con acentos`() {
        val doctora = contact("ctc_doctora", "Dra. Pérez", "Médico")

        assertSame(doctora, ContactMatcher.find("medico", listOf(doctora)))
    }

    @Test
    fun `encuentra por apodo exacto`() {
        val carmen = contact("ctc_carmen", "Carmen", "Hija", alias = "Mamí")

        assertSame(carmen, ContactMatcher.find("mami", listOf(carmen)))
    }

    @Test
    fun `encuentra por apodo parcial`() {
        val carmen = contact("ctc_carmen", "Carmen", "Hija", alias = "Mamita")

        assertSame(carmen, ContactMatcher.find("mami", listOf(carmen)))
    }

    @Test
    fun `encuentra por nombre parcial`() {
        val patricia = contact("ctc_patricia", "Patricia", "Hija", closeness = 0.9f)

        assertSame(patricia, ContactMatcher.find("patri", listOf(patricia)))
    }

    @Test
    fun `encuentra por tokens aunque el orden cambie`() {
        val mariaJose = contact("ctc_maria", "María José", "Vecina")

        assertSame(mariaJose, ContactMatcher.find("jose maria", listOf(mariaJose)))
    }

    @Test
    fun `una coincidencia exacta gana sobre una parcial mas cercana`() {
        val parcial = contact("ctc_parcial", "Hija Lucía", "Amiga", closeness = 0.99f)
        val exacta = contact("ctc_exacta", "Carmen", "Hija", closeness = 0.1f)

        assertSame(exacta, ContactMatcher.find("hija", listOf(parcial, exacta)))
    }

    @Test
    fun `entre coincidencias parciales gana la mayor cercania`() {
        val vecina = contact("ctc_vecina", "Patricia Vecina", "Vecina", closeness = 0.4f)
        val hija = contact("ctc_hija", "Patricia", "Hija", closeness = 0.9f)

        assertSame(hija, ContactMatcher.find("patri", listOf(vecina, hija)))
    }

    @Test
    fun `una consulta vacia nunca resuelve un contacto`() {
        val rosa = contact("ctc_rosa", "Rosa", "Vecina")

        assertNull(ContactMatcher.find("", listOf(rosa)))
        assertNull(ContactMatcher.find("   \t ", listOf(rosa)))
    }

    @Test
    fun `devuelve null cuando no hay coincidencias`() {
        val rosa = contact("ctc_rosa", "Rosa", "Vecina")

        assertNull(ContactMatcher.find("astronomía telescopio", listOf(rosa)))
    }

    @Test
    fun `no confunde un nombre con otro por acentos`() {
        val jose = contact("ctc_jose", "José", "Hermano")

        assertEquals("José", ContactMatcher.find("jose", listOf(jose))?.contactName)
        assertEquals("José", ContactMatcher.find("José", listOf(jose))?.contactName)
    }
}
