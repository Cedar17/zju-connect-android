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

/**
 * A saved account credential plus the aTrust endpoints that have actually
 * accepted it ("host:port" strings). Sessions are server-bound, so a
 * credential only auto-submits against servers listed here; the first login
 * on any new server is always typed by the user.
 */
internal data class StoredCredential(
    val username: String,
    val password: String,
    val acceptedServers: List<String> = listOf(),
) {
    fun isAcceptedOn(serverKey: String): Boolean = serverKey in acceptedServers
}

/** Canonical per-server key used for credential scoping. */
internal fun serverKey(target: CustomServerTarget): String = "${target.host}:${target.port}"

internal object StoredCredentialCodec {
    private const val MAGIC = 0x5A4A4352 // ZJCR
    private const val VERSION = 2
    private const val MAX_USERNAME_BYTES = 1024
    private const val MAX_PASSWORD_BYTES = 16 * 1024
    private const val MAX_SERVER_BYTES = 256
    private const val MAX_SERVERS = 8
    const val MAX_ENTRIES = 10

    fun encode(credentials: List<StoredCredential>): ByteArray {
        require(credentials.isNotEmpty() && credentials.size <= MAX_ENTRIES) { "Invalid stored credential count" }
        return try {
            ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(VERSION)
                    output.writeInt(credentials.size)
                    for (credential in credentials) {
                        val username = credential.username.toByteArray(Charsets.UTF_8)
                        val password = credential.password.toByteArray(Charsets.UTF_8)
                        require(username.isNotEmpty() && username.size <= MAX_USERNAME_BYTES) { "Invalid stored username" }
                        require(password.isNotEmpty() && password.size <= MAX_PASSWORD_BYTES) { "Invalid stored password" }
                        output.writeInt(username.size)
                        output.writeInt(password.size)
                        output.write(username)
                        output.write(password)
                        val servers = credential.acceptedServers.distinct()
                        require(servers.size <= MAX_SERVERS) { "Invalid accepted server count" }
                        output.writeInt(servers.size)
                        for (server in servers) {
                            val encoded = server.toByteArray(Charsets.UTF_8)
                            require(encoded.isNotEmpty() && encoded.size <= MAX_SERVER_BYTES) { "Invalid accepted server" }
                            output.writeInt(encoded.size)
                            output.write(encoded)
                        }
                    }
                }
                bytes.toByteArray()
            }
        } finally {
            // String fields cannot be scrubbed; the codec zeroes every
            // intermediate byte array it created above.
        }
    }

    fun decode(encoded: ByteArray): List<StoredCredential> = try {
        DataInputStream(ByteArrayInputStream(encoded)).use { input ->
            require(input.readInt() == MAGIC) { "Invalid stored credential" }
            when (val version = input.readInt()) {
                1 -> listOf(decodeLegacy(input))
                2 -> decodeEntries(input)
                else -> throw IllegalArgumentException("Unsupported stored credential version $version")
            }
        }
    } catch (error: IOException) {
        throw IllegalArgumentException("Invalid stored credential", error)
    }

    private fun decodeEntries(input: DataInputStream): List<StoredCredential> {
        val count = input.readInt()
        require(count in 1..MAX_ENTRIES) { "Invalid stored credential count" }
        return buildList(count) {
            repeat(count) {
                val usernameSize = input.readInt()
                val passwordSize = input.readInt()
                require(usernameSize in 1..MAX_USERNAME_BYTES) { "Invalid stored username size" }
                require(passwordSize in 1..MAX_PASSWORD_BYTES) { "Invalid stored password size" }
                val username = ByteArray(usernameSize).also(input::readFully)
                val password = ByteArray(passwordSize).also(input::readFully)
                try {
                    val serverCount = input.readInt()
                    require(serverCount in 0..MAX_SERVERS) { "Invalid accepted server count" }
                    val servers = buildList(serverCount) {
                        repeat(serverCount) {
                            val serverSize = input.readInt()
                            require(serverSize in 1..MAX_SERVER_BYTES) { "Invalid accepted server size" }
                            val server = ByteArray(serverSize).also(input::readFully)
                            try {
                                add(server.toString(Charsets.UTF_8))
                            } finally {
                                server.fill(0)
                            }
                        }
                    }
                    add(
                        StoredCredential(
                            username = username.toString(Charsets.UTF_8),
                            password = password.toString(Charsets.UTF_8),
                            acceptedServers = servers,
                        ),
                    )
                } finally {
                    username.fill(0)
                    password.fill(0)
                }
            }
        }
    }

    /** v1 held one credential with no server scope; it only ever saw the ZJU endpoint. */
    private fun decodeLegacy(input: DataInputStream): StoredCredential {
        val usernameSize = input.readInt()
        val passwordSize = input.readInt()
        require(usernameSize in 1..MAX_USERNAME_BYTES) { "Invalid stored username size" }
        require(passwordSize in 1..MAX_PASSWORD_BYTES) { "Invalid stored password size" }
        require(usernameSize + passwordSize == input.available()) { "Truncated stored credential" }
        val username = ByteArray(usernameSize).also(input::readFully)
        val password = ByteArray(passwordSize).also(input::readFully)
        try {
            return StoredCredential(
                username = username.toString(Charsets.UTF_8),
                password = password.toString(Charsets.UTF_8),
                acceptedServers = listOf(serverKey(CustomServerTarget.DEFAULT)),
            )
        } finally {
            username.fill(0)
            password.fill(0)
        }
    }
}

internal class InvalidStoredCredential(cause: Throwable) :
    Exception("Stored credential cannot be decrypted", cause)

internal object StoredCredentialEncryption {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private val AAD: ByteArray =
        "io.github.cedar17.zjuconnect/atrust-saved-credential/v1".toByteArray(Charsets.UTF_8)

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

/**
 * Encrypted vault of account credentials, one entry per username, each
 * scoped to the servers that accepted it. Doubles as the manual account
 * picker source on the login screen.
 */
internal class SavedCredentialStore(context: Context) {
    private val credentialFile = AtomicFile(context.noBackupFilesDir.resolve(FILE_NAME))

    fun readAll(): List<StoredCredential> {
        if (!credentialFile.baseFile.isFile) return listOf()
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

    /** Single-credential convenience for callers that expect at most one match. */
    fun read(): StoredCredential? = readAll().firstOrNull()

    fun writeAll(credentials: List<StoredCredential>) {
        val plaintext = StoredCredentialCodec.encode(credentials)
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

    fun write(credential: StoredCredential) = writeAll(listOf(credential))

    /** Replaces the entry with the same username, or appends up to the cap. */
    fun upsert(credential: StoredCredential): Boolean {
        val current = readAll()
        val without = current.filterNot { it.username == credential.username }
        val updated = (listOf(credential) + without).take(StoredCredentialCodec.MAX_ENTRIES)
        writeAll(updated)
        return true
    }

    fun remove(username: String): Boolean {
        val current = readAll()
        if (current.none { it.username == username }) return true
        val remaining = current.filterNot { it.username == username }
        if (remaining.isEmpty()) {
            return clear()
        }
        writeAll(remaining)
        return true
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
        const val KEY_ALIAS = "io.github.cedar17.zjuconnect.atrust-saved-credential.v1"
    }
}
