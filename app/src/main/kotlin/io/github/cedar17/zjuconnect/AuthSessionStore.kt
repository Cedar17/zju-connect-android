package io.github.cedar17.zjuconnect

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class EncryptedAuthSession(
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

internal object AuthSessionEnvelopeCodec {
    private const val MAGIC = 0x5A4A4153 // ZJAS
    private const val VERSION = 1
    private const val MIN_IV_BYTES = 12
    private const val MAX_IV_BYTES = 32
    private const val MIN_CIPHERTEXT_BYTES = 16
    private const val MAX_CIPHERTEXT_BYTES = 64 * 1024 + 16
    private const val MAX_ENVELOPE_BYTES = 128 * 1024

    fun encode(session: EncryptedAuthSession): ByteArray {
        require(session.iv.size in MIN_IV_BYTES..MAX_IV_BYTES) { "Invalid authentication-session IV" }
        require(session.ciphertext.size in MIN_CIPHERTEXT_BYTES..MAX_CIPHERTEXT_BYTES) {
            "Invalid authentication-session ciphertext"
        }
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeInt(session.iv.size)
                output.writeInt(session.ciphertext.size)
                output.write(session.iv)
                output.write(session.ciphertext)
            }
            bytes.toByteArray()
        }
    }

    fun decode(envelope: ByteArray): EncryptedAuthSession {
        require(envelope.size in 1..MAX_ENVELOPE_BYTES) { "Invalid authentication-session envelope size" }
        return try {
            DataInputStream(ByteArrayInputStream(envelope)).use { input ->
                require(input.readInt() == MAGIC) { "Invalid authentication-session envelope" }
                require(input.readInt() == VERSION) { "Unsupported authentication-session envelope" }
                val ivSize = input.readInt()
                val ciphertextSize = input.readInt()
                require(ivSize in MIN_IV_BYTES..MAX_IV_BYTES) { "Invalid authentication-session IV size" }
                require(ciphertextSize in MIN_CIPHERTEXT_BYTES..MAX_CIPHERTEXT_BYTES) {
                    "Invalid authentication-session ciphertext size"
                }
                require(ivSize + ciphertextSize == input.available()) { "Truncated authentication-session envelope" }
                EncryptedAuthSession(
                    iv = ByteArray(ivSize).also(input::readFully),
                    ciphertext = ByteArray(ciphertextSize).also(input::readFully),
                )
            }
        } catch (error: IOException) {
            throw IllegalArgumentException("Invalid authentication-session envelope", error)
        }
    }
}

internal class InvalidStoredAuthenticationSession(cause: Throwable) :
    Exception("Stored authentication session cannot be decrypted", cause)

internal class AuthSessionStore(context: Context) {
    private val sessionFile = AtomicFile(context.noBackupFilesDir.resolve(FILE_NAME))

    fun read(): ByteArray? {
        if (!sessionFile.baseFile.isFile) {
            return null
        }
        val envelopeBytes = sessionFile.readFully()
        var envelope: EncryptedAuthSession? = null
        return try {
            val decoded = AuthSessionEnvelopeCodec.decode(envelopeBytes)
            envelope = decoded
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, decoded.iv))
            cipher.updateAAD(AAD)
            cipher.doFinal(decoded.ciphertext)
        } catch (error: GeneralSecurityException) {
            resetCorruptState()
            throw InvalidStoredAuthenticationSession(error)
        } catch (error: IllegalArgumentException) {
            resetCorruptState()
            throw InvalidStoredAuthenticationSession(error)
        } finally {
            envelope?.iv?.fill(0)
            envelope?.ciphertext?.fill(0)
            envelopeBytes.fill(0)
        }
    }

    fun write(snapshot: ByteArray) {
        require(snapshot.isNotEmpty()) { "Authentication snapshot is empty" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(AAD)
        val ciphertext = cipher.doFinal(snapshot)
        val envelope = try {
            AuthSessionEnvelopeCodec.encode(
                EncryptedAuthSession(iv = cipher.iv, ciphertext = ciphertext),
            )
        } finally {
            ciphertext.fill(0)
        }

        try {
            val output = sessionFile.startWrite()
            try {
                output.write(envelope)
                sessionFile.finishWrite(output)
            } catch (error: Exception) {
                sessionFile.failWrite(output)
                throw error
            }
        } finally {
            envelope.fill(0)
        }
    }

    fun clear(): Boolean {
        sessionFile.delete()
        return !sessionFile.baseFile.exists()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun resetCorruptState() {
        sessionFile.delete()
        runCatching {
            KeyStore.getInstance(KEYSTORE_PROVIDER).apply {
                load(null)
                deleteEntry(KEY_ALIAS)
            }
        }
    }

    private companion object {
        const val FILE_NAME = "atrust_auth_session.bin"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "io.github.cedar17.zjuconnect.atrust-auth-session.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        val AAD: ByteArray =
            "io.github.cedar17.zjuconnect/atrust-auth-session/v1".toByteArray(Charsets.UTF_8)
    }
}
