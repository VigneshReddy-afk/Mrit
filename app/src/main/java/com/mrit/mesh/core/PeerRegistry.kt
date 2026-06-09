package com.mrit.mesh.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.crypto.SecretKey

/**
 * PeerRegistry — thread-safe table of all reachable mesh peers.
 *
 * Phase 3 addition:
 *   Caches the per-peer AES-256 shared key (derived via ECDH during handshake).
 *   MeshNode calls [storeSharedKey] once per peer, then [getSharedKey] per message.
 */
class PeerRegistry {

    companion object {
        private const val TAG = "PeerRegistry"
    }

    private val table       = HashMap<String, PeerInfo>()   // meshId hex → PeerInfo
    private val sharedKeys  = HashMap<String, SecretKey>()  // meshId hex → AES-256 key
    private val lock        = Any()

    private val _peers = MutableStateFlow<List<PeerInfo>>(emptyList())
    val peers: StateFlow<List<PeerInfo>> = _peers.asStateFlow()

    // ── Write ──────────────────────────────────────────────────────────────────

    /** Register or update a peer. Preserves existing BLE address and public key if absent. */
    fun register(peer: PeerInfo) {
        synchronized(lock) {
            val key      = peer.meshId.toString()
            val existing = table[key]
            table[key]   = peer.copy(
                bleAddress = peer.bleAddress ?: existing?.bleAddress,
                publicKey  = peer.publicKey  ?: existing?.publicKey
            )
            publishUpdate()
        }
        Log.d(TAG, "Registered $peer")
    }

    /** Remove a peer when WiFi Direct disconnects. */
    fun remove(meshId: MeshID) {
        synchronized(lock) {
            if (table.remove(meshId.toString()) != null) {
                sharedKeys.remove(meshId.toString())
                publishUpdate()
                Log.d(TAG, "Removed peer ${meshId.shortId()}")
            }
        }
    }

    /** Refresh last-seen timestamp. Call on every received packet. */
    fun touch(meshId: MeshID) {
        synchronized(lock) {
            val key = meshId.toString()
            table[key] = table[key]?.touched() ?: return
            publishUpdate()
        }
    }

    /** Remove peers not heard from in [timeoutMs] ms. */
    fun removeStale(timeoutMs: Long = 60_000L) {
        synchronized(lock) {
            val stale = table.entries.filter { it.value.isStale(timeoutMs) }.map { it.key }
            stale.forEach {
                table.remove(it)
                sharedKeys.remove(it)
            }
            if (stale.isNotEmpty()) {
                publishUpdate()
                Log.d(TAG, "Removed ${stale.size} stale peers")
            }
        }
    }

    // ── Phase 3: shared key cache ──────────────────────────────────────────────

    /**
     * Cache the ECDH-derived AES-256 shared key for a peer.
     * Called once after handshake completes.
     */
    fun storeSharedKey(meshId: MeshID, key: SecretKey) {
        synchronized(lock) { sharedKeys[meshId.toString()] = key }
        Log.d(TAG, "Shared key stored for ${meshId.shortId()}")
    }

    /**
     * Retrieve the shared AES-256 key for encrypting/decrypting messages with a peer.
     * Returns null if handshake hasn't completed yet.
     */
    fun getSharedKey(meshId: MeshID): SecretKey? =
        synchronized(lock) { sharedKeys[meshId.toString()] }

    // ── Read ───────────────────────────────────────────────────────────────────

    fun getIpFor(meshId: MeshID): String? =
        synchronized(lock) { table[meshId.toString()]?.ipAddress }

    fun get(meshId: MeshID): PeerInfo? =
        synchronized(lock) { table[meshId.toString()] }

    fun getAll(): List<PeerInfo> =
        synchronized(lock) { table.values.toList() }

    fun isEmpty(): Boolean  = synchronized(lock) { table.isEmpty() }
    fun count(): Int        = synchronized(lock) { table.size }

    private fun publishUpdate() {
        _peers.tryEmit(table.values.toList())
    }
}
