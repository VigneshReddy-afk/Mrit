import Foundation

/// Mrit — the high-level, app-facing entry point to the MRIT mesh (Phase 5).
///
/// `MeshNode` is the Layer 2/3 protocol engine: packets, routing, encryption,
/// store-and-forward. `Mrit` wraps it in a small declarative configuration API
/// so application code never touches a `MeshPacket` directly.
///
/// Mirrors the Android `Mrit` DSL in
/// `app/src/main/java/com/mrit/mesh/dsl/Mrit.kt` — the two are intentionally
/// symmetric so a shared app design doc reads the same on both platforms.
///
/// Example:
/// ```swift
/// let mesh = Mrit { config in
///     config.onMessage { msg in print("From \(msg.from.shortId): \(msg.text)") }
///     config.onFile    { file in save(file.fileName, file.data) }
///     config.onPeers   { peers in updatePeerList(peers) }
/// }
///
/// mesh.send(to: peerId, text: "Hello mesh!")
/// mesh.sendFile(to: peerId, name: "map.png", data: bytes)
/// mesh.sos("Need help — twisted ankle, 2km north of trailhead")
///
/// // ... later
/// mesh.stop()
/// ```
public final class Mrit {

    /// The underlying protocol engine. Most apps never need to touch this directly.
    public let node: MeshNode

    /// Our own identity on the mesh — share this with others so they can message us.
    public var ourId: MeshID { node.ourId }

    /// Create, configure, and start a mesh node in one call.
    ///
    /// - Parameter configure: closure registering message/file/peer handlers,
    ///   evaluated once before the node starts.
    public init(configure: (MritConfig) -> Void = { _ in }) {
        let config = MritConfig()
        configure(config)

        let node = MeshNode()
        node.onMessageReceived = { msg in config.messageHandlers.forEach { $0(msg) } }
        node.onFileReceived    = { name, data in
            let file = ReceivedFile(fileName: name, data: data)
            config.fileHandlers.forEach { $0(file) }
        }
        node.onPeersChanged = { peers in config.peerHandlers.forEach { $0(peers) } }

        self.node = node
        node.start()
    }

    // ── Send API ───────────────────────────────────────────────────────────────

    /// Send an end-to-end encrypted text message to `dest`. Routes multi-hop automatically.
    public func send(to dest: MeshID, text: String) {
        node.sendMessage(to: dest, text: text)
    }

    /// Send a file of any size to `dest` — automatically chunked (32KB) and encrypted.
    public func sendFile(to dest: MeshID, name: String, data: Data) {
        node.sendFile(to: dest, name: name, data: data)
    }

    /// Broadcast an unencrypted emergency message to every reachable node.
    public func sos(_ message: String) {
        node.sendSOS(message)
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    /// Stop the mesh node and release all transport resources.
    public func stop() {
        node.stop()
    }
}

/// Configuration block for `Mrit.init`.
///
/// Register one or more handlers per event type — every registered handler is
/// called for every event of that type.
public final class MritConfig {
    fileprivate var messageHandlers: [(MeshNode.IncomingMessage) -> Void] = []
    fileprivate var fileHandlers:    [(ReceivedFile) -> Void] = []
    fileprivate var peerHandlers:    [([MeshNode.PeerInfo]) -> Void] = []

    public init() {}

    /// Called for every text message (including SOS broadcasts) addressed to or seen by us.
    public func onMessage(_ handler: @escaping (MeshNode.IncomingMessage) -> Void) {
        messageHandlers.append(handler)
    }

    /// Called once per file transfer, after all chunks have arrived and been reassembled.
    public func onFile(_ handler: @escaping (ReceivedFile) -> Void) {
        fileHandlers.append(handler)
    }

    /// Called whenever the set of reachable peers changes (peer joins, leaves, or times out).
    public func onPeers(_ handler: @escaping ([MeshNode.PeerInfo]) -> Void) {
        peerHandlers.append(handler)
    }
}

/// A fully reassembled file received from a peer.
/// Mirrors Android's `MeshNode.ReceivedFile`.
public struct ReceivedFile {
    public let fileName: String
    public let data: Data
}
