import Foundation
import CryptoKit
import Security

/// KeyManager — generates, persists, and loads the node's EC P-256 key pair.
///
/// Keys are stored in the **iOS Keychain** (hardware-backed on devices with Secure Enclave).
/// The Keychain is more secure than Android SharedPreferences — the private key never
/// leaves the secure element on supported hardware.
///
/// Key compatibility with Android:
///   - Both use EC P-256 (secp256r1 / NIST P-256)
///   - Public keys are exchanged as 65-byte x963 (uncompressed point: 04 + X + Y)
///   - ECDH shared secrets are computed identically by both platforms
public class KeyManager {

    // Keychain item labels
    private static let privateKeyTag  = "com.mrit.mesh.ec.private"
    private static let publicKeyLabel = "com.mrit.mesh.ec.public"

    /// This node's private key (EC P-256)
    public let privateKey: P256.KeyAgreement.PrivateKey

    /// This node's public key (EC P-256)
    public var publicKey: P256.KeyAgreement.PublicKey { privateKey.publicKey }

    /// Raw 65-byte x963 representation of our public key.
    /// Format: 04 || X (32 bytes) || Y (32 bytes)
    /// This is what we embed in DISCOVER packets.
    ///
    /// Note: Android sends X.509 DER format (~91 bytes), but for iOS↔iOS we use
    /// x963 (65 bytes). When iOS↔Android interop is added in Phase 5, the codec
    /// will detect and handle both formats.
    public var publicKeyBytes: Data { privateKey.publicKey.x963Representation }

    public init() {
        self.privateKey = Self.loadOrGenerate()
    }

    // ── Key persistence (Keychain) ─────────────────────────────────────────────

    private static func loadOrGenerate() -> P256.KeyAgreement.PrivateKey {
        if let loaded = loadFromKeychain() { return loaded }
        let key = P256.KeyAgreement.PrivateKey()
        saveToKeychain(key)
        return key
    }

    private static func loadFromKeychain() -> P256.KeyAgreement.PrivateKey? {
        let query: [String: Any] = [
            kSecClass as String:            kSecClassKey,
            kSecAttrApplicationTag as String: privateKeyTag.data(using: .utf8)!,
            kSecAttrKeyType as String:      kSecAttrKeyTypeEC,
            kSecReturnData as String:       true
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let keyData = result as? Data else { return nil }

        return try? P256.KeyAgreement.PrivateKey(rawRepresentation: keyData)
    }

    private static func saveToKeychain(_ key: P256.KeyAgreement.PrivateKey) {
        let query: [String: Any] = [
            kSecClass as String:             kSecClassKey,
            kSecAttrApplicationTag as String: privateKeyTag.data(using: .utf8)!,
            kSecAttrKeyType as String:       kSecAttrKeyTypeEC,
            kSecValueData as String:         key.rawRepresentation,
            kSecAttrAccessible as String:    kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        // Delete any existing entry first (handles re-install scenario)
        SecItemDelete(query as CFDictionary)
        let status = SecItemAdd(query as CFDictionary, nil)
        if status != errSecSuccess {
            print("[KeyManager] Warning: failed to save key to Keychain, status=\(status)")
        }
    }

    // ── Static helpers ─────────────────────────────────────────────────────────

    /// Reconstruct a peer's public key from the x963 bytes received in a DISCOVER packet.
    /// Returns nil if the bytes are not a valid P-256 public key.
    public static func publicKeyFromBytes(_ bytes: Data) -> P256.KeyAgreement.PublicKey? {
        try? P256.KeyAgreement.PublicKey(x963Representation: bytes)
    }
}
