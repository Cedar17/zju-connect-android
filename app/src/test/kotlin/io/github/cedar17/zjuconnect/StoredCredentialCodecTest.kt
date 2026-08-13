package io.github.cedar17.zjuconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator

class StoredCredentialCodecTest {
    @Test
    fun credentialRecordRoundTripsUnicode() {
        val credential = StoredCredential(username = "学生账号", password = "correct horse 电池 staple")

        val encoded = StoredCredentialCodec.encode(credential)
        val decoded = StoredCredentialCodec.decode(encoded)

        assertEquals(credential, decoded)
        assertFalse(encoded.contentEquals(credential.password.toByteArray()))
    }

    @Test
    fun credentialRecordRejectsTruncationAndUnsupportedVersion() {
        val encoded = StoredCredentialCodec.encode(StoredCredential("student", "secret"))
        assertThrows(IllegalArgumentException::class.java) {
            StoredCredentialCodec.decode(encoded.copyOf(encoded.size - 1))
        }
        encoded[7] = 2
        assertThrows(IllegalArgumentException::class.java) {
            StoredCredentialCodec.decode(encoded)
        }
    }

    @Test
    fun encryptedCredentialEnvelopeContainsNoPlaintextAndRejectsTampering() {
        val credential = StoredCredential("student-account", "correct-horse-battery-staple")
        val plaintext = StoredCredentialCodec.encode(credential)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        val encrypted = StoredCredentialEncryption.encrypt(plaintext, key)
        val envelope = AuthSessionEnvelopeCodec.encode(encrypted)

        assertFalse(envelope.containsSubsequence(credential.username.toByteArray()))
        assertFalse(envelope.containsSubsequence(credential.password.toByteArray()))
        assertEquals(credential, StoredCredentialCodec.decode(StoredCredentialEncryption.decrypt(encrypted, key)))

        encrypted.ciphertext[encrypted.ciphertext.lastIndex] =
            (encrypted.ciphertext.last().toInt() xor 1).toByte()
        assertThrows(GeneralSecurityException::class.java) {
            StoredCredentialEncryption.decrypt(encrypted, key)
        }
    }
}

private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > size) return false
    return indices.take(size - needle.size + 1).any { offset ->
        needle.indices.all { index -> this[offset + index] == needle[index] }
    }
}
