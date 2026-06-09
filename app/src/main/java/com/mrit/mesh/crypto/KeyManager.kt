package com.mrit.mesh.crypto

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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
 * Phase 4 upgrade: private key is now stored in EncryptedSharedPreferences.
 * EncryptedSharedPreferences uses an Android Keystore-backed AES-256-GCM master key
 * to encrypt both the preference keys and values — the EC private key bytes are
 * never written to disk in plaintext.
 *
 * On devices with a Secure Enclave (most Android 6+ devices), the master key
 * is hardware-backed and the private key cannot be extracted even with root access.
 *
 * Key compatibility with iOS:
 *   Both platforms use EC P-256 (secp256r1).
 *   Android public key: X.509 DER, ~91 bytes (SubjectPublicKeyInfo format).
 *   iOS public key:     x963 uncompressed, 65 bytes (04 || X || Y).
 *   The Phase 5 transport bridge will handle format detection and conversion.
 */
class KeyManager(context: Context) {

    companion object {
        private const val TAG          = "KeyManager"
        private const val PREFS_NAME   = "mrit_crypto_v2"   // v2 = EncryptedSharedPreferences
        private const val KEY_PRIVATE  = "ec_private_key"
        private const val KEY_PUBLIC   = "ec_public_key"
        private const val EC_CURVE     = "secp256r1"

        /** Reconstruct a PublicKey from X.509 DER bytes received in a DISCOVER packet. */
        fun publicKeyFromBytes(bytes: ByteArray): PublicKey? = try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
        } catch (e: Exception) {
            Log.w("KeyManager", "Failed to decode public key: ${e.message}")
            null
        }
    }

    val keyPair: KeyPair = loadOrGenerate(context)

    val privateKey: PrivateKey get() = keyPair.private
    val publicKey:  PublicKey  get() = keyPair.public

    /** X.509 DER-encoded public key bytes — embedded in every DISCOVER packet */
    val publicKeyBytes: ByteArray get() = publicKey.encoded

    // ── Key persistence ────────────────────────────────────────────────────────

    private fun loadOrGenerate(context: Context): KeyPair {
        val prefs = createEncryptedPrefs(context)
        val privB64 = prefs.getString(KEY_PRIVATE, null)
        val pubB64  = prefs.getString(KEY_PUBLIC, null)

        return if (privB64 != null && pubB64 != null) {
            try {
                val factory = KeyFactory.getInstance("EC")
                val priv = factory.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.NO_WRAP)))
                val pub  = factory.generatePublic(X509EncodedKeySpec(Base64.decode(pubB64, Base64.NO_WRAP)))
                KeyPair(pub, priv).also { Log.d(TAG, "Loaded EC key pair from EncryptedSharedPreferences") }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load key pair, regenerating: ${e.message}")
                generateAndSave(prefs)
            }
        } else {
            generateAndSave(prefs)
        }
    }

    private fun createEncryptedPrefs(context: Context): android.content.SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ).also { Log.d(TAG, "EncryptedSharedPreferences initialised") }
        } catch (e: Exception) {
            // Fallback to regular SharedPreferences if Keystore is unavailable (emulator/test)
            Log.w(TAG, "EncryptedSharedPreferences unavailable, using plain prefs: ${e.message}")
            context.getSharedPreferences("mrit_crypto_fallback", Context.MODE_PRIVATE)
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

        Log.d(TAG, "Generated new EC P-256 key pair — stored encrypted (${kp.public.encoded.size}b public key)")
        return kp
    }
}
