import Foundation

/// MMPCodec — encodes and decodes MeshPackets to/from raw bytes.
///
/// The binary format is IDENTICAL to the Android MMPEncoder/MMPDecoder.
/// All multi-byte fields are big-endian (network byte order).
///
/// Wire layout:
///   [0]      VERSION   1 byte
///   [1]      TYPE      1 byte
///   [2..33]  SRC_ID    32 bytes
///   [34..65] DST_ID    32 bytes
///   [66]     TTL       1 byte (unsigned)
///   [67..68] LENGTH    2 bytes (unsigned short, big-endian)
///   [69..]   PAYLOAD   LENGTH bytes
public enum MMPCodec {

    // ── Encode ─────────────────────────────────────────────────────────────────

    /// Serialize a MeshPacket to bytes ready for transmission.
    public static func encode(_ packet: MeshPacket) -> Data {
        var data = Data(capacity: MeshPacket.headerSize + packet.payload.count)

        data.append(packet.version)                         // [0]      VERSION
        data.append(packet.type.rawValue)                   // [1]      TYPE
        data.append(contentsOf: packet.srcId.bytes)         // [2..33]  SRC_ID
        data.append(contentsOf: packet.dstId.bytes)         // [34..65] DST_ID
        data.append(UInt8(packet.ttl & 0xFF))               // [66]     TTL
        let len = UInt16(packet.payload.count)
        data.append(UInt8(len >> 8))                        // [67]     LENGTH high byte
        data.append(UInt8(len & 0xFF))                      // [68]     LENGTH low byte
        data.append(contentsOf: packet.payload)             // [69..]   PAYLOAD

        return data
    }

    // ── Decode ─────────────────────────────────────────────────────────────────

    /// Deserialize raw bytes into a MeshPacket.
    /// Returns nil if the data is malformed or too short — never throws.
    public static func decode(_ data: Data) -> MeshPacket? {
        guard data.count >= MeshPacket.headerSize else { return nil }

        let version = data[0]

        guard let type = PacketType(rawValue: data[1]) else { return nil }

        guard
            let srcId = MeshID.fromBytes(data[2...]),
            let dstId = MeshID.fromBytes(data[34...])
        else { return nil }

        let ttl           = Int(data[66])
        let payloadLength = Int(data[67]) << 8 | Int(data[68])

        guard data.count >= MeshPacket.headerSize + payloadLength else { return nil }

        let payload = data[MeshPacket.headerSize ..< MeshPacket.headerSize + payloadLength]

        return MeshPacket(
            type:    type,
            srcId:   srcId,
            dstId:   dstId,
            ttl:     ttl,
            payload: Data(payload),
            version: version
        )
    }
}
