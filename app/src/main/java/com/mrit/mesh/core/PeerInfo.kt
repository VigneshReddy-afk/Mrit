package com.mrit.mesh.core

import java.security.PublicKey

/**
 * PeerInfo — everything we know about a reachable mesh neighbour.
 *
 * Phase 3 additions:
 *   [publicKey] — the peer's EC P-256 public key, received during the DISCOVER handshake.
 *                 Used by MeshCrypto.computeSharedKey() to derive the per-peer AES-256 key.
 *
 * @param meshId      The peer's 256-bit MeshID (permanent identity)
 * @param ipAddress   Current WiFi Direct IP (changes each session)
 * @param bleAddress  Bluetooth MAC (used to re-connect via BLE discovery)
 * @param publicKey   EC P-256 public key for end-to-end encryption (null until handshake)
 * @param lastSeenMs  Timestamp of last received packet from this peer
 */
data class PeerInfo(
    val meshId: MeshID,
    val ipAddress: String,
    val bleAddress: String?  = null,
    val publicKey: PublicKey? = null,       // Phase 3: populated from DISCOVER payload
    val lastSeenMs: Long     = System.currentTimeMillis()
) {
    /** True once we have the public key — messages to this peer can be encrypted */
    val canEncrypt: Boolean get() = publicKey != null

    override fun toString(): String =
        "Peer(id=${meshId.shortId()} ip=$ipAddress encrypted=$canEncrypt)"

    /** Return a copy with the last-seen timestamp refreshed to now */
    fun touched(): PeerInfo = copy(lastSeenMs = System.currentTimeMillis())

    /**
     * True if we haven't heard from this peer in more than [timeoutMs] milliseconds.
     * Default = 60 seconds.
     */
    fun isStale(timeoutMs: Long = 60_000L): Boolean =
        System.currentTimeMillis() - lastSeenMs > timeoutMs
}
