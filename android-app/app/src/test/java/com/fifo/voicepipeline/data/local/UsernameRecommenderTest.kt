package com.fifo.voicepipeline.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsernameRecommenderTest {

    @Test
    fun `genera cinco handles unicos y normalizados`() {
        val result = UsernameRecommender.recommend(
            fullName = "María José Valdés",
            email = "mj.valdes@example.com",
            batchIndex = 0,
            isTaken = { false },
            currentYear = 26
        )

        assertEquals(5, result.size)
        assertEquals(result.size, result.distinct().size)
        assertTrue(result.contains("@maria_jose"))
        result.forEach { handle ->
            assertTrue(handle.startsWith("@"))
            val clean = handle.removePrefix("@")
            assertTrue(clean.length in 3..20)
            assertFalse(clean.contains("á"))
            assertFalse(clean.contains("é"))
        }
    }

    @Test
    fun `omite candidatos que ya estan tomados`() {
        val taken = setOf("maria_jose", "maria.jose", "mariajose")

        val result = UsernameRecommender.recommend(
            fullName = "María José",
            email = null,
            batchIndex = 0,
            isTaken = { it in taken },
            currentYear = 26
        )

        assertEquals(5, result.size)
        assertTrue(result.none { it.removePrefix("@") in taken })
    }

    @Test
    fun `devuelve vacio cuando todos los candidatos estan tomados`() {
        val result = UsernameRecommender.recommend(
            fullName = "María José",
            email = "mj@example.com",
            batchIndex = 0,
            isTaken = { true },
            currentYear = 26
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `batchIndex cambia las sugerencias`() {
        val first = UsernameRecommender.recommend("María José Valdés", "mj.valdes@example.com", 0, { false }, 26)
        val second = UsernameRecommender.recommend("María José Valdés", "mj.valdes@example.com", 1, { false }, 26)

        assertNotEquals(first, second)
    }

    @Test
    fun `usa el correo cuando el nombre es generico`() {
        val result = UsernameRecommender.recommend(
            fullName = "Usuario",
            email = "carmen.gloria@example.com",
            batchIndex = 0,
            isTaken = { false },
            currentYear = 26
        )

        assertTrue(result.isNotEmpty())
        assertTrue(result.all { it.contains("carmen") })
    }
}
