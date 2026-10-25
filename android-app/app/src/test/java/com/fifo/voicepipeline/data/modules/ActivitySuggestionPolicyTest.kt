package com.fifo.voicepipeline.data.modules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivitySuggestionPolicyTest {

    private fun draft(
        idea: String,
        tastes: List<String> = emptyList(),
        organizerId: String = "user_ana",
        organizerName: String = "Ana Pérez"
    ) = ActivitySuggestionPolicy.draft(idea, tastes, organizerId, organizerName)

    @Test
    fun `una idea de pintura usa la categoria y el lugar de arte`() {
        val activity = draft("taller de pintura y dibujo")

        assertEquals("palette", activity.iconCategory)
        assertEquals("Centro Cultural o Plaza de las Esculturas", activity.locationName)
        assertEquals("Taller de pintura y dibujo", activity.title)
        assertTrue(activity.targetTastes.contains("Pintura y arte"))
    }

    @Test
    fun `una idea de cocina usa la categoria gastronomica`() {
        val activity = draft("compartir recetas de pan amasado")

        assertEquals("coffee", activity.iconCategory)
        assertEquals("Café vecinal o rincón del té", activity.locationName)
        assertTrue(activity.targetTastes.contains("Cocina tradicional"))
    }

    @Test
    fun `una idea de caminata o jardin usa la categoria de naturaleza`() {
        val porCaminata = draft("caminata por el parque")
        val porJardin = draft("jardinería en la plaza")

        assertEquals("nature", porCaminata.iconCategory)
        assertEquals("nature", porJardin.iconCategory)
        assertEquals("Acceso principal del Parque Central", porCaminata.locationName)
        assertTrue(porCaminata.targetTastes.contains("Paseos al aire libre"))
    }

    @Test
    fun `una idea musical usa la categoria de musica`() {
        val activity = draft("tarde de boleros y guitarra")

        assertEquals("music", activity.iconCategory)
        assertEquals("Sala de música del centro comunitario", activity.locationName)
        assertTrue(activity.targetTastes.contains("Música clásica"))
    }

    @Test
    fun `una idea de lectura usa la categoria de libros`() {
        val activity = draft("club de lectura sobre historias y biblioteca")

        assertEquals("book", activity.iconCategory)
        assertEquals("Biblioteca vecinal", activity.locationName)
        assertTrue(activity.targetTastes.contains("Historias de vida"))
    }

    @Test
    fun `una idea sin palabras conocidas cae al encuentro vecinal`() {
        val activity = draft("hola")

        assertEquals("nature", activity.iconCategory)
        assertEquals("Encuentro vecinal para compartir y conversar", activity.title)
        assertEquals("Café o parque cercano", activity.locationName)
    }

    @Test
    fun `sin gustos detectados usa los dos primeros gustos del perfil`() {
        val activity = draft(
            idea = "conversar sobre el barrio",
            tastes = listOf("Tejido", "Ajedrez", "Cine")
        )

        assertEquals(listOf("Tejido", "Ajedrez"), activity.targetTastes)
    }

    @Test
    fun `los gustos detectados en la idea tienen prioridad sobre el perfil`() {
        val activity = draft(
            idea = "taller de pintura",
            tastes = listOf("Tejido", "Ajedrez")
        )

        assertEquals(listOf("Pintura y arte"), activity.targetTastes)
    }

    @Test
    fun `el titulo usa la idea entre ocho y cincuenta y cinco caracteres`() {
        val minimo = draft("abcdefgh")
        val maximo = draft("a".repeat(55))
        val demasiadoLargo = draft("a".repeat(56))

        assertEquals("Abcdefgh", minimo.title)
        assertEquals("A" + "a".repeat(54), maximo.title)
        assertEquals("Encuentro vecinal para compartir y conversar", demasiadoLargo.title)
    }

    @Test
    fun `el titulo recorta espacios antes de medir`() {
        val activity = draft("   taller de teatro   ")

        assertEquals("Taller de teatro", activity.title)
    }

    @Test
    fun `la idea larga se cita en la descripcion`() {
        val activity = draft("caminata por el parque con vecinos")

        assertTrue(activity.description.startsWith("Fifo organizó esta propuesta: caminata por el parque con vecinos."))
    }

    @Test
    fun `la idea corta usa la descripcion generica`() {
        val activity = draft("té")

        assertEquals(
            "Reunión comunitaria guiada por Fifo para disfrutar de una grata conversación y realizar actividades amenas en conjunto.",
            activity.description
        )
    }

    @Test
    fun `el organizador conserva identidad y usa Vecino si no hay nombre`() {
        val conNombre = draft("té", organizerId = "user_ana", organizerName = "Ana Pérez")
        val sinNombre = draft("té", organizerId = "user_ana", organizerName = "   ")

        assertEquals("user_ana", conNombre.organizerId)
        assertEquals("Ana Pérez", conNombre.organizerName)
        assertEquals("Vecino", sinNombre.organizerName)
        assertEquals("user", sinNombre.organizerType)
    }

    @Test
    fun `el borrador conserva cupos fecha y estado de union`() {
        val activity = draft("té")

        assertEquals("draft_suggested", activity.id)
        assertEquals("Quedan 7 cupos", activity.spotsLabel)
        assertEquals(8, activity.spotsMax)
        assertEquals(1, activity.spotsTaken)
        assertEquals("Próximo sábado · 16:00", activity.dateLabel)
        assertEquals("A 500 m", activity.distanceLabel)
        assertTrue(activity.isJoined)
        assertFalse(activity.title.isBlank())
    }
}
