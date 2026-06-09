import Foundation

/// PacketType — all MMP v0.1 packet types.
/// Raw values match Android's `PacketType.byte` exactly (binary compatible).
public enum PacketType: UInt8, CaseIterable {
    case msg       = 0x01   // User text message
    case ack       = 0x02   // Delivery acknowledgement
    case discover  = 0x03   // Node discovery / handshake
    case route     = 0x04   // AODV routing control (RREQ / RREP)
    case sos       = 0x05   // Emergency broadcast — max TTL, never dropped
    case fileChunk = 0x06   // File transfer chunk — reassembled by FileTransferManager
}

/// MeshPacket — the fundamental unit of data in the MRIT Mobile Mesh Protocol (MMP).
///
/// Binary wire format (69-byte fixed header + variable payload):
/// ```
/// ┌──────────┬──────────┬────────────┬────────────┬──────────┬──────────┬──────────────┐
/// │ VERSION  │   TYPE   │   SRC_ID   │   DST_ID   │   TTL    │  LENGTH  │   PAYLOAD    │
/// │  1 byte  │  1 byte  │  32 bytes  │  32 bytes  │  1 byte  │  2 bytes │   N bytes    │
/// └──────────┴──────────┴────────────┴────────────┴──────────┴──────────┴──────────────┘
/// ```
/// This format is IDENTICAL to the Android implementation — packets are binary compatible.
public struct MeshPacket {

    public static let mmpVersion: UInt8 = 0x01
    public static let headerSize        = 69
    public static let maxPayloadSize    = 65535
    public static let defaultTTL        = 64
    public static let sosTTL            = 255

    public let version: UInt8
    public let type:    PacketType
    public let srcId:   MeshID
    public let dstId:   MeshID
    public let ttl:     Int
    public let payload: Data

    public init(
        type:    PacketType,
        srcId:   MeshID,
        dstId:   MeshID,
        ttl:     Int     = MeshPacket.defaultTTL,
        payload: Data    = Data(),
        version: UInt8   = MeshPacket.mmpVersion
    ) {
        self.version = version
        self.type    = type
        self.srcId   = srcId
        self.dstId   = dstId
        self.ttl     = max(0, min(ttl, 255))
        self.payload = payload
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /// Return a copy with TTL decremented by 1. Called at every relay hop.
    public func decrementingTTL() -> MeshPacket {
        MeshPacket(type: type, srcId: srcId, dstId: dstId,
                   ttl: ttl - 1, payload: payload, version: version)
    }

    public var isAlive:      Bool { ttl > 0 }
    public var isBroadcast:  Bool { dstId == .broadcast }

    public func isForUs(_ ourId: MeshID) -> Bool { dstId == ourId }
}
