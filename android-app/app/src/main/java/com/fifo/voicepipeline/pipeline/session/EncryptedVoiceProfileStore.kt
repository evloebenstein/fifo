package com.fifo.voicepipeline.pipeline.session

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Cifra autenticada de un blob de perfiles de voz (ADR-0048).
 *
 * La implementación de producción es [AndroidKeystoreProfileCipher]. La costura
 * existe para probar el almacén en JVM con un cifrador falso y para no atar el
 * formato a Android.
 */
interface ProfileCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
}

/** Persistencia opaca del blob ya cifrado. Nunca recibe texto plano. */
interface ProfileBlobStore {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
    fun clear()
}

/**
 * Blob en un archivo privado de la app.
 *
 * Escribe a un temporal y reemplaza de forma atómica: un cierre a mitad de
 * escritura deja el perfil anterior intacto en vez de un archivo truncado.
 */
class FileProfileBlobStore(private val file: File) : ProfileBlobStore {

    override fun read(): ByteArray? =
        if (file.isFile) file.readBytes() else null

    override fun write(bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeBytes(bytes)
        try {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override fun clear() {
        if (file.exists()) file.delete()
        File(file.parentFile, "${file.name}.tmp").delete()
    }
}

/**
 * Almacén cifrado de perfiles de voz (ADR-0048).
 *
 * Guarda todos los perfiles en un único blob cifrado y autenticado. El formato
 * interno está versionado; un blob corrupto, de otra versión o cifrado con otra
 * clave se trata como "no hay perfiles" y **nunca** rompe el turno.
 *
 * Solo contiene embeddings y metadatos: no hay audio crudo. El cifrado es
 * responsabilidad de [ProfileCipher]; el almacén no conoce Android Keystore.
 */
class EncryptedVoiceProfileStore(
    private val blobs: ProfileBlobStore,
    private val cipher: ProfileCipher
) : VoiceProfileStore {

    private val mutex = Mutex()

    override suspend fun loadAll(): List<VoiceProfile> = mutex.withLock { readUnlocked() }

    override suspend fun upsert(profile: VoiceProfile) = mutex.withLock {
        val profiles = readUnlocked().filterNot { it.id == profile.id } + profile
        writeUnlocked(profiles)
    }

    override suspend fun delete(profileId: String) = mutex.withLock {
        val profiles = readUnlocked()
        if (profiles.none { it.id == profileId }) return@withLock
        writeUnlocked(profiles.filterNot { it.id == profileId })
    }

    override suspend fun clear() = mutex.withLock {
        blobs.clear()
    }

    private fun readUnlocked(): List<VoiceProfile> {
        val payload = try {
            blobs.read() ?: return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }
        val plaintext = try {
            cipher.decrypt(payload)
        } catch (_: Exception) {
            return emptyList()
        }
        return try {
            decode(plaintext)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeUnlocked(profiles: List<VoiceProfile>) {
        blobs.write(cipher.encrypt(encode(profiles)))
    }

    private fun encode(profiles: List<VoiceProfile>): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeInt(profiles.size)
            for (profile in profiles) {
                data.writeString(profile.id)
                data.writeString(profile.displayName)
                data.writeString(profile.modelId)
                data.writeLong(profile.enrolledAtMillis)
                data.writeInt(profile.sampleCount)
                data.writeInt(profile.embedding.size)
                for (value in profile.embedding) data.writeFloat(value)
            }
        }
        return out.toByteArray()
    }

    private fun decode(plaintext: ByteArray): List<VoiceProfile> {
        DataInputStream(ByteArrayInputStream(plaintext)).use { data ->
            require(data.readInt() == MAGIC) { "blob de perfiles sin cabecera" }
            require(data.readInt() == VERSION) { "versión de perfiles no soportada" }
            val count = data.readInt()
            require(count in 0..MAX_PROFILES) { "cantidad de perfiles fuera de rango: $count" }
            return buildList(count) {
                repeat(count) {
                    val id = data.readString()
                    val displayName = data.readString()
                    val modelId = data.readString()
                    val enrolledAtMillis = data.readLong()
                    val sampleCount = data.readInt()
                    val dimension = data.readInt()
                    require(dimension in 1..MAX_EMBEDDING_DIMENSION) {
                        "dimensión de embedding fuera de rango: $dimension"
                    }
                    val embedding = FloatArray(dimension) { data.readFloat() }
                    add(
                        VoiceProfile(
                            id = id,
                            displayName = displayName,
                            embedding = embedding,
                            modelId = modelId,
                            enrolledAtMillis = enrolledAtMillis,
                            sampleCount = sampleCount
                        )
                    )
                }
            }
        }
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val size = readInt()
        require(size in 0..MAX_STRING_BYTES) { "cadena fuera de rango: $size" }
        val bytes = ByteArray(size)
        readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private companion object {
        const val MAGIC = 0x46565031 // "FVP1"
        const val VERSION = 1
        const val MAX_PROFILES = 64
        const val MAX_EMBEDDING_DIMENSION = 4_096
        const val MAX_STRING_BYTES = 4_096
    }
}

/** Se lanza al intentar enrolar sin consentimiento vigente. */
class VoiceProfileConsentRequiredException :
    IllegalStateException("El enrolamiento de voz requiere consentimiento explícito")

/**
 * Puerta de consentimiento sobre un [VoiceProfileStore] (ADR-0048).
 *
 * - Sin consentimiento no se leen perfiles ni se enrola: `loadAll()` devuelve
 *   vacío y `upsert()` falla con [VoiceProfileConsentRequiredException].
 * - Borrar **siempre** se permite, incluso tras retirar el consentimiento: la
 *   revocación debe poder eliminar el dato biométrico sin pedir permiso otra vez.
 */
class ConsentAwareVoiceProfileStore(
    private val delegate: VoiceProfileStore,
    private val hasConsent: () -> Boolean
) : VoiceProfileStore {

    override suspend fun loadAll(): List<VoiceProfile> =
        if (hasConsent()) delegate.loadAll() else emptyList()

    override suspend fun upsert(profile: VoiceProfile) {
        if (!hasConsent()) throw VoiceProfileConsentRequiredException()
        delegate.upsert(profile)
    }

    override suspend fun delete(profileId: String) = delegate.delete(profileId)

    override suspend fun clear() = delegate.clear()
}
