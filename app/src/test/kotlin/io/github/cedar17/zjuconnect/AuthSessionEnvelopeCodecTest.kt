package io.github.cedar17.zjuconnect

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AuthSessionEnvelopeCodecTest {
    @Test
    fun roundTripsVersionedEncryptedEnvelope() {
        val encrypted = EncryptedAuthSession(
            iv = ByteArray(12) { index -> index.toByte() },
            ciphertext = ByteArray(48) { index -> (index * 3).toByte() },
        )

        val decoded = AuthSessionEnvelopeCodec.decode(AuthSessionEnvelopeCodec.encode(encrypted))

        assertArrayEquals(encrypted.iv, decoded.iv)
        assertArrayEquals(encrypted.ciphertext, decoded.ciphertext)
    }

    @Test
    fun rejectsTruncatedOrUnsupportedEnvelope() {
        val encoded = AuthSessionEnvelopeCodec.encode(
            EncryptedAuthSession(iv = ByteArray(12), ciphertext = ByteArray(32)),
        )
        val unsupported = encoded.copyOf().also { bytes -> bytes[7] = 2 }

        assertThrows(IllegalArgumentException::class.java) {
            AuthSessionEnvelopeCodec.decode(encoded.copyOf(encoded.size - 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AuthSessionEnvelopeCodec.decode(unsupported)
        }
    }
}
