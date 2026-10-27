package com.fifo.voicepipeline.pipeline.session

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Contrato del almacén cifrado de perfiles (ADR-0048).
 *
 * Fija lo que no puede fallar: el blob no expone ids ni embeddings, un blob
 * corrupto se trata como "no hay perfiles" en vez de romper el turno, borrar
 * siempre funciona y sin consentimiento no se lee ni se enrola.
 */
class EncryptedVoiceProfileStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun profile(
        id: String = "owner",
        name: String = "Ana",
        embedding: FloatArray = floatArrayOf(0.5f, -0.25f, 0.75f),
        modelId: String = "fake-v1"
    ) = VoiceProfile(
        id = id,
        displayName = name,
        embedding = embedding,
        modelId = modelId,
        enrolledAtMillis = 1_000L,
        sampleCount = 3
    )

    @Test
    fun `guarda y recupera perfiles con embeddings exactos`() = runTest {
        val blobs = InMemoryBlobStore()
        val store = EncryptedVoiceProfileStore(blobs, XorCipher())

        store.upsert(profile())
        store.upsert(profile(id = "friend", name = "María"))

        val loaded = store.loadAll().sortedBy { it.id }
        assertEquals(2, loaded.size)
        assertEquals(profile(), loaded.first { it.id == "owner" })
        assertEquals("María", loaded.first { it.id == "friend" }.displayName)
    }

    @Test
    fun `el blob guardado no contiene ids ni embeddings en claro`() = runTest {
        val blobs = InMemoryBlobStore()
        val store = EncryptedVoiceProfileStore(blobs, XorCipher())
        store.upsert(profile(id = "owner-secret", name = "Ana Privada"))

        val payload = blobs.payload!!
        assertFalse(
            "el id no debe aparecer en claro",
            payload.containsAscii("owner-secret")
        )
        assertFalse(
            "el nombre no debe aparecer en claro",
            payload.containsAscii("Ana Privada")
        )
        assertFalse(
            "el modelId no debe aparecer en claro",
            payload.containsAscii("fake-v1")
        )
    }

    @Test
    fun `una clave distinta no produce perfiles`() = runTest {
        val blobs = InMemoryBlobStore()
        EncryptedVoiceProfileStore(blobs, XorCipher(key = 0x11)).upsert(profile())

        val other = EncryptedVoiceProfileStore(blobs, XorCipher(key = 0x22))
        assertTrue(other.loadAll().isEmpty())
    }

    @Test
    fun `un blob corrupto se trata como vacio y se puede recuperar`() = runTest {
        val blobs = InMemoryBlobStore(payload = byteArrayOf(1, 2, 3, 4, 5))
        val store = EncryptedVoiceProfileStore(blobs, XorCipher())

        assertTrue(store.loadAll().isEmpty())

        store.upsert(profile())
        assertEquals(listOf(profile()), store.loadAll())
    }

    @Test
    fun `un contador de perfiles absurdo no rompe la lectura`() = runTest {
        val plaintext = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use {
                it.writeInt(0x46565031)
                it.writeInt(1)
                it.writeInt(9_999)
            }
        }.toByteArray()
        val blobs = InMemoryBlobStore(payload = XorCipher().encrypt(plaintext))
        val store = EncryptedVoiceProfileStore(blobs, XorCipher())

        assertTrue(store.loadAll().isEmpty())
    }

    @Test
    fun `borrar un perfil y vaciar el almacen funcionan`() = runTest {
        val blobs = InMemoryBlobStore()
        val store = EncryptedVoiceProfileStore(blobs, XorCipher())
        store.upsert(profile())
        store.upsert(profile(id = "friend", name = "María"))

        store.delete("owner")
        assertEquals(listOf("friend"), store.loadAll().map { it.id })

        store.clear()
        assertTrue(store.loadAll().isEmpty())
        assertNull(blobs.payload)
    }

    @Test
    fun `el archivo persiste entre instancias y se borra con clear`() = runTest {
        val folder = temporaryFolder.newFolder("speaker")
        val file = File(folder, "voice_profiles.enc")
        val firstName = EncryptedVoiceProfileStore(FileProfileBlobStore(file), XorCipher())
        firstName.upsert(profile())

        assertTrue("el archivo debe existir tras guardar", file.isFile)

        val second = EncryptedVoiceProfileStore(FileProfileBlobStore(file), XorCipher())
        assertEquals(listOf(profile()), second.loadAll())

        second.clear()
        assertFalse("clear debe eliminar el archivo", file.exists())
    }

    @Test
    fun `sin consentimiento no se lee ni se enrola pero si se borra`() = runTest {
        val backing = EncryptedVoiceProfileStore(InMemoryBlobStore(), XorCipher())
        var consent = false
        val store = ConsentAwareVoiceProfileStore(backing) { consent }

        val rejected = runCatching { store.upsert(profile()) }.exceptionOrNull()
        assertTrue(rejected is VoiceProfileConsentRequiredException)
        assertTrue(store.loadAll().isEmpty())

        consent = true
        store.upsert(profile())
        assertEquals(listOf(profile()), store.loadAll())

        consent = false
        assertTrue("sin consentimiento tampoco se lee", store.loadAll().isEmpty())
        store.delete("owner")
        consent = true
        assertTrue("el borrado debe haber llegado al almacén", store.loadAll().isEmpty())
    }

    @Test
    fun `clear sigue permitido tras retirar el consentimiento`() = runTest {
        val backing = EncryptedVoiceProfileStore(InMemoryBlobStore(), XorCipher())
        var consent = true
        val store = ConsentAwareVoiceProfileStore(backing) { consent }
        store.upsert(profile())

        consent = false
        store.clear()

        consent = true
        assertTrue(store.loadAll().isEmpty())
    }

    private class InMemoryBlobStore(var payload: ByteArray? = null) : ProfileBlobStore {
        override fun read(): ByteArray? = payload
        override fun write(bytes: ByteArray) {
            payload = bytes.copyOf()
        }

        override fun clear() {
            payload = null
        }
    }

    private class XorCipher(private val key: Byte = 0x5A) : ProfileCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray =
            ByteArray(plaintext.size) { index ->
                (plaintext[index].toInt() xor key.toInt()).toByte()
            }

        override fun decrypt(ciphertext: ByteArray): ByteArray = encrypt(ciphertext)
    }

    private fun ByteArray.containsAscii(text: String): Boolean {
        val needle = text.toByteArray(Charsets.UTF_8)
        if (needle.isEmpty() || needle.size > size) return false
        for (start in 0..(size - needle.size)) {
            if (needle.indices.all { this[start + it] == needle[it] }) return true
        }
        return false
    }
}
