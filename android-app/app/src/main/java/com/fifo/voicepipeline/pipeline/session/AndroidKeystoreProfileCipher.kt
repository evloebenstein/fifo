package com.fifo.voicepipeline.pipeline.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cifrado AES-256-GCM con una clave del Android Keystore (ADR-0048).
 *
 * La clave nunca sale del almacén del sistema y no se deriva de una contraseña.
 * El IV aleatorio viaja delante del texto cifrado; GCM autentica el contenido,
 * así que un blob manipulado falla al descifrar en vez de producir perfiles
 * basura.
 *
 * Requiere API 23+ (el proyecto tiene `minSdk 26`). No usa dependencias nuevas.
 */
class AndroidKeystoreProfileCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) : ProfileCipher {

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        require(iv.size in 1..MAX_IV_BYTES) { "IV fuera de rango: ${iv.size}" }
        val encrypted = cipher.doFinal(plaintext)
        return ByteArrayOutputStream(2 + iv.size + encrypted.size).use { out ->
            out.write(PAYLOAD_VERSION.toInt())
            out.write(iv.size)
            out.write(iv)
            out.write(encrypted)
            out.toByteArray()
        }
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size > 2) { "blob cifrado demasiado corto" }
        require(ciphertext[0] == PAYLOAD_VERSION) { "versión de cifrado no soportada" }
        val ivSize = ciphertext[1].toInt() and 0xFF
        require(ivSize in 1..MAX_IV_BYTES && ciphertext.size > 2 + ivSize) { "IV inválido" }

        val iv = ciphertext.copyOfRange(2, 2 + ivSize)
        val encrypted = ciphertext.copyOfRange(2 + ivSize, ciphertext.size)
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            doFinal(encrypted)
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val DEFAULT_KEY_ALIAS = "fifo_voice_profile_key_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val GCM_TAG_BITS = 128
        const val MAX_IV_BYTES = 32
        const val PAYLOAD_VERSION: Byte = 1
    }
}

/**
 * Almacén de perfiles de producción: archivo privado + Keystore + consentimiento.
 *
 * Se deja como función de composición para que `VoiceSessionPipeline` decida
 * cuándo construirla. Hoy no se cablea porque no hay extractor real ni UI de
 * consentimiento; `NoopSpeakerIdentityProvider` sigue por defecto (ADR-0048).
 */
fun androidEncryptedVoiceProfileStore(
    filesDir: File,
    hasConsent: () -> Boolean
): VoiceProfileStore = ConsentAwareVoiceProfileStore(
    delegate = EncryptedVoiceProfileStore(
        blobs = FileProfileBlobStore(File(filesDir, "speaker/voice_profiles.enc")),
        cipher = AndroidKeystoreProfileCipher()
    ),
    hasConsent = hasConsent
)
