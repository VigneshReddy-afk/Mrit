package com.mrit.mesh

import com.mrit.mesh.crypto.MeshCrypto
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec

class MeshCryptoTest {

    /** Generate a fresh EC P-256 key pair for testing */
    private fun genKeyPair() = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        generateKeyPair()
    }

    @Test
    fun `ECDH both sides derive identical shared key`() {
        val aliceKp = genKeyPair()
        val bobKp   = genKeyPair()

        val aliceKey = MeshCrypto.computeSharedKey(aliceKp.private, bobKp.public)
        val bobKey   = MeshCrypto.computeSharedKey(bobKp.private, aliceKp.public)

        assertArrayEquals(aliceKey.encoded, bobKey.encoded)
    }

    @Test
    fun `encrypt then decrypt round-trips correctly`() {
        val aliceKp   = genKeyPair()
        val bobKp     = genKeyPair()
        val sharedKey = MeshCrypto.computeSharedKey(aliceKp.private, bobKp.public)

        val plaintext = "Hello from the mesh".toByteArray(Charsets.UTF_8)
        val encrypted = MeshCrypto.encrypt(plaintext, sharedKey)
        val decrypted = MeshCrypto.decrypt(encrypted, sharedKey)

        assertNotNull(decrypted)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `encrypted payload is different from plaintext`() {
        val kp  = genKeyPair()
        val key = MeshCrypto.computeSharedKey(kp.private, kp.public)

        val plaintext = "test message".toByteArray()
        val encrypted = MeshCrypto.encrypt(plaintext, key)

        assertFalse(encrypted.contentEquals(plaintext))
    }

    @Test
    fun `each encrypt call produces different ciphertext (random IV)`() {
        val kp  = genKeyPair()
        val key = MeshCrypto.computeSharedKey(kp.private, kp.public)

        val plaintext = "same message".toByteArray()
        val enc1 = MeshCrypto.encrypt(plaintext, key)
        val enc2 = MeshCrypto.encrypt(plaintext, key)

        // Different IVs → different ciphertexts even for identical plaintext
        assertFalse(enc1.contentEquals(enc2))
    }

    @Test
    fun `decrypt returns null for tampered ciphertext`() {
        val kp  = genKeyPair()
        val key = MeshCrypto.computeSharedKey(kp.private, kp.public)

        val encrypted = MeshCrypto.encrypt("hello".toByteArray(), key).toMutableList()
        encrypted[encrypted.size - 1] = (encrypted.last() + 1).toByte()  // flip last byte

        val result = MeshCrypto.decrypt(encrypted.toByteArray(), key)
        assertNull(result)  // GCM auth tag check must fail
    }

    @Test
    fun `decrypt returns null for wrong key`() {
        val aliceKp = genKeyPair()
        val bobKp   = genKeyPair()
        val eveKp   = genKeyPair()

        val correctKey = MeshCrypto.computeSharedKey(aliceKp.private, bobKp.public)
        val wrongKey   = MeshCrypto.computeSharedKey(eveKp.private, aliceKp.public)

        val encrypted = MeshCrypto.encrypt("secret".toByteArray(), correctKey)
        val result    = MeshCrypto.decrypt(encrypted, wrongKey)
        assertNull(result)
    }

    @Test
    fun `decrypt returns null for data shorter than IV length`() {
        val kp  = genKeyPair()
        val key = MeshCrypto.computeSharedKey(kp.private, kp.public)
        assertNull(MeshCrypto.decrypt(ByteArray(5), key))
    }

    @Test
    fun `packetFingerprint is deterministic`() {
        val src  = ByteArray(32) { it.toByte() }
        val dst  = ByteArray(32) { (it + 1).toByte() }
        val data = "payload".toByteArray()

        val fp1 = MeshCrypto.packetFingerprint(src, dst, data)
        val fp2 = MeshCrypto.packetFingerprint(src, dst, data)

        assertEquals(fp1, fp2)
        assertEquals(8, fp1.length)  // 4 bytes → 8 hex chars
    }

    @Test
    fun `packetFingerprint differs for different payloads`() {
        val src = ByteArray(32)
        val dst = ByteArray(32)

        val fp1 = MeshCrypto.packetFingerprint(src, dst, "hello".toByteArray())
        val fp2 = MeshCrypto.packetFingerprint(src, dst, "world".toByteArray())

        assertNotEquals(fp1, fp2)
    }
}
