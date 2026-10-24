package com.fifo.voicepipeline.data.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneHintExtractorTest {

    @Test
    fun `un celular chileno con formato se normaliza`() {
        val phone = PhoneHintExtractor.extract("Mi celular es +56 9 1234 5678.")
        assertEquals("+56912345678", phone)
    }

    @Test
    fun `un movil local con palabra clave se normaliza`() {
        val phone = PhoneHintExtractor.extract("Anótalo, mi número es 9 8765 4321.")
        assertEquals("+56987654321", phone)
    }

    @Test
    fun `un numero internacional sin palabra clave se acepta`() {
        val phone = PhoneHintExtractor.extract("Lucas +56 9 1111 2222")
        assertEquals("+56911112222", phone)
    }

    @Test
    fun `un numero con guiones y parentesis se normaliza`() {
        val phone = PhoneHintExtractor.extract("teléfono (56) 9-1234-5678")
        assertEquals("+56912345678", phone)
    }

    @Test
    fun `un numero internacional con 00 se normaliza`() {
        val phone = PhoneHintExtractor.extract("teléfono 0056 9 1234 5678")
        assertEquals("+56912345678", phone)
    }

    @Test
    fun `whatsapp tambien es una palabra clave`() {
        val phone = PhoneHintExtractor.extract("Escríbele por WhatsApp 9 1234 5678")
        assertEquals("+56912345678", phone)
    }

    @Test
    fun `no reemplaza un telefono conocido`() {
        val phone = PhoneHintExtractor.extract(
            snippet = "Su nuevo teléfono es 9 8765 4321.",
            currentPhone = "+56911112222"
        )
        assertNull(phone)
    }

    @Test
    fun `ignora numeros cortos`() {
        assertNull(PhoneHintExtractor.extract("Tiene 42 años y 3 nietos."))
    }

    @Test
    fun `ignora anios sueltos`() {
        assertNull(PhoneHintExtractor.extract("El número de la casa es 2024."))
    }

    @Test
    fun `ignora fechas compactas`() {
        assertNull(PhoneHintExtractor.extract("teléfono 20241023"))
    }

    @Test
    fun `ignora digitos repetidos`() {
        assertNull(PhoneHintExtractor.extract("teléfono 00000000"))
    }

    @Test
    fun `ignora numeros sin palabra clave`() {
        assertNull(PhoneHintExtractor.extract("Lucas vive en el 1234 de la calle 5678."))
    }

    @Test
    fun `ignora numeros demasiado largos`() {
        assertNull(PhoneHintExtractor.extract("teléfono 1234567890123456"))
    }

    @Test
    fun `un snippet vacio no produce indicio`() {
        assertNull(PhoneHintExtractor.extract("   "))
    }
}
