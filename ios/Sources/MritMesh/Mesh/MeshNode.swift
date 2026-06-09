import Foundation
import CryptoKit

/// MeshNode — the complete MRIT mesh stack for iOS. Phase 4.
///
/// API is intentionally symmetric with the Android MeshNode:
///   node.sendMessage(to:, text:)    — encrypted unicast text
///   node.sendSOS(_:)                — unencrypted broadcast emergency
///   node.sendFile(to:, name:, data:)— encrypted chunked file transfer
///   node.onMessageReceived          — callback for incoming messages
///   node.onFileReceived             — callback for completed file transfers
///   node.onPeersChanged             — callback for peer list updates
///
/// Crypto: ECDH (P-256) + AES-256-GCM — binary compatible with Android.
/// Transport: MultipeerConnectivity (iOS) — iOS↔iOS only in Phase 4.
/// iOS↔Android cross-platform transport: Phase 5 (BLE GATT bridge).
public class MeshNode {

    // ── Identity & crypto ──────────────────────────────────────────────────────

    public let ourId:     MeshID
    public let keyManager = KeyManager()

    // ── Components ─────────────────────────────────────────────────────────────

    private var transport:      MultipeerTransport!
    private var peerRegistry    = [String: PeerEntry]()     // meshId hex → PeerEntry
    private var sharedKeys      = [String: SymmetricKey]()  // meshId hex → AES key
    private var routingTable    = [String: String]()        // dest hex  → next-hop hex
    private var packetStore     = [String: [MeshPacket]]()  // dest hex  → queued packets
    private let lock            = NSLock()

    private var fileManager:    FileTransferManager!

    // ── Callbacks ──────────────────────────────────────────────────────────────

    public var onMessageReceived: ((IncomingMessage) -> Void)?
    public var onFileReceived:    ((String, Data) -> Void)?     // filename, data
    public var onPeersChanged:    (([PeerInfo]) -> Void)?

    // ── Init ───────────────────────────────────────────────────────────────────

