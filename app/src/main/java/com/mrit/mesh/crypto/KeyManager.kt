package com.mrit.mesh.crypto

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * KeyManager — generates, persists, and loads the node's EC P-256 key pair.
 *
 * Phase 4: private key is stored in EncryptedSharedPreferences.
 * EncryptedSharedPreferences uses an Android Keystore-backed AES-256-GCM master key
 * to encrypt both the preference keys and values — the EC private key bytes are
 * never written to disk in plaintext.
 *
 * On devices with a Secure Enclave (most Android 6+ devices), the master key
 * is hardware-backed and the private key cannot be extracted even with root access.
 *
 * Phase 5 — cross-platform key format reconciliation:
 *   Both platforms use EC P-256 (secp256r1). DISCOVER packets now carry the
 *   public key as a **65-byte x963 point** (0x04 || X(32) || Y(32)) on BOTH
 *   Android and iOS — this is the format CryptoKit uses natively
 *   (`P256.KeyAgreement.PublicKey.x963Representation`). Android encodes/decodes
 *   it manually via [java.security.spec.ECPoint] + the secp256r1
 *   [ECParameterSpec]. ECDH over the resulting point produces an identical
 *   AES-256 key on both platforms — see PROTOCOL.md.
 *
 *   Internally (on-disk persistence only) Android still stores keys as
 *   X.509/PKCS8 DER via the JCA default `.encoded` — that format never
 *   crosses the wire and is irrelevant to interop.
 */
class KeyManager(context: Context) {

    companion object {
        private const val TAG          = "KeyManager"
        private const val PREFS_NAME   = "mrit_crypto_v2"   // v2 = EncryptedSharedPreferences
        private const val KEY_PRIVATE  = "ec_private_key"
        private const val KEY_PUBLIC   = "ec_public_key"
        private const val EC_CURVE     = "secp256r1"

        /** Length of an x963 uncompressed P-256 point: 1 (0x04 prefix) + 32 (X) + 32 (Y) */
        private const val X963_LEN  = 65
        private const val COORD_LEN = 32

        /** Cached secp256r1 curve parameters — used to reconstruct points from raw bytes. */
        private val curveParams: ECParameterSpec by lazy {
            val params = AlgorithmParameters.getInstance("EC")
            params.init(ECGenParameterSpec(EC_CURVE))
            params.getParameterSpec(ECParameterSpec::class.java)
        }

        /**
         * Reconstruct a PublicKey from the 65-byte x963 (uncompressed point) bytes
         * received in a DISCOVER packet. Binary compatible with iOS CryptoKit's
         * `x963Representation`.
         *
         * Returns null if [bytes] is not a valid 65-byte uncompressed P-256 point.
         */
        fun publicKeyFromBytes(bytes: ByteArray): PublicKey? {
            if (bytes.size != X963_LEN || bytes[0] != 0x04.toByte()) {
                Log.w(TAG, "Invalid x963 public key: ${bytes.size} bytes (expected $X963_LEN, prefix 0x04)")
                return null
            }
            return try {
                val x = BigInteger(1, bytes.copyOfRange(1, 1 + COORD_LEN))
                val y = BigInteger(1, bytes.copyOfRange(1 + COORD_LEN, 1 + 2 * COORD_LEN))
                val spec = ECPublicKeySpec(ECPoint(x, y), curveParams)
                KeyFactory.getInstance("EC").generatePublic(spec)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode x963 public key: ${e.message}")
                null
            }
        }

        /**
         * Encode an EC public key as a 65-byte x963 point: 0x04 || X(32) || Y(32).
         * This is the wire format embedded in every DISCOVER packet.
         */
        private fun encodeX963(publicKey: PublicKey): ByteArray {
            val point = (publicKey as ECPublicKey).w
            val x = point.affineX.toFixedLengthBytes(COORD_LEN)
            val y = point.affineY.toFixedLengthBytes(COORD_LEN)
            return byteArrayOf(0x04) + x + y
        }

        /**
         * Encode a non-negative BigInteger as exactly [len] big-endian bytes.
         * BigInteger.toByteArray() may include a leading 0x00 sign byte (stripped here)
         * or may be shorter than [len] if the value has leading zero bytes (zero-padded here).
         */
        private fun BigInteger.toFixedLengthBytes(len: Int): ByteArray {
            val raw = this.toByteArray()
            return when {
                raw.size == len -> raw
                raw.size == len + 1 && raw[0] == 0.toByte() -> raw.copyOfRange(1, raw.size)
                raw.size < len  -> ByteArray(len - raw.size) + raw
                else -> throw IllegalArgumentException("EC coordinate too large for $len bytes (got ${raw.size})")
            }
        }
    }

    val keyPair: KeyPair = loadOrGenerate(context)

    val privateKey: PrivateKey get() = keyPair.private
    val publicKey:  PublicKey  get() = keyPair.public

    /**
     * 65-byte x963 (uncompressed point) public key bytes — embedded in every
     * DISCOVER packet. Identical wire format to iOS CryptoKit's x963Representation.
     */
    val publicKeyBytes: ByteArray get() = encodeX963(publicKey)

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
