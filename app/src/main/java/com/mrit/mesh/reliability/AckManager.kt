package com.mrit.mesh.reliability

import android.util.Log
import com.mrit.mesh.core.MeshPacket
import com.mrit.mesh.crypto.MeshCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * AckManager — tracks outgoing MSG packets and retries if no ACK arrives.
 *
 * How it works:
 *   1. Caller registers an outgoing packet via [trackOutgoing]
 *   2. AckManager starts a retry countdown
 *   3. When an ACK arrives, caller calls [acknowledge] — packet removed from pending
 *   4. If ACK doesn't arrive within [ACK_TIMEOUT_MS], the packet is re-sent
 *   5. After [MAX_RETRIES] failures, [onDeliveryFailed] is invoked
 *
 * ACK packet format (PacketType.ACK, payload):
 *   UTF-8 string: "ACK:{fingerprint}"
 *   where fingerprint = first 8 hex chars of SHA-256(srcId + dstId + payload)
 */
class AckManager(
    private val scope: CoroutineScope,
    private val onRetry: (packet: MeshPacket, peerIp: String) -> Unit,
    private val onDeliveryFailed: (packet: MeshPacket) -> Unit
) {

    companion object {
        private const val TAG           = "AckManager"
        private const val ACK_TIMEOUT_MS = 5_000L  // 5 seconds per attempt
        private const val MAX_RETRIES   = 3        // total of 3 attempts before giving up
        private const val CHECK_INTERVAL = 1_000L  // check pending queue every 1 s
    }

    /** An outgoing packet waiting for its ACK */
    private data class PendingEntry(
        val packet: MeshPacket,
        val peerIp: String,
        val fingerprint: String,
        val firstSentAt: Long = System.currentTimeMillis(),
        var lastSentAt: Long  = System.currentTimeMillis(),
        var retryCount: Int   = 0
    )

    private val pending = HashMap<String, PendingEntry>()   // fingerprint → entry
    private val lock    = Any()

    init {
        startRetryLoop()
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Begin tracking an outgoing packet.
     * Call this immediately after the first send attempt.
     *
     * @param packet  The packet that was sent
     * @param peerIp  IP address it was sent to (needed for retries)
     */
    fun trackOutgoing(packet: MeshPacket, peerIp: String) {
        val fp = fingerprint(packet)
        synchronized(lock) {
            pending[fp] = PendingEntry(packet = packet, peerIp = peerIp, fingerprint = fp)
        }
        Log.d(TAG, "Tracking packet $fp → $peerIp")
    }

    /**
     * Mark a packet as acknowledged — removes it from the retry queue.
     * Call this when an ACK packet arrives for a previously sent MSG.
     *
     * @param ackPayload The raw UTF-8 payload of the received ACK packet
     */
    fun acknowledge(ackPayload: String) {
        if (!ackPayload.startsWith("ACK:")) return
        val fp = ackPayload.removePrefix("ACK:")
        synchronized(lock) {
            if (pending.remove(fp) != null) {
                Log.d(TAG, "ACK received for $fp — delivery confirmed")
            }
        }
    }

    /**
     * Build the payload string for an ACK packet in response to a received MSG.
     *
     * @param receivedPacket The MSG packet we're acknowledging
     * @return UTF-8 string to use as ACK packet payload
     */
    fun buildAckPayload(receivedPacket: MeshPacket): ByteArray =
        "ACK:${fingerprint(receivedPacket)}".toByteArray(Charsets.UTF_8)

    /**
     * Compute the fingerprint for a packet.
     * Identical packets (same src, dst, payload) always produce the same fingerprint.
     */
    fun fingerprint(packet: MeshPacket): String =
        MeshCrypto.packetFingerprint(
            srcBytes = packet.srcId.bytes,
            dstBytes = packet.dstId.bytes,
            payload  = packet.payload
        )

    /** Number of packets currently waiting for ACK */
    fun pendingCount(): Int = synchronized(lock) { pending.size }

    // ── Retry loop ─────────────────────────────────────────────────────────────

    private fun startRetryLoop() {
        scope.launch {
            while (true) {
                delay(CHECK_INTERVAL)
                checkAndRetry()
            }
        }
    }

    private fun checkAndRetry() {
        val now = System.currentTimeMillis()
        val toRetry   = mutableListOf<PendingEntry>()
        val toAbandon = mutableListOf<PendingEntry>()

        synchronized(lock) {
            pending.values.forEach { entry ->
                val elapsed = now - entry.lastSentAt
                if (elapsed >= ACK_TIMEOUT_MS) {
                    if (entry.retryCount >= MAX_RETRIES) {
                        toAbandon.add(entry)
                    } else {
                        entry.retryCount++
                        entry.lastSentAt = now
                        toRetry.add(entry)
                    }
                }
            }
            toAbandon.forEach { pending.remove(it.fingerprint) }
        }

        toRetry.forEach { entry ->
            Log.d(TAG, "Retry ${entry.retryCount}/$MAX_RETRIES for packet ${entry.fingerprint}")
            onRetry(entry.packet, entry.peerIp)
        }

        toAbandon.forEach { entry ->
            Log.w(TAG, "Delivery failed after $MAX_RETRIES retries: ${entry.fingerprint}")
            onDeliveryFailed(entry.packet)
        }
    }
}
