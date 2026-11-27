package io.github.cedar17.zjuconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator

class StoredCredentialCodecTest {
    private val defaultServer = serverKey(CustomServerTarget.DEFAULT)

    @Test
    fun credentialRecordsRoundTripUnicodeAndServerScopes() {
        val credentials = listOf(
            StoredCredential(
                username = "学生账号",
                password = "correct horse 电池 staple",
                acceptedServers = listOf(defaultServer, "atrust.example.edu:8443"),
            ),
            StoredCredential(username = "second", password = "pw2", acceptedServers = listOf()),
        )

        val encoded = StoredCredentialCodec.encode(credentials)
        val decoded = StoredCredentialCodec.decode(encoded)

        assertEquals(credentials, decoded)
        assertFalse(encoded.contentEquals(credentials[0].password.toByteArray()))
    }

    @Test
    fun legacyCredentialMigratesToTheDefaultServerScope() {
        // v1 layout: magic, version=1, username size, password size, payload.
        val username = "student".toByteArray(Charsets.UTF_8)
        val password = "secret".toByteArray(Charsets.UTF_8)
        val legacy = ByteArrayOutputStream().let { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(0x5A4A4352)
                output.writeInt(1)
                output.writeInt(username.size)
                output.writeInt(password.size)
                output.write(username)
                output.write(password)
            }
            bytes.toByteArray()
        }

        val decoded = StoredCredentialCodec.decode(legacy)

        assertEquals(listOf(StoredCredential("student", "secret", listOf(defaultServer))), decoded)
        assertTrue(decoded.single().isAcceptedOn(defaultServer))
        assertFalse(decoded.single().isAcceptedOn("atrust.example.edu:8443"))
    }

    @Test
    fun credentialListRejectsTruncationAndUnsupportedVersion() {
        val encoded = StoredCredentialCodec.encode(listOf(StoredCredential("student", "secret")))
        assertThrows(IllegalArgumentException::class.java) {
            StoredCredentialCodec.decode(encoded.copyOf(encoded.size - 1))
        }
        encoded[7] = 3
        assertThrows(IllegalArgumentException::class.java) {
            StoredCredentialCodec.decode(encoded)
        }
    }

    @Test
    fun encryptedCredentialEnvelopeContainsNoPlaintextAndRejectsTampering() {
        val credentials = listOf(StoredCredential("student-account", "correct-horse-battery-staple"))
        val plaintext = StoredCredentialCodec.encode(credentials)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        val encrypted = StoredCredentialEncryption.encrypt(plaintext, key)
        val envelope = AuthSessionEnvelopeCodec.encode(encrypted)

        assertFalse(envelope.containsSubsequence(credentials[0].username.toByteArray()))
        assertFalse(envelope.containsSubsequence(credentials[0].password.toByteArray()))
        assertEquals(
            credentials,
            StoredCredentialCodec.decode(StoredCredentialEncryption.decrypt(encrypted, key)),
        )

        encrypted.ciphertext[encrypted.ciphertext.lastIndex] =
            (encrypted.ciphertext.last().toInt() xor 1).toByte()
        assertThrows(GeneralSecurityException::class.java) {
            StoredCredentialEncryption.decrypt(encrypted, key)
        }
    }

    @Test
    fun serverKeyUsesHostAndPort() {
        assertEquals("vpn.zju.edu.cn:443", serverKey(CustomServerTarget.DEFAULT))
        assertEquals("atrust.example.edu:8443", serverKey(CustomServerTarget("atrust.example.edu", 8443)))
    }
}

private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > size) return false
    return indices.take(size - needle.size + 1).any { offset ->
        needle.indices.all { index -> this[offset + index] == needle[index] }
    }
}