    public init() {
        self.ourId    = Self.loadOrCreateMeshId()
        self.transport = MultipeerTransport(ourId: ourId, keyManager: keyManager)
        self.fileManager = FileTransferManager(
            ourId:    ourId,
            sendPacket: { [weak self] packet, dest in self?.dispatchPacket(packet, to: dest) },
            onFileReceived: { [weak self] name, data in self?.onFileReceived?(name, data) }
        )
        wireTransport()
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    public func start() {
        transport.start()
        print("[MeshNode] Started — id=\(ourId.shortId)")
    }

    public func stop() {
        transport.stop()
    }

    // ── Send API ───────────────────────────────────────────────────────────────

    /// Send an encrypted text message to [to].
    public func sendMessage(to dest: MeshID, text: String) {
        let plaintext = Data(text.utf8)
        let payload   = encrypt(plaintext, for: dest) ?? plaintext
        let packet    = MeshPacket(type: .msg, srcId: ourId, dstId: dest, payload: payload)
        dispatchPacket(packet, to: dest)
    }

    /// Broadcast an unencrypted SOS to all connected peers.
    public func sendSOS(_ message: String) {
        let packet = MeshPacket(
            type: .sos, srcId: ourId, dstId: .broadcast,
            ttl: MeshPacket.sosTTL, payload: Data(message.utf8)
        )
        transport.broadcast(packet)
    }

    /// Send a file to [to] — split into 32KB encrypted chunks.
    public func sendFile(to dest: MeshID, name: String, data: Data) {
        fileManager.send(to: dest, fileName: name, data: data)
    }

    // ── Internal — routing ─────────────────────────────────────────────────────

    private func dispatchPacket(_ packet: MeshPacket, to dest: MeshID) {
        if dest == .broadcast {
            transport.broadcast(packet)
            return
        }
        // Direct route known?
        lock.lock()
        let hasDirectRoute = peerRegistry[dest.hexString] != nil
        lock.unlock()

        if hasDirectRoute {
            transport.send(packet, to: dest)
        } else {
            // Store and broadcast RREQ
            lock.lock()
            packetStore[dest.hexString, default: []].append(packet)
            lock.unlock()
            broadcastRREQ(for: dest)
        }
    }

    private func route(_ packet: MeshPacket) {
        if packet.isForUs(ourId) {
            deliverToApp(packet)
            return
        }
        if packet.isBroadcast {
            deliverToApp(packet)
            if packet.isAlive { transport.broadcast(packet.decrementingTTL()) }
            return
        }
        guard packet.isAlive else { return }

        lock.lock()
        let hasRoute = peerRegistry[packet.dstId.hexString] != nil
        lock.unlock()

        if hasRoute {
            transport.send(packet.decrementingTTL(), to: packet.dstId)
        } else {
            lock.lock()
            packetStore[packet.dstId.hexString, default: []].append(packet)
            lock.unlock()
            broadcastRREQ(for: packet.dstId)
        }
    }

    private func deliverToApp(_ packet: MeshPacket) {
        switch packet.type {
        case .msg:
            let plaintext = decrypt(packet.payload, from: packet.srcId) ?? packet.payload
            let text = String(data: plaintext, encoding: .utf8) ?? "<binary>"
            onMessageReceived?(IncomingMessage(from: packet.srcId, text: text))
            sendAck(for: packet)

        case .ack:
            // ACK received — delivery confirmed (AckManager in Phase 5)
            break

        case .sos:
            let text = String(data: packet.payload, encoding: .utf8) ?? "<sos>"
            onMessageReceived?(IncomingMessage(from: packet.srcId, text: "🆘 SOS: \(text)", isSOS: true))

        case .route:
            handleRoutingPacket(packet)

        case .fileChunk:
            let plaintext = decrypt(packet.payload, from: packet.srcId) ?? packet.payload
            fileManager.onChunkReceived(srcId: packet.srcId, payload: plaintext)

        case .discover:
            break  // Handled in transport layer
        }
    }

    // ── ACK ────────────────────────────────────────────────────────────────────

    private func sendAck(for original: MeshPacket) {
        let fp      = MeshCrypto.packetFingerprint(
            srcBytes: original.srcId.bytes,
            dstBytes: original.dstId.bytes,
            payload:  original.payload
        )
        let ack = MeshPacket(
            type: .ack, srcId: ourId, dstId: original.srcId,
            payload: Data("ACK:\(fp)".utf8)
        )
        transport.send(ack, to: original.srcId)
    }

    // ── RREQ / RREP ────────────────────────────────────────────────────────────

    private var seenRequests = Set<String>()

    private func broadcastRREQ(for destination: MeshID) {
        let reqId   = UUID().uuidString.prefix(8).lowercased()
        let payload = "RREQ:\(reqId):\(destination.hexString)"
        let rreq    = MeshPacket(
            type: .route, srcId: ourId, dstId: destination,
            ttl: MeshPacket.defaultTTL, payload: Data(payload.utf8)
        )
        seenRequests.insert(String(reqId))
        transport.broadcast(rreq)
    }

    private func handleRoutingPacket(_ packet: MeshPacket) {
        guard let payload = String(data: packet.payload, encoding: .utf8) else { return }

        if payload.hasPrefix("RREQ:") {
            handleRREQ(packet, payload: payload)
        } else if payload.hasPrefix("RREP:") {
            handleRREP(packet, payload: payload)
        }
    }

    private func handleRREQ(_ packet: MeshPacket, payload: String) {
        let parts = payload.split(separator: ":").map(String.init)
        guard parts.count >= 3 else { return }
        let reqId   = parts[1]
        let destHex = parts[2]

        guard !seenRequests.contains(reqId) else { return }
        seenRequests.insert(reqId)

        guard let destination = MeshID.fromHex(destHex) else { return }

        if destination == ourId {
            sendRREP(requestId: reqId, destination: destination, replyTo: packet.srcId)
        } else {
            lock.lock()
            let knowsDest = peerRegistry[destination.hexString] != nil
            lock.unlock()
            if knowsDest {
                sendRREP(requestId: reqId, destination: destination, replyTo: packet.srcId)
            } else if packet.isAlive {
                transport.broadcast(packet.decrementingTTL())
            }
        }
    }

    private func handleRREP(_ packet: MeshPacket, payload: String) {
        let parts = payload.split(separator: ":").map(String.init)
        guard parts.count >= 3, let dest = MeshID.fromHex(parts[2]) else { return }

        // Record route
        lock.lock()
        routingTable[dest.hexString] = packet.srcId.hexString
        lock.unlock()

        if packet.dstId == ourId {
            // We are original requester — flush stored packets
            flushStored(for: dest)
        } else if packet.isAlive {
            transport.send(packet.decrementingTTL(), to: packet.dstId)
        }
    }

    private func sendRREP(requestId: String, destination: MeshID, replyTo: MeshID) {
        let rrep = MeshPacket(
            type: .route, srcId: ourId, dstId: replyTo,
            ttl: MeshPacket.defaultTTL,
            payload: Data("RREP:\(requestId):\(destination.hexString)".utf8)
        )
        transport.send(rrep, to: replyTo)
    }

    private func flushStored(for destination: MeshID) {
        lock.lock()
        let queued = packetStore.removeValue(forKey: destination.hexString) ?? []
        lock.unlock()
        queued.forEach { transport.send($0, to: destination) }
    }

    // ── Peer handshake ─────────────────────────────────────────────────────────

    private func wireTransport() {
        transport.onPeerHandshake = { [weak self] handshake in
            self?.handleHandshake(handshake)
        }
        transport.onPacketReceived = { [weak self] packet in
            self?.handleIncoming(packet)
        }
        transport.onPeerDisconnected = { [weak self] meshId in
            self?.removePeer(meshId)
        }
    }

    private func handleHandshake(_ handshake: MultipeerTransport.PeerHandshake) {
        // Derive shared key from peer's public key
        var sharedKey: SymmetricKey?
        if let peerPubKey = KeyManager.publicKeyFromBytes(handshake.publicKeyBytes) {
            sharedKey = try? MeshCrypto.computeSharedKey(
                ourPrivateKey:  keyManager.privateKey,
                theirPublicKey: peerPubKey
            )
        }

        lock.lock()
        let entry = PeerEntry(meshId: handshake.meshId)
        peerRegistry[handshake.meshId.hexString] = entry
        if let key = sharedKey { sharedKeys[handshake.meshId.hexString] = key }
        lock.unlock()

        flushStored(for: handshake.meshId)
        publishPeers()
        print("[MeshNode] Peer registered: \(handshake.meshId.shortId) encrypted=\(sharedKey != nil)")
    }

    private func handleIncoming(_ packet: MeshPacket) {
        lock.lock()
        peerRegistry[packet.srcId.hexString]?.lastSeen = Date()
        lock.unlock()
        route(packet)
    }

    private func removePeer(_ meshId: MeshID) {
        lock.lock()
        peerRegistry.removeValue(forKey: meshId.hexString)
        sharedKeys.removeValue(forKey: meshId.hexString)
        lock.unlock()
        publishPeers()
    }

    private func publishPeers() {
        lock.lock()
        let list = peerRegistry.values.map { PeerInfo(meshId: $0.meshId) }
        lock.unlock()
        onPeersChanged?(list)
    }

    // ── Encryption ─────────────────────────────────────────────────────────────

    private func encrypt(_ plaintext: Data, for dest: MeshID) -> Data? {
        guard dest != .broadcast else { return nil }
        lock.lock()
        let key = sharedKeys[dest.hexString]
        lock.unlock()
        guard let key else { return nil }
        return try? MeshCrypto.encrypt(plaintext, using: key)
    }

    private func decrypt(_ ciphertext: Data, from src: MeshID) -> Data? {
        lock.lock()
        let key = sharedKeys[src.hexString]
        lock.unlock()
        guard let key else { return nil }
        return MeshCrypto.decrypt(ciphertext, using: key)
    }

    // ── MeshID persistence ─────────────────────────────────────────────────────

    private static let meshIdKey = "mrit.meshId"

    private static func loadOrCreateMeshId() -> MeshID {
        if let hex = UserDefaults.standard.string(forKey: meshIdKey),
           let id  = MeshID.fromHex(hex) { return id }
        let newId = MeshID.generate()
        UserDefaults.standard.set(newId.hexString, forKey: meshIdKey)
        return newId
    }

    // ── Data models ────────────────────────────────────────────────────────────

    private struct PeerEntry {
        let meshId: MeshID
        var lastSeen = Date()
    }

    public struct PeerInfo {
        public let meshId: MeshID
    }

    public struct IncomingMessage {
        public let from:      MeshID
        public let text:      String
        public let isSOS:     Bool
        public let timestamp: Date

        init(from: MeshID, text: String, isSOS: Bool = false) {
            self.from      = from
            self.text      = text
            self.isSOS     = isSOS
            self.timestamp = Date()
        }
    }
}
