package com.mrit.mesh.core

/**
 * PeerInfo — everything we know about a reachable mesh neighbour.
 *
 * Populated during the WiFi Direct handshake (see WifiDirectTransport).
 * Stored in PeerRegistry and used by the router to forward packets.
 *
 * @param meshId       The peer's 256-bit MeshID (their permanent identity)
 * @param ipAddress    Their current WiFi Direct IP address (changes each session)
 * @param bleAddress   Their Bluetooth MAC address (used to re-initiate WiFi Direct)
 * @param lastSeenMs   System.currentTimeMillis() when we last heard from them
 */
data class PeerInfo(
    val meshId: MeshID,
    val ipAddress: String,
    val bleAddress: String? = null,
    val lastSeenMs: Long = System.currentTimeMillis()
) {
    /** Display-friendly summary */
    override fun toString(): String =
        "Peer(id=${meshId.shortId()} ip=$ipAddress ble=$bleAddress)"

    /** Update the last-seen timestamp — returns a new copy */
    fun touched(): PeerInfo = copy(lastSeenMs = System.currentTimeMillis())

    /**
     * True if we haven't heard from this peer in more than [timeoutMs] milliseconds.
     * Default timeout = 60 seconds.
     */
    fun isStale(timeoutMs: Long = 60_000L): Boolean =
        System.currentTimeMillis() - lastSeenMs > timeoutMs
}
