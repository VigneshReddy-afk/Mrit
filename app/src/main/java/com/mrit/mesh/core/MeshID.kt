package com.mrit.mesh.core

import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * MeshID — unique 256-bit identifier for every node on the MRIT mesh network.
 *
 * Generated once on first install. Not a phone number. Not an IP. Ours.
 *
 * Generation formula:
 *   MeshID = SHA-256(EC_PublicKey_bytes + InstallTimestamp + RandomSalt)
 *
 * This ensures:
 *   - Globally unique (EC key pair + salt + time)
 *   - Not traceable to personal identity (no phone number, no IMEI)
 *   - 32 bytes / 256-bit — compact for packet headers
 */
data class MeshID(val bytes: ByteArray) {

    init {
        require(bytes.size == 32) { "MeshID must be exactly 32 bytes (256-bit)" }
    }

    /** Full hex string, e.g. "a3f79c12...8b41" (64 chars) */
    override fun toString(): String = bytes.joinToString("") { "%02x".format(it) }

    /** Short display name for UI — first 8 hex chars */
    fun shortId(): String = toString().take(8).uppercase()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MeshID) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {

        /**
         * Generate a new MeshID for this device.
         * Called exactly once on first app install — result stored in encrypted SharedPreferences.
         */
        fun generate(): MeshID {
            // Generate EC P-256 key pair (Curve25519 requires BouncyCastle on older Android)
            val keyGen = KeyPairGenerator.getInstance("EC")
            keyGen.initialize(256, SecureRandom())
            val publicKeyBytes = keyGen.generateKeyPair().public.encoded

            val timestamp = System.currentTimeMillis().toString().toByteArray(Charsets.UTF_8)
            val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }

            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(publicKeyBytes)
            digest.update(timestamp)
            digest.update(salt)

            return MeshID(digest.digest())
        }

        /**
         * Restore a MeshID from its stored hex string.
         *
         * @param hex 64-character hex string (e.g. from SharedPreferences)
         */
        fun fromHex(hex: String): MeshID {
            require(hex.length == 64) { "Hex must be 64 characters, got ${hex.length}" }
            val bytes = ByteArray(32) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            return MeshID(bytes)
        }

        /** Reconstruct MeshID from raw 32-byte array (e.g. from packet header). */
        fun fromBytes(bytes: ByteArray): MeshID {
            require(bytes.size >= 32) { "Need at least 32 bytes, got ${bytes.size}" }
            return MeshID(bytes.copyOf(32))
        }

        /**
         * Broadcast address — packet addressed to ALL nearby nodes.
         * All 0xFF bytes: FF FF FF ... FF (32 bytes)
         */
        val BROADCAST: MeshID = MeshID(ByteArray(32) { 0xFF.toByte() })
    }
}
