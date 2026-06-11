import Foundation

/// AckManager — tracks outgoing MSG packets and retries if no ACK arrives.
///
/// Direct port of Android's `AckManager`
/// (`app/src/main/java/com/mrit/mesh/reliability/AckManager.kt`), bringing
/// iOS to parity for outgoing ACK retry (PROTOCOL.md §9 — previously a known
/// gap, see PROTOCOL.md §11.6).
///
/// How it works:
///   1. Caller registers an outgoing packet via `trackOutgoing`
///   2. AckManager starts a retry countdown
///   3. When an ACK arrives, caller calls `acknowledge` — packet removed from pending
///   4. If the ACK doesn't arrive within `ackTimeout`, the packet is re-sent
///   5. After `maxRetries` failures, `onDeliveryFailed` is invoked
///
/// ACK packet format (`PacketType.ack` payload):
///   UTF-8 string `"ACK:{fingerprint}"`, where `fingerprint` is the first 8
///   hex chars of `SHA-256(srcId + dstId + payload)` (`MeshCrypto.packetFingerprint`).
public final class AckManager {

    private static let ackTimeout: TimeInterval    = 5.0   // 5 seconds per attempt
    private static let maxRetries                  = 3     // total of 3 attempts before giving up
    private static let checkInterval: TimeInterval = 1.0   // check pending queue every 1s

    /// An outgoing packet waiting for its ACK.
    private struct PendingEntry {
        let packet: MeshPacket
        let address: String
        let fingerprint: String
        var lastSentAt: Date = Date()
        var retryCount: Int = 0
    }

    private var pending: [String: PendingEntry] = [:]   // fingerprint → entry
    private let lock = NSLock()
    private var isRunning = true

    private let onRetry: (MeshPacket, String) -> Void
    private let onDeliveryFailed: (MeshPacket) -> Void

    /// - Parameters:
    ///   - onRetry: called with the original packet and the `address` it was
    ///     last sent to (informational — callers typically re-resolve the
    ///     transport at retry time, see `MeshNode.transmit`).
    ///   - onDeliveryFailed: called once `maxRetries` is exceeded with no ACK.
    public init(onRetry: @escaping (MeshPacket, String) -> Void,
                onDeliveryFailed: @escaping (MeshPacket) -> Void) {
        self.onRetry = onRetry
        self.onDeliveryFailed = onDeliveryFailed
        scheduleRetryCheck()
    }

    deinit {
        lock.lock()
        isRunning = false
        lock.unlock()
    }

    // MARK: - Public API

    /// Begin tracking an outgoing packet. Call this immediately after the first send attempt.
    ///
    /// - Parameter address: the BLE address / transport identifier the packet
    ///   was sent to — kept for logging/diagnostics, mirroring Android's `peerIp`.
    public func trackOutgoing(_ packet: MeshPacket, address: String) {
        let fp = fingerprint(for: packet)
        lock.lock()
        pending[fp] = PendingEntry(packet: packet, address: address, fingerprint: fp)
        lock.unlock()
        print("[AckManager] Tracking packet \(fp) → \(address)")
    }

    /// Mark a packet as acknowledged — removes it from the retry queue.
    /// Call this when an ACK packet's UTF-8 payload arrives.
    public func acknowledge(_ ackPayload: String) {
        guard ackPayload.hasPrefix("ACK:") else { return }
        let fp = String(ackPayload.dropFirst("ACK:".count))

        lock.lock()
        let removed = pending.removeValue(forKey: fp) != nil
        lock.unlock()

        if removed {
            print("[AckManager] ACK received for \(fp) — delivery confirmed")
        }
    }

    /// Build the payload `Data` for an ACK packet in response to a received MSG.
    public func buildAckPayload(for receivedPacket: MeshPacket) -> Data {
        Data("ACK:\(fingerprint(for: receivedPacket))".utf8)
    }

    /// Compute the fingerprint for a packet. Identical packets (same src, dst,
    /// payload) always produce the same fingerprint.
    public func fingerprint(for packet: MeshPacket) -> String {
        MeshCrypto.packetFingerprint(
            srcBytes: packet.srcId.bytes,
            dstBytes: packet.dstId.bytes,
            payload:  packet.payload
        )
    }

    /// Number of packets currently waiting for ACK.
    public var pendingCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return pending.count
    }

    // MARK: - Retry loop

    private func scheduleRetryCheck() {
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + Self.checkInterval) { [weak self] in
            guard let self = self else { return }
            self.lock.lock()
            let running = self.isRunning
            self.lock.unlock()
            guard running else { return }

            self.checkAndRetry()
            self.scheduleRetryCheck()
        }
    }

    private func checkAndRetry() {
        let now = Date()
        var toRetry:   [PendingEntry] = []
        var toAbandon: [PendingEntry] = []

        lock.lock()
        var updates: [(String, PendingEntry)] = []
        for (fp, entry) in pending {
            let elapsed = now.timeIntervalSince(entry.lastSentAt)
            guard elapsed >= Self.ackTimeout else { continue }

            if entry.retryCount >= Self.maxRetries {
                toAbandon.append(entry)
            } else {
                var updated = entry
                updated.retryCount += 1
                updated.lastSentAt = now
                updates.append((fp, updated))
                toRetry.append(updated)
            }
        }
        for (fp, updated) in updates {
            pending[fp] = updated
        }
        for entry in toAbandon {
            pending.removeValue(forKey: entry.fingerprint)
        }
        lock.unlock()

        for entry in toRetry {
            print("[AckManager] Retry \(entry.retryCount)/\(Self.maxRetries) for packet \(entry.fingerprint)")
            onRetry(entry.packet, entry.address)
        }

        for entry in toAbandon {
            print("[AckManager] Delivery failed after \(Self.maxRetries) retries: \(entry.fingerprint)")
            onDeliveryFailed(entry.packet)
        }
    }
}
