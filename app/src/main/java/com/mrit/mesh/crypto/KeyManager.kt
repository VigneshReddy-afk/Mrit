package com.mrit.mesh.crypto

import android.content.Context
import android.util.Base64
import android.util.Log
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * KeyManager — generates, persists, and loads the node's EC P-256 key pair.
 *
 * The key pair is generated ONCE on first launch and stored in SharedPreferences
 * (Base64-encoded DER). The same key pair is reused across restarts so that
 * our MeshID and encryption identity stay consistent.
 *
 * Key usage:
 *   Private key → ECDH key agreement (derive shared secret with a peer)
 *   Public key  → sent to peers in DISCOVER packets so they can derive the shared secret
 *
 * Security note: Storing the private key in SharedPreferences is acceptable for Phase 3.
 * Phase 4 will migrate to Android Keystore (hardware-backed, private key never exported).
 */
class KeyManager(context: Context) {

    companion object {
        private const val TAG          = "KeyManager"
        private const val PREFS_NAME   = "mrit_crypto"
        private const val KEY_PRIVATE  = "ec_private_key"
        private const val KEY_PUBLIC   = "ec_public_key"
        private const val EC_CURVE     = "secp256r1"   // NIST P-256, natively supported on all Android
    }

    val keyPair: KeyPair = loadOrGenerate(context)

    val privateKey: PrivateKey get() = keyPair.private
    val publicKey: PublicKey   get() = keyPair.public

    /** Raw X.509 DER bytes of our public key — embedded in every DISCOVER packet */
    val publicKeyBytes: ByteArray get() = publicKey.encoded

    // ── Key persistence ────────────────────────────────────────────────────────

    private fun loadOrGenerate(context: Context): KeyPair {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val privB64 = prefs.getString(KEY_PRIVATE, null)
        val pubB64  = prefs.getString(KEY_PUBLIC,  null)

        return if (privB64 != null && pubB64 != null) {
            try {
                val factory = KeyFactory.getInstance("EC")
                val priv = factory.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.NO_WRAP)))
                val pub  = factory.generatePublic(X509EncodedKeySpec(Base64.decode(pubB64, Base64.NO_WRAP)))
                KeyPair(pub, priv).also { Log.d(TAG, "Loaded existing EC key pair") }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load key pair, regenerating: ${e.message}")
                generateAndSave(prefs)
            }
        } else {
            generateAndSave(prefs)
        }
    }

    private fun generateAndSave(prefs: android.content.SharedPreferences): KeyPair {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec(EC_CURVE), SecureRandom())
        val kp = gen.generateKeyPair()

        prefs.edit()
            .putString(KEY_PRIVATE, Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
            .putString(KEY_PUBLIC,  Base64.encodeToString(kp.public.encoded,  Base64.NO_WRAP))
            .apply()

        Log.d(TAG, "Generated new EC P-256 key pair (${kp.public.encoded.size} byte public key)")
        return kp
    }

    // ── Static helpers ─────────────────────────────────────────────────────────

    companion object Util {
        /**
         * Reconstruct a PublicKey from raw X.509 DER bytes received in a DISCOVER packet.
         * Returns null if the bytes are not a valid EC public key.
         */
        fun publicKeyFromBytes(bytes: ByteArray): PublicKey? = try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
        } catch (e: Exception) {
            Log.w("KeyManager", "Failed to decode public key: ${e.message}")
            null
        }
    }
}
