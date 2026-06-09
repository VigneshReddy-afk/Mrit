import XCTest
import CryptoKit
@testable import MritMesh

final class MeshCryptoTests: XCTestCase {

    private func makeKeyPair() -> P256.KeyAgreement.PrivateKey {
        P256.KeyAgreement.PrivateKey()
    }

    func testBothSidesDeriveSameSharedKey() throws {
        let alice = makeKeyPair()
        let bob   = makeKeyPair()

        let aliceKey = try MeshCrypto.computeSharedKey(ourPrivateKey: alice, theirPublicKey: bob.publicKey)
        let bobKey   = try MeshCrypto.computeSharedKey(ourPrivateKey: bob,   theirPublicKey: alice.publicKey)

        // Both keys must be identical — ECDH property
        XCTAssertEqual(aliceKey, bobKey)
    }

    func testEncryptDecryptRoundTrip() throws {
        let alice = makeKeyPair()
        let bob   = makeKeyPair()
        let key   = try MeshCrypto.computeSharedKey(ourPrivateKey: alice, theirPublicKey: bob.publicKey)

        let plaintext = Data("Hello from the iOS mesh".utf8)
        let encrypted = try MeshCrypto.encrypt(plaintext, using: key)
        let decrypted = MeshCrypto.decrypt(encrypted, using: key)

        XCTAssertNotNil(decrypted)
        XCTAssertEqual(decrypted, plaintext)
    }

    func testEncryptedDiffersFromPlaintext() throws {
        let kp  = makeKeyPair()
        let key = try MeshCrypto.computeSharedKey(ourPrivateKey: kp, theirPublicKey: kp.publicKey)

        let plaintext = Data("test".utf8)
        let encrypted = try MeshCrypto.encrypt(plaintext, using: key)
        XCTAssertNotEqual(encrypted, plaintext)
    }

    func testEachEncryptCallProducesDifferentCiphertext() throws {
        let kp  = makeKeyPair()
        let key = try MeshCrypto.computeSharedKey(ourPrivateKey: kp, theirPublicKey: kp.publicKey)

        let plaintext = Data("same message".utf8)
        let enc1 = try MeshCrypto.encrypt(plaintext, using: key)
        let enc2 = try MeshCrypto.encrypt(plaintext, using: key)
        // Different random nonces → different ciphertexts
        XCTAssertNotEqual(enc1, enc2)
    }

    func testDecryptReturnNilForTamperedCiphertext() throws {
        let kp  = makeKeyPair()
        let key = try MeshCrypto.computeSharedKey(ourPrivateKey: kp, theirPublicKey: kp.publicKey)

        var encrypted = try MeshCrypto.encrypt(Data("secret".utf8), using: key)
        encrypted[encrypted.count - 1] ^= 0xFF  // flip last byte (auth tag)
        XCTAssertNil(MeshCrypto.decrypt(encrypted, using: key))
    }

    func testDecryptReturnNilForWrongKey() throws {
        let alice = makeKeyPair()
        let bob   = makeKeyPair()
        let eve   = makeKeyPair()

        let correctKey = try MeshCrypto.computeSharedKey(ourPrivateKey: alice, theirPublicKey: bob.publicKey)
        let wrongKey   = try MeshCrypto.computeSharedKey(ourPrivateKey: eve,   theirPublicKey: alice.publicKey)

        let encrypted = try MeshCrypto.encrypt(Data("secret".utf8), using: correctKey)
        XCTAssertNil(MeshCrypto.decrypt(encrypted, using: wrongKey))
    }

    func testDecryptReturnNilForTooShortData() throws {
        let kp  = makeKeyPair()
        let key = try MeshCrypto.computeSharedKey(ourPrivateKey: kp, theirPublicKey: kp.publicKey)
        XCTAssertNil(MeshCrypto.decrypt(Data(count: 5), using: key))
    }

    func testPacketFingerprintIsDeterministic() {
        let src     = Data(repeating: 0xAA, count: 32)
        let dst     = Data(repeating: 0xBB, count: 32)
        let payload = Data("payload".utf8)
        let fp1 = MeshCrypto.packetFingerprint(srcBytes: src, dstBytes: dst, payload: payload)
        let fp2 = MeshCrypto.packetFingerprint(srcBytes: src, dstBytes: dst, payload: payload)
        XCTAssertEqual(fp1, fp2)
        XCTAssertEqual(fp1.count, 8)
    }
}

final class MMPCodecTests: XCTestCase {

    func testEncodeDecodeRoundTrip() {
        let src    = MeshID.generate()
        let dst    = MeshID.generate()
        let packet = MeshPacket(type: .msg, srcId: src, dstId: dst,
                                ttl: 42, payload: Data("hello".utf8))

        let encoded = MMPCodec.encode(packet)
        let decoded = MMPCodec.decode(encoded)

        XCTAssertNotNil(decoded)
        XCTAssertEqual(decoded?.type,    packet.type)
        XCTAssertEqual(decoded?.srcId,   packet.srcId)
        XCTAssertEqual(decoded?.dstId,   packet.dstId)
        XCTAssertEqual(decoded?.ttl,     packet.ttl)
        XCTAssertEqual(decoded?.payload, packet.payload)
    }

    func testDecodeReturnsNilForTooShortData() {
        XCTAssertNil(MMPCodec.decode(Data(count: MeshPacket.headerSize - 1)))
    }

    func testEncodedSizeIsHeaderPlusPayload() {
        let packet  = MeshPacket(type: .msg, srcId: .generate(), dstId: .generate(),
                                 payload: Data(count: 100))
        let encoded = MMPCodec.encode(packet)
        XCTAssertEqual(encoded.count, MeshPacket.headerSize + 100)
    }

    func testAllPacketTypesRoundTrip() {
        for type_ in PacketType.allCases {
            let p = MeshPacket(type: type_, srcId: .generate(), dstId: .generate())
            let decoded = MMPCodec.decode(MMPCodec.encode(p))
            XCTAssertEqual(decoded?.type, type_, "Round-trip failed for type \(type_)")
        }
    }
}
