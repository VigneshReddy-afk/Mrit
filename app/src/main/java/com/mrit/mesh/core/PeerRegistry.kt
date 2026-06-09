package com.mrit.mesh.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * PeerRegistry — thread-safe table of all currently reachable mesh peers.
 *
 * This is the routing table's companion: the router decides WHERE to send
 * (next-hop MeshID), and the registry resolves HOW to reach them (IP address).
 *
 * Emits [peers] as a StateFlow so the UI can observe the live peer list.
 */
class PeerRegistry {

    companion object {
        private const val TAG = "PeerRegistry"
    }

    // meshId hex string → PeerInfo
    private val table = HashMap<String, PeerInfo>()
    private val lock  = Any()

    private val _peers = MutableStateFlow<List<PeerInfo>>(emptyList())

    /** Live list of all currently registered peers — observe this in UI */
    val peers: StateFlow<List<PeerInfo>> = _peers.asStateFlow()

    // ── Write operations ───────────────────────────────────────────────────────

    /**
     * Register or update a peer.
     * If the peer already exists, its IP and last-seen time are refreshed.
     */
    fun register(peer: PeerInfo) {
        synchronized(lock) {
            val key = peer.meshId.toString()
            val existing = table[key]
            table[key] = if (existing != null) {
                // Preserve BLE address if we already knew it
                peer.copy(bleAddress = peer.bleAddress ?: existing.bleAddress)
            } else {
                peer
            }
            publishUpdate()
        }
        Log.d(TAG, "Registered $peer")
    }

    /**
     * Remove a peer — called when WiFi Direct disconnects.
     */
    fun remove(meshId: MeshID) {
        synchronized(lock) {
            val removed = table.remove(meshId.toString())
            if (removed != null) {
                publishUpdate()
                Log.d(TAG, "Removed peer ${meshId.shortId()}")
            }
        }
    }

    /**
     * Refresh the last-seen timestamp for a peer.
     * Called every time we receive any packet from them.
     */
    fun touch(meshId: MeshID) {
        synchronized(lock) {
            val key = meshId.toString()
            table[key] = table[key]?.touched() ?: return
            publishUpdate()
        }
    }

    /**
     * Remove peers we haven't heard from in over [timeoutMs] milliseconds.
     * Call this periodically (e.g. every 30 s).
     */
    fun removeStale(timeoutMs: Long = 60_000L) {
        synchronized(lock) {
            val stale = table.entries.filter { it.value.isStale(timeoutMs) }.map { it.key }
            stale.forEach { table.remove(it) }
            if (stale.isNotEmpty()) {
                publishUpdate()
                Log.d(TAG, "Removed ${stale.size} stale peers")
            }
        }
    }

    // ── Read operations ────────────────────────────────────────────────────────

    /**
     * Look up the IP address to reach a given MeshID.
     * Returns null if we don't have a direct connection to this peer.
     */
    fun getIpFor(meshId: MeshID): String? =
        synchronized(lock) { table[meshId.toString()]?.ipAddress }

    /**
     * Returns a snapshot of all registered peers.
     */
    fun getAll(): List<PeerInfo> =
        synchronized(lock) { table.values.toList() }

    /**
     * True if at least one peer is currently registered.
     */
    fun isEmpty(): Boolean = synchronized(lock) { table.isEmpty() }

    /** Total number of registered peers */
    fun count(): Int = synchronized(lock) { table.size }

    // ── Internal ───────────────────────────────────────────────────────────────

    private fun publishUpdate() {
        _peers.tryEmit(table.values.toList())
    }
}
