import Foundation
import CryptoKit

/// MeshCrypto — all cryptographic operations for the MRIT mesh (iOS).
///
/// Scheme — IDENTICAL to the Android implementation:
///   Key exchange:   ECDH over P-256
///   Key derivation: SHA-256(rawSharedSecret) → 32-byte AES key
///   Encryption:     AES-256-GCM, 12-byte random nonce, 16-byte auth tag
///
/// Wire format of encrypted payload (binary compatible with Android):
///   [ 12 bytes : nonce ] [ N+16 bytes : ciphertext + auth tag ]
///
/// Both platforms produce and consume the SAME wire format — a packet
/// encrypted on Android can be decrypted on iOS and vice versa.
public enum MeshCrypto {

    // ── Key agreement ──────────────────────────────────────────────────────────

    /// Derive a shared AES-256 symmetric key from our private key and the peer's public key.
    ///
    /// Both nodes independently compute the same key — no key material is ever transmitted.
    ///
    /// Derivation: SHA-256(ECDH_shared_secret) — matches Android exactly.
    public static func computeSharedKey(
        ourPrivateKey:  P256.KeyAgreement.PrivateKey,
        theirPublicKey: P256.KeyAgreement.PublicKey
    ) throws -> SymmetricKey {
        let sharedSecret = try ourPrivateKey.sharedSecretFromKeyAgreement(with: theirPublicKey)

        // Hash the raw ECDH output — matches Android's MessageDigest.getInstance("SHA-256").digest(rawSecret)
        let keyBytes: SymmetricKey = sharedSecret.withUnsafeBytes { secretBytes in
            var hasher = SHA256()
            hasher.update(bufferPointer: secretBytes)
            return SymmetricKey(data: hasher.finalize())
        }
        return keyBytes
    }

    // ── Encryption ─────────────────────────────────────────────────────────────

    /// Encrypt [plaintext] with AES-256-GCM using [key].
    ///
    /// Output format (wire-compatible with Android):
    ///   [12-byte nonce] + [ciphertext] + [16-byte auth tag]
    public static func encrypt(_ plaintext: Data, using key: SymmetricKey) throws -> Data {
        let nonce     = AES.GCM.Nonce()            // random 12-byte nonce
        let sealedBox = try AES.GCM.seal(plaintext, using: key, nonce: nonce)

        // Combine into Android-compatible wire format: nonce + ciphertext + tag
        return Data(nonce) + sealedBox.ciphertext + sealedBox.tag
    }

    // ── Decryption ─────────────────────────────────────────────────────────────

    /// Decrypt data produced by [encrypt] (or by Android's MeshCrypto.encrypt).
    ///
    /// Returns nil if authentication fails (tampered data or wrong key).
    public static func decrypt(_ data: Data, using key: SymmetricKey) -> Data? {
        guard data.count > 28 else { return nil }  // 12-byte nonce + at least 1 byte + 16-byte tag

        let nonceData  = data.prefix(12)
        let remaining  = data.dropFirst(12)
        let tag        = remaining.suffix(16)
        let ciphertext = remaining.dropLast(16)

        return try? {
            let nonce     = try AES.GCM.Nonce(data: nonceData)
            let sealedBox = try AES.GCM.SealedBox(nonce: nonce, ciphertext: ciphertext, tag: tag)
            return try AES.GCM.open(sealedBox, using: key)
        }()
    }

    // ── Hashing ────────────────────────────────────────────────────────────────

    /// Short fingerprint of a packet — used by AckManager to match ACKs to their MSG.
    /// Matches the Android MeshCrypto.packetFingerprint algorithm.
    public static func packetFingerprint(
        srcBytes: Data,
        dstBytes: Data,
        payload:  Data
    ) -> String {
        var hasher = SHA256()
        hasher.update(data: srcBytes)
        hasher.update(data: dstBytes)
        hasher.update(data: payload)
        let digest = hasher.finalize()
        return digest.prefix(4).map { String(format: "%02x", $0) }.joined()
    }
}
