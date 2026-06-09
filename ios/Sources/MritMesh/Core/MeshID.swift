import Foundation
import CryptoKit

/// MeshID — unique 256-bit identifier for every node on the MRIT mesh network.
///
/// Generated once on first install. Not a phone number. Not an IP. Ours.
///
/// Generation:  SHA-256(EC_P256_PublicKey + InstallTimestamp + RandomSalt)
/// Wire format: 32 raw bytes — identical to Android's MeshID.
public struct MeshID: Hashable, Sendable {

    /// Raw 32-byte (256-bit) identifier
    public let bytes: Data

    public init(bytes: Data) {
        precondition(bytes.count == 32, "MeshID must be exactly 32 bytes")
        self.bytes = bytes
    }

    // ── Identity ───────────────────────────────────────────────────────────────

    /// Full 64-character lowercase hex string
    public var hexString: String {
        bytes.map { String(format: "%02x", $0) }.joined()
    }

    /// Short 8-character uppercase hex — used in UI and logs
    public var shortId: String {
        String(hexString.prefix(8)).uppercased()
    }

    // ── Factory methods ────────────────────────────────────────────────────────

    /// Generate a new MeshID for this device.
    /// Called exactly once on first launch — result stored in Keychain.
    public static func generate() -> MeshID {
        // EC P-256 key pair — same curve as Android
        let privateKey  = P256.KeyAgreement.PrivateKey()
        let pubKeyBytes = privateKey.publicKey.x963Representation  // 65-byte uncompressed point

        let timestampBytes = withUnsafeBytes(of: Int64(Date().timeIntervalSince1970 * 1000).bigEndian) { Data($0) }

        var salt = Data(count: 32)
        salt.withUnsafeMutableBytes { _ = SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }

        var hasher = SHA256()
        hasher.update(data: pubKeyBytes)
        hasher.update(data: timestampBytes)
        hasher.update(data: salt)
        return MeshID(bytes: Data(hasher.finalize()))
    }

    /// Reconstruct a MeshID from a 64-character hex string.
    public static func fromHex(_ hex: String) -> MeshID? {
        guard hex.count == 64 else { return nil }
        var result = Data(count: 32)
        for i in 0..<32 {
            let lo = hex.index(hex.startIndex, offsetBy: i * 2)
            let hi = hex.index(lo, offsetBy: 2)
            guard let byte = UInt8(hex[lo..<hi], radix: 16) else { return nil }
            result[i] = byte
        }
        return MeshID(bytes: result)
    }

    /// Reconstruct a MeshID from raw bytes (e.g. from a received packet header).
    public static func fromBytes(_ data: Data) -> MeshID? {
        guard data.count >= 32 else { return nil }
        return MeshID(bytes: data.prefix(32))
    }

    /// Broadcast address — packet addressed to ALL nearby nodes (all 0xFF bytes).
    public static let broadcast = MeshID(bytes: Data(repeating: 0xFF, count: 32))
}

extension MeshID: CustomStringConvertible {
    public var description: String { hexString }
}
