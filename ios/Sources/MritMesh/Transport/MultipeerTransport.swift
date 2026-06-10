import Foundation
import MultipeerConnectivity

/// MultipeerTransport — sends and receives MeshPackets over Apple MultipeerConnectivity.
///
/// Role: Both discovery AND data transfer (MultipeerConnectivity handles both).
///
/// Platform note:
///   iOS uses MultipeerConnectivity (WiFi P2P + Bluetooth).
///   Android uses WiFi Direct + BLE separately.
///   These are NOT directly compatible at the transport layer.
///   iOS↔iOS and Android↔Android work natively.
///   As of Phase 5, the MMP packet format, AES-256-GCM wire format, and EC
///   public key encoding (65-byte x963) are byte-for-byte identical on both
///   platforms — see PROTOCOL.md. A real iOS↔Android *link* still requires a
///   shared transport, which is the Phase 6 BLE GATT bridge.
///
/// MeshID handshake:
///   When a peer connects, we immediately send a DISCOVER packet containing
///   our EC P-256 public key (65-byte x963) — identical handshake logic to Android.
///
/// Service type: "mrit-mesh"
///   Must be ≤15 chars, only lowercase ASCII letters, digits, and hyphens.
public class MultipeerTransport: NSObject {

    private static let serviceType = "mrit-mesh"

    private let ourId:      MeshID
    private let keyManager: KeyManager
    private let myPeerID:   MCPeerID
    private var session:    MCSession!
    private var advertiser: MCNearbyServiceAdvertiser!
    private var browser:    MCNearbyServiceBrowser!

    /// Active peer connections: MCPeerID displayName → MeshID
    private var peerIdMap  = [String: MeshID]()   // MCPeerID.displayName → MeshID
    private var peerIpMap  = [String: MCPeerID]()  // MeshID hex → MCPeerID (for sending)
    private let lock       = NSLock()

    // ── Callbacks ──────────────────────────────────────────────────────────────

    /// Called when a non-DISCOVER data packet arrives
    var onPacketReceived:     ((MeshPacket) -> Void)?

    /// Called when a handshake completes and a new peer is identified
    var onPeerHandshake:      ((PeerHandshake) -> Void)?

    /// Called when a peer disconnects
    var onPeerDisconnected:   ((MeshID) -> Void)?

    // ── Init ───────────────────────────────────────────────────────────────────

    public init(ourId: MeshID, keyManager: KeyManager) {
        self.ourId      = ourId
        self.keyManager = keyManager
        // Use shortId as display name so peers can recognize us
        self.myPeerID   = MCPeerID(displayName: ourId.shortId)
        super.init()
        setupSession()
    }

    private func setupSession() {
        session    = MCSession(peer: myPeerID, securityIdentity: nil, encryptionPreference: .none)
        advertiser = MCNearbyServiceAdvertiser(peer: myPeerID, discoveryInfo: nil,
                                               serviceType: Self.serviceType)
        browser    = MCNearbyServiceBrowser(peer: myPeerID, serviceType: Self.serviceType)

        session.delegate    = self
        advertiser.delegate = self
        browser.delegate    = self
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    public func start() {
        advertiser.startAdvertisingPeer()
        browser.startBrowsingForPeers()
    }

    public func stop() {
        advertiser.stopAdvertisingPeer()
        browser.stopBrowsingForPeers()
        session.disconnect()
    }

    // ── Send ───────────────────────────────────────────────────────────────────

    /// Send a MeshPacket to a specific peer (identified by their MeshID).
    public func send(_ packet: MeshPacket, to meshId: MeshID) {
        lock.lock()
        let peerID = peerIpMap[meshId.hexString]
        lock.unlock()
        guard let peerID else { return }
        send(packet, toPeerID: peerID)
    }

    /// Broadcast a packet to all connected peers.
    public func broadcast(_ packet: MeshPacket) {
        guard !session.connectedPeers.isEmpty else { return }
        let data = MMPCodec.encode(packet)
        try? session.send(data, toPeers: session.connectedPeers, with: .reliable)
    }

    private func send(_ packet: MeshPacket, toPeerID peerID: MCPeerID) {
        guard session.connectedPeers.contains(peerID) else { return }
        let data = MMPCodec.encode(packet)
        try? session.send(data, toPeers: [peerID], with: .reliable)
    }

    // ── Handshake ──────────────────────────────────────────────────────────────

    private func sendHandshake(to peerID: MCPeerID) {
        let discover = MeshPacket(
            type:    .discover,
            srcId:   ourId,
            dstId:   .broadcast,
            ttl:     1,
            payload: keyManager.publicKeyBytes   // EC P-256 public key
        )
        send(discover, toPeerID: peerID)
    }

    // ── Data model ─────────────────────────────────────────────────────────────

    public struct PeerHandshake {
        public let meshId:         MeshID
        public let publicKeyBytes: Data
    }
}

// ── MCSessionDelegate ──────────────────────────────────────────────────────────

extension MultipeerTransport: MCSessionDelegate {

