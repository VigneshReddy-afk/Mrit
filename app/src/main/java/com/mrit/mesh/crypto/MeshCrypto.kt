package com.mrit.mesh.crypto

import android.util.Log
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * MeshCrypto — all cryptographic operations for the MRIT mesh.
 *
 * Scheme:
 *   1. Key exchange  — ECDH (Elliptic Curve Diffie-Hellman) over P-256
 *   2. Key derivation — SHA-256(sharedSecret) → 32-byte AES key
 *   3. Encryption    — AES-256-GCM (authenticated encryption — confidentiality + integrity)
 *
 * Wire format of an encrypted payload:
 *   [ 12 bytes : GCM IV/nonce (random per message) ]
 *   [  N bytes : ciphertext + 16-byte GCM auth tag  ]
 *   Total overhead: 28 bytes per message
 *
 * Why AES-256-GCM:
 *   - Authenticated: any tampering of the ciphertext causes decryption to fail
 *   - Fast: hardware-accelerated on all modern Android devices
 *   - No padding needed (stream cipher mode)
 *   - Natively supported in Android (no external libraries required)
 */
object MeshCrypto {

    private const val TAG          = "MeshCrypto"
    private const val AES_MODE     = "AES/GCM/NoPadding"
    private const val GCM_IV_LEN   = 12    // bytes — NIST recommended for GCM
    private const val GCM_TAG_LEN  = 128   // bits — full 16-byte authentication tag

    // ── Key Agreement ──────────────────────────────────────────────────────────

    /**
     * Derive a shared AES-256 key from our private key and the peer's public key.
     *
     * Both nodes compute the same shared secret independently via ECDH.
     * The raw ECDH output is hashed with SHA-256 to produce a uniform 256-bit AES key.
     *
     * @param ourPrivateKey  This node's EC private key
     * @param theirPublicKey The peer's EC public key (received in their DISCOVER packet)
     * @return AES-256 SecretKey for encrypting/decrypting messages to/from this peer
     */
    fun computeSharedKey(ourPrivateKey: PrivateKey, theirPublicKey: PublicKey): SecretKey {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(ourPrivateKey)
        ka.doPhase(theirPublicKey, true)
        val rawSecret = ka.generateSecret()

        // Hash the raw ECDH output — produces a uniformly distributed 256-bit key
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(rawSecret)
        return SecretKeySpec(keyBytes, "AES")
    }

    // ── Encryption ─────────────────────────────────────────────────────────────

    /**
     * Encrypt [plaintext] with AES-256-GCM using [key].
     *
     * A fresh random 12-byte IV is generated for every message — critical for GCM security.
     * IV reuse with the same key would be catastrophic for GCM.
     *
     * @return [IV (12 bytes)] + [ciphertext + auth tag (N+16 bytes)]
     */
    fun encrypt(plaintext: ByteArray, key: SecretKey): ByteArray {
        val iv = ByteArray(GCM_IV_LEN).also { SecureRandom().nextBytes(it) }

        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LEN, iv))
        val ciphertext = cipher.doFinal(plaintext)

        // Prepend IV so the receiver can extract it for decryption
        return iv + ciphertext
    }

    // ── Decryption ─────────────────────────────────────────────────────────────

    /**
     * Decrypt data produced by [encrypt].
     *
     * GCM authentication is checked automatically — if the ciphertext or IV has been
     * tampered with, this returns null (AEADBadTagException is caught).
     *
     * @param data  [IV (12 bytes)] + [ciphertext + auth tag]
     * @param key   The shared AES-256 key derived via ECDH
     * @return      Decrypted plaintext, or null if authentication failed
     */
    fun decrypt(data: ByteArray, key: SecretKey): ByteArray? {
        if (data.size < GCM_IV_LEN + GCM_TAG_LEN / 8) {
            Log.w(TAG, "Encrypted payload too short: ${data.size} bytes")
            return null
        }

        return try {
            val iv         = data.copyOfRange(0, GCM_IV_LEN)
            val ciphertext = data.copyOfRange(GCM_IV_LEN, data.size)

            val cipher = Cipher.getInstance(AES_MODE)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LEN, iv))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            // javax.crypto.AEADBadTagException → tampered or wrong key
            Log.w(TAG, "Decryption failed (bad tag or wrong key): ${e.javaClass.simpleName}")
            null
        }
    }

    // ── Hashing ────────────────────────────────────────────────────────────────

    /**
     * Compute a short (8 hex char) fingerprint of a packet's identity.
     * Used by AckManager to match ACK packets to their original MSG.
     *
     * Input: srcId bytes + dstId bytes + payload → SHA-256 → first 4 bytes as hex
     */
    fun packetFingerprint(
        srcBytes: ByteArray,
        dstBytes: ByteArray,
        payload: ByteArray
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(srcBytes)
        digest.update(dstBytes)
        digest.update(payload)
        return digest.digest().take(4).joinToString("") { "%02x".format(it) }
    }
}
