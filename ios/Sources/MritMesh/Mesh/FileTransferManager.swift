import Foundation

/// FileTransferManager (iOS) — splits files into 32KB encrypted chunks and reassembles them.
///
/// Binary-compatible with the Android FileTransferManager.
/// Chunk payload header format (identical on both platforms):
///   [ 16 bytes : transfer ID  ]
///   [  4 bytes : chunk index  ]  big-endian UInt32
///   [  4 bytes : total chunks ]  big-endian UInt32
///   [  4 bytes : total size   ]  big-endian UInt32
///   [  2 bytes : name length  ]  big-endian UInt16  (non-zero only in chunk 0)
///   [  N bytes : filename     ]  UTF-8, only in chunk 0
///   [  M bytes : chunk data   ]
class FileTransferManager {

    private static let chunkSize = 32 * 1024  // 32 KB per chunk

    private let ourId:      MeshID
    private let sendPacket: (MeshPacket, MeshID) -> Void
    private let onFileReceived: (String, Data) -> Void

    /// In-progress reassembly sessions: transferId → session
    private var sessions = [String: ReassemblySession]()
    private let lock     = NSLock()

    init(
        ourId:          MeshID,
        sendPacket:     @escaping (MeshPacket, MeshID) -> Void,
        onFileReceived: @escaping (String, Data) -> Void
    ) {
        self.ourId          = ourId
        self.sendPacket     = sendPacket
        self.onFileReceived = onFileReceived
    }

    // ── Send ───────────────────────────────────────────────────────────────────

    func send(to dest: MeshID, fileName: String, data: Data) {
        let transferId  = Data((0..<16).map { _ in UInt8.random(in: 0...255) })
        let totalChunks = (data.count + Self.chunkSize - 1) / Self.chunkSize

        for i in 0..<totalChunks {
            let start     = i * Self.chunkSize
            let end       = min(start + Self.chunkSize, data.count)
            let chunkData = data[start..<end]
            let name      = (i == 0) ? fileName : ""
            let payload   = buildPayload(
                transferId: transferId, chunkIndex: i, totalChunks: totalChunks,
                totalSize: data.count, fileName: name, chunk: chunkData
            )
            let packet = MeshPacket(
                type: .fileChunk, srcId: ourId, dstId: dest, payload: payload
            )
            sendPacket(packet, dest)
        }
    }

    // ── Receive ────────────────────────────────────────────────────────────────

    func onChunkReceived(srcId: MeshID, payload: Data) {
        guard let header = parseHeader(payload) else { return }

        let key = header.transferId.map { String(format: "%02x", $0) }.joined()
        lock.lock()
        var session = sessions[key] ?? ReassemblySession(
            transferId:  header.transferId,
            totalChunks: header.totalChunks,
            totalSize:   header.totalSize
        )
        if !header.fileName.isEmpty { session.fileName = header.fileName }
        session.chunks[header.chunkIndex] = header.chunkData

        let complete = session.chunks.count == session.totalChunks
        if complete { sessions.removeValue(forKey: key) } else { sessions[key] = session }
        lock.unlock()

        if complete, let assembled = session.assemble() {
            onFileReceived(session.fileName, assembled)
        }
    }

    // ── Payload codec ──────────────────────────────────────────────────────────

    private func buildPayload(
        transferId: Data, chunkIndex: Int, totalChunks: Int,
        totalSize: Int, fileName: String, chunk: Data
    ) -> Data {
        let nameData = Data(fileName.utf8)
        var buf = Data(capacity: 30 + nameData.count + chunk.count)
        buf.append(contentsOf: transferId)                             // 16 bytes
        buf.appendBigEndian(UInt32(chunkIndex))                        //  4 bytes
        buf.appendBigEndian(UInt32(totalChunks))                       //  4 bytes
        buf.appendBigEndian(UInt32(totalSize))                         //  4 bytes
        buf.appendBigEndian(UInt16(nameData.count))                    //  2 bytes
        buf.append(contentsOf: nameData)                               //  N bytes
        buf.append(contentsOf: chunk)                                  //  M bytes
        return buf
    }

    private func parseHeader(_ data: Data) -> ChunkHeader? {
        guard data.count >= 30 else { return nil }
        var offset = 0

        let transferId  = data[offset..<offset+16]; offset += 16
        let chunkIndex  = Int(data.readBigEndianUInt32(at: offset)); offset += 4
        let totalChunks = Int(data.readBigEndianUInt32(at: offset)); offset += 4
        let totalSize   = Int(data.readBigEndianUInt32(at: offset)); offset += 4
        let nameLen     = Int(data.readBigEndianUInt16(at: offset)); offset += 2

        guard data.count >= offset + nameLen else { return nil }
        let fileName = String(data: data[offset..<offset+nameLen], encoding: .utf8) ?? ""
        offset += nameLen

        let chunkData = data[offset...]
        return ChunkHeader(
            transferId:  Data(transferId), chunkIndex: chunkIndex, totalChunks: totalChunks,
            totalSize: totalSize, fileName: fileName, chunkData: Data(chunkData)
        )
    }

    // ── Data models ────────────────────────────────────────────────────────────

    private struct ChunkHeader {
        let transferId:  Data
        let chunkIndex:  Int
        let totalChunks: Int
        let totalSize:   Int
        let fileName:    String
        let chunkData:   Data
    }

    private struct ReassemblySession {
        let transferId:  Data
        let totalChunks: Int
        let totalSize:   Int
        var fileName:    String = ""
        var chunks:      [Int: Data] = [:]

        func assemble() -> Data? {
            guard chunks.count == totalChunks else { return nil }
            var result = Data(capacity: totalSize)
            for i in 0..<totalChunks {
                guard let chunk = chunks[i] else { return nil }
                result.append(chunk)
            }
            return result
        }
    }
}

// ── Data extensions ────────────────────────────────────────────────────────────

private extension Data {
    mutating func appendBigEndian(_ value: UInt32) {
        var v = value.bigEndian
        withUnsafeBytes(of: &v) { append(contentsOf: $0) }
    }
    mutating func appendBigEndian(_ value: UInt16) {
        var v = value.bigEndian
        withUnsafeBytes(of: &v) { append(contentsOf: $0) }
    }
    func readBigEndianUInt32(at offset: Int) -> UInt32 {
        guard count >= offset + 4 else { return 0 }
        return UInt32(self[offset]) << 24 | UInt32(self[offset+1]) << 16
             | UInt32(self[offset+2]) << 8 | UInt32(self[offset+3])
    }
    func readBigEndianUInt16(at offset: Int) -> UInt16 {
        guard count >= offset + 2 else { return 0 }
        return UInt16(self[offset]) << 8 | UInt16(self[offset+1])
    }
}