    public func session(_ session: MCSession, peer peerID: MCPeerID,
                        didChange state: MCSessionState) {
        switch state {
        case .connected:
            // Initiate our side of the handshake immediately on connect
            sendHandshake(to: peerID)

        case .notConnected:
            lock.lock()
            if let meshId = peerIdMap.removeValue(forKey: peerID.displayName) {
                peerIpMap.removeValue(forKey: meshId.hexString)
                lock.unlock()
                onPeerDisconnected?(meshId)
            } else {
                lock.unlock()
            }

        case .connecting:
            break  // Waiting for full connection

        @unknown default:
            break
        }
    }

    public func session(_ session: MCSession, didReceive data: Data,
                        fromPeer peerID: MCPeerID) {
        guard let packet = MMPCodec.decode(data) else { return }

        if packet.type == .discover {
            // Handshake packet — register this peer
            lock.lock()
            peerIdMap[peerID.displayName]     = packet.srcId
            peerIpMap[packet.srcId.hexString] = peerID
            lock.unlock()

            let handshake = PeerHandshake(meshId: packet.srcId, publicKeyBytes: packet.payload)
            onPeerHandshake?(handshake)
        } else {
            onPacketReceived?(packet)
        }
    }

    // Required but unused delegate methods
    public func session(_ session: MCSession, didReceive stream: InputStream,
                        withName streamName: String, fromPeer peerID: MCPeerID) {}
    public func session(_ session: MCSession, didStartReceivingResourceWithName resourceName: String,
                        fromPeer peerID: MCPeerID, with progress: Progress) {}
    public func session(_ session: MCSession, didFinishReceivingResourceWithName resourceName: String,
                        fromPeer peerID: MCPeerID, at localURL: URL?, withError error: Error?) {}
}

// ── MCNearbyServiceAdvertiserDelegate ─────────────────────────────────────────

extension MultipeerTransport: MCNearbyServiceAdvertiserDelegate {

    public func advertiser(_ advertiser: MCNearbyServiceAdvertiser,
                           didReceiveInvitationFromPeer peerID: MCPeerID,
                           withContext context: Data?,
                           invitationHandler: @escaping (Bool, MCSession?) -> Void) {
        // Always accept invitations from other MRIT nodes
        invitationHandler(true, session)
    }
}

// ── MCNearbyServiceBrowserDelegate ────────────────────────────────────────────

extension MultipeerTransport: MCNearbyServiceBrowserDelegate {

    public func browser(_ browser: MCNearbyServiceBrowser,
                        foundPeer peerID: MCPeerID, withDiscoveryInfo info: [String: String]?) {
        // Invite every discovered MRIT peer
        browser.invitePeer(peerID, to: session, withContext: nil, timeout: 10)
    }

    public func browser(_ browser: MCNearbyServiceBrowser, lostPeer peerID: MCPeerID) {
        // Peer went out of range — handled in didChange state: .notConnected
    }

    public func browser(_ browser: MCNearbyServiceBrowser,
                        didNotStartBrowsingForPeers error: Error) {
        print("[MultipeerTransport] Browse error: \(error.localizedDescription)")
    }
}
