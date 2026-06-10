package com.mrit.mesh.dsl

import android.content.Context
import com.mrit.mesh.core.MeshID
import com.mrit.mesh.core.PeerInfo
import com.mrit.mesh.mesh.MeshNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Mrit — the high-level, app-facing entry point to the MRIT mesh (Phase 5).
 *
 * [MeshNode] is the Layer 2/3 protocol engine: packets, routing, encryption,
 * store-and-forward. [Mrit] wraps it in a small declarative configuration DSL
 * so application code never touches a [com.mrit.mesh.core.MeshPacket] directly.
 *
 * Mirrors the iOS `Mrit` API in `ios/Sources/MritMesh/DSL/Mrit.swift` —
 * the two are intentionally symmetric so a shared app design doc reads the
 * same on both platforms.
 *
 * Example:
 * ```kotlin
 * val mesh = Mrit.start(applicationContext) {
 *     onMessage { msg  -> log("From ${msg.from.shortId()}: ${msg.text}") }
 *     onFile    { file -> saveToDisk(file.fileName, file.data) }
 *     onPeers   { peers -> updatePeerChips(peers) }
 * }
 *
 * mesh.send(peerId, "Hello mesh!")
 * mesh.sendFile(peerId, "map.png", bytes)
 * mesh.sos("Need help — twisted ankle, 2km north of trailhead")
 *
 * // ... later, e.g. in onDestroy()
 * mesh.stop()
 * ```
 */
class Mrit private constructor(val node: MeshNode) {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    companion object {
        /**
         * Create, configure, and start a mesh node in one call.
         *
         * @param context   Android context (application context recommended —
         *                   the underlying [MeshNode] outlives any single Activity)
         * @param configure DSL block registering message/file/peer handlers,
         *                   evaluated once before the node starts
         * @return a started [Mrit] instance ready to send messages
         */
        fun start(context: Context, configure: MritConfig.() -> Unit = {}): Mrit {
            val config = MritConfig().apply(configure)
            val node = MeshNode(context.applicationContext)
            val mrit = Mrit(node)

            mrit.scope.launch {
                node.incomingMessages.collect { msg -> config.messageHandlers.forEach { it(msg) } }
            }
            mrit.scope.launch {
                node.incomingFiles.collect { file -> config.fileHandlers.forEach { it(file) } }
            }
            mrit.scope.launch {
                node.peers.collect { peers -> config.peerHandlers.forEach { it(peers) } }
            }

            node.start()
            return mrit
        }
    }

    // ── Identity ───────────────────────────────────────────────────────────────

    /** Our own identity on the mesh — share this with others so they can message us. */
    val ourId: MeshID get() = node.ourId

    /** Currently reachable peers — updates live as devices come in/out of range. */
    val peers: StateFlow<List<PeerInfo>> get() = node.peers

    // ── Send API ───────────────────────────────────────────────────────────────

    /** Send an end-to-end encrypted text message to [to]. Routes multi-hop automatically. */
    fun send(to: MeshID, text: String) = node.sendMessage(to, text)

    /** Send a file of any size to [to] — automatically chunked (32KB) and encrypted. */
    fun sendFile(to: MeshID, fileName: String, data: ByteArray) = node.sendFile(to, fileName, data)

    /** Broadcast an unencrypted emergency message to every reachable node. */
    fun sos(message: String) = node.sendSOS(message)

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    /** Stop the mesh node and release all transport resources (WiFi Direct, BLE). */
    fun stop() {
        scope.cancel()
        node.stop()
    }
}

/**
 * DSL configuration block for [Mrit.start].
 *
 * Register one or more handlers per event type — every registered handler is
 * called for every event of that type. Handlers run on the main thread.
 */
class MritConfig {
    internal val messageHandlers = mutableListOf<(MeshNode.IncomingMessage) -> Unit>()
    internal val fileHandlers    = mutableListOf<(MeshNode.ReceivedFile) -> Unit>()
    internal val peerHandlers    = mutableListOf<(List<PeerInfo>) -> Unit>()

    /** Called for every text message (including SOS broadcasts) addressed to or seen by us. */
    fun onMessage(handler: (MeshNode.IncomingMessage) -> Unit) {
        messageHandlers += handler
    }

    /** Called once per file transfer, after all chunks have arrived and been reassembled. */
    fun onFile(handler: (MeshNode.ReceivedFile) -> Unit) {
        fileHandlers += handler
    }

    /** Called whenever the set of reachable peers changes (peer joins, leaves, or times out). */
    fun onPeers(handler: (List<PeerInfo>) -> Unit) {
        peerHandlers += handler
    }
}
