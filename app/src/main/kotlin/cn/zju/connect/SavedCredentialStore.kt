package cn.zju.connect

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

internal data class StoredCredential(
    val username: String,
    val password: String,
)

internal object StoredCredentialCodec {
    private const val MAGIC = 0x5A4A4352 // ZJCR
    private const val VERSION = 1
    private const val MAX_USERNAME_BYTES = 1024
    private const val MAX_PASSWORD_BYTES = 16 * 1024

    fun encode(credential: StoredCredential): ByteArray {
        val username = credential.username.toByteArray(Charsets.UTF_8)
        val password = credential.password.toByteArray(Charsets.UTF_8)
        require(username.isNotEmpty() && username.size <= MAX_USERNAME_BYTES) { "Invalid stored username" }
        require(password.isNotEmpty() && password.size <= MAX_PASSWORD_BYTES) { "Invalid stored password" }
        return try {
            ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(VERSION)
                    output.writeInt(username.size)
                    output.writeInt(password.size)
                    output.write(username)
                    output.write(password)
                }
                bytes.toByteArray()
            }
        } finally {
            username.fill(0)
            password.fill(0)
        }
    }

    fun decode(encoded: ByteArray): StoredCredential = try {
        DataInputStream(ByteArrayInputStream(encoded)).use { input ->
            require(input.readInt() == MAGIC) { "Invalid stored credential" }
            require(input.readInt() == VERSION) { "Unsupported stored credential" }
            val usernameSize = input.readInt()
            val passwordSize = input.readInt()
            require(usernameSize in 1..MAX_USERNAME_BYTES) { "Invalid stored username size" }
            require(passwordSize in 1..MAX_PASSWORD_BYTES) { "Invalid stored password size" }
            require(usernameSize + passwordSize == input.available()) { "Truncated stored credential" }
            val username = ByteArray(usernameSize).also(input::readFully)
            val password = ByteArray(passwordSize).also(input::readFully)
            try {
                StoredCredential(
                    username = username.toString(Charsets.UTF_8),
                    password = password.toString(Charsets.UTF_8),
                )
            } finally {
                username.fill(0)
                password.fill(0)
            }
        }
    } catch (error: IOException) {
        throw IllegalArgumentException("Invalid stored credential", error)
    }
}

internal class InvalidStoredCredential(cause: Throwable) :
    Exception("Stored credential cannot be decrypted", cause)

internal object StoredCredentialEncryption {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private val AAD: ByteArray = "cn.zju.connect/atrust-saved-credential/v1".toByteArray(Charsets.UTF_8)

    fun encrypt(plaintext: ByteArray, key: SecretKey): EncryptedAuthSession {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(AAD)
        return EncryptedAuthSession(iv = cipher.iv, ciphertext = cipher.doFinal(plaintext))
    }

    fun decrypt(encrypted: EncryptedAuthSession, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, encrypted.iv))
        cipher.updateAAD(AAD)
        return cipher.doFinal(encrypted.ciphertext)
    }
}

internal class SavedCredentialStore(context: Context) {
    private val credentialFile = AtomicFile(context.noBackupFilesDir.resolve(FILE_NAME))

    fun read(): StoredCredential? {
        if (!credentialFile.baseFile.isFile) return null
        val envelopeBytes = credentialFile.readFully()
        var envelope: EncryptedAuthSession? = null
        var plaintext: ByteArray? = null
        return try {
            val decoded = AuthSessionEnvelopeCodec.decode(envelopeBytes)
            envelope = decoded
            plaintext = StoredCredentialEncryption.decrypt(decoded, getOrCreateKey())
            StoredCredentialCodec.decode(plaintext)
        } catch (error: GeneralSecurityException) {
            resetCorruptState()
            throw InvalidStoredCredential(error)
        } catch (error: IllegalArgumentException) {
            resetCorruptState()
            throw InvalidStoredCredential(error)
        } finally {
            plaintext?.fill(0)
            envelope?.iv?.fill(0)
            envelope?.ciphertext?.fill(0)
            envelopeBytes.fill(0)
        }
    }

    fun write(credential: StoredCredential) {
        val plaintext = StoredCredentialCodec.encode(credential)
        val encrypted = try {
            StoredCredentialEncryption.encrypt(plaintext, getOrCreateKey())
        } finally {
            plaintext.fill(0)
        }
        val envelope = try {
            AuthSessionEnvelopeCodec.encode(encrypted)
        } finally {
            encrypted.iv.fill(0)
            encrypted.ciphertext.fill(0)
        }
        try {
            val output = credentialFile.startWrite()
            try {
                output.write(envelope)
                credentialFile.finishWrite(output)
            } catch (error: Exception) {
                credentialFile.failWrite(output)
                throw error
            }
        } finally {
            envelope.fill(0)
        }
    }

    fun clear(): Boolean {
        credentialFile.delete()
        return !credentialFile.baseFile.exists()
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
        credentialFile.delete()
        runCatching {
            KeyStore.getInstance(KEYSTORE_PROVIDER).apply {
                load(null)
                deleteEntry(KEY_ALIAS)
            }
        }
    }

    private companion object {
        const val FILE_NAME = "atrust_saved_credential.bin"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "cn.zju.connect.atrust-saved-credential.v1"
    }
}
