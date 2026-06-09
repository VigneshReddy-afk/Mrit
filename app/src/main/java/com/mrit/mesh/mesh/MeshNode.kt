package com.mrit.mesh.mesh

import android.content.Context
import android.util.Log
import com.mrit.mesh.core.MeshID
import com.mrit.mesh.core.MeshPacket
import com.mrit.mesh.core.PacketType
import com.mrit.mesh.core.PeerInfo
import com.mrit.mesh.core.PeerRegistry
import com.mrit.mesh.routing.AODVRouter
import com.mrit.mesh.routing.RoutingDecision
import com.mrit.mesh.storage.PacketStore
import com.mrit.mesh.transport.BLETransport
import com.mrit.mesh.transport.WifiDirectTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * MeshNode — the single public API surface for the MRIT mesh network.
 *
 * All internal components (router, transports, store, registry) are wired
 * together here. The app layer (MeshService, MainActivity) only talks to MeshNode.
 *
 * Usage:
 *   val node = MeshNode(context)
 *   node.start()
 *
 *   // Send a message
 *   node.sendMessage(destinationId, "Hello from the mesh!")
 *
 *   // Receive messages
 *   lifecycleScope.launch {
 *       node.incomingMessages.collect { msg ->
 *           Log.d("App", "From ${msg.from.shortId()}: ${msg.text}")
 *       }
 *   }
 *
 *   // Observe peers
 *   lifecycleScope.launch {
 *       node.peers.collect { peerList -> updateUI(peerList) }
 *   }
 */
class MeshNode(private val context: Context) {

    companion object {
        private const val TAG             = "MeshNode"
        private const val PREFS_NAME      = "mrit_prefs"
        private const val KEY_MESH_ID     = "mesh_id_hex"
        private const val CLEANUP_INTERVAL = 30_000L  // 30 s
    }

    // ── Identity ───────────────────────────────────────────────────────────────

    val ourId: MeshID = loadOrCreateMeshId()

    // ── Components ─────────────────────────────────────────────────────────────

    private val peerRegistry   = PeerRegistry()
    private val router         = AODVRouter(ourId)
    private val packetStore    = PacketStore(context)
    private val bleTransport   = BLETransport(context, ourId)
    private val wifiTransport  = WifiDirectTransport(context, ourId)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Public flows ───────────────────────────────────────────────────────────

    /** Emits every text message received from any peer */
    private val _incomingMessages = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<IncomingMessage> = _incomingMessages

    /** Live list of currently connected peers — observe in UI */
    val peers: StateFlow<List<PeerInfo>> = peerRegistry.peers

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    fun start() {
        bleTransport.start()
        wifiTransport.start()

        observeIncomingPackets()
        observePeerHandshakes()
        observeDiscoveredNodes()
        startMaintenanceLoop()

        packetStore.purgeExpired()
        Log.d(TAG, "MeshNode started — ourId=${ourId.shortId()} (${ourId})")
    }

    fun stop() {
        bleTransport.stop()
        wifiTransport.stop()
        scope.cancel()
        Log.d(TAG, "MeshNode stopped")
    }

    // ── Send API ───────────────────────────────────────────────────────────────

    /**
     * Send a UTF-8 text message to a specific peer.
     *
     * If the peer is currently reachable, the message is delivered immediately.
     * If not, it is stored and will be forwarded when the peer comes into range.
     */
    fun sendMessage(to: MeshID, text: String) {
        val packet = MeshPacket(
            type    = PacketType.MSG,
            srcId   = ourId,
            dstId   = to,
            ttl     = MeshPacket.DEFAULT_TTL,
            payload = text.toByteArray(Charsets.UTF_8)
        )
        dispatchPacket(packet)
    }

    /**
     * Broadcast an SOS to ALL reachable nodes.
     * Uses maximum TTL — propagates as far as possible through the mesh.
     */
    fun sendSOS(message: String) {
        val packet = MeshPacket(
            type    = PacketType.SOS,
            srcId   = ourId,
            dstId   = MeshID.BROADCAST,
            ttl     = MeshPacket.SOS_TTL,
            payload = message.toByteArray(Charsets.UTF_8)
        )
        forwardToAllPeers(packet)
        Log.w(TAG, "SOS broadcast sent: $message")
    }

    // ── Internal — packet handling ─────────────────────────────────────────────

    private fun observeIncomingPackets() {
        scope.launch {
            wifiTransport.incomingPackets.collect { packet ->
                peerRegistry.touch(packet.srcId)  // refresh last-seen for this sender
                router.recordRoute(packet.srcId, packet.srcId) // direct route to sender
                route(packet)
            }
        }
    }

    private fun route(packet: MeshPacket) {
        Log.d(TAG, "Routing $packet")

        when (val decision = router.process(packet)) {

            is RoutingDecision.Deliver -> {
                deliverToApp(packet)
            }

            is RoutingDecision.DeliverAndForward -> {
                deliverToApp(packet)
                if (packet.isAlive()) forwardToAllPeers(packet.decrementTTL())
            }

            is RoutingDecision.Forward -> {
                val ip = peerRegistry.getIpFor(decision.nextHop)
                if (ip != null) {
                    wifiTransport.send(packet.decrementTTL(), ip)
                    Log.d(TAG, "Forwarded to ${decision.nextHop.shortId()} at $ip")
                } else {
                    // Route record is stale — fall back to store-and-discover
                    Log.w(TAG, "No IP for ${decision.nextHop.shortId()} — storing")
                    packetStore.store(packet)
                    broadcastRouteRequest(packet.dstId)
                }
            }

            is RoutingDecision.StoreAndDiscover -> {
                Log.d(TAG, "Storing packet for ${decision.destination.shortId()}, sending RREQ")
                packetStore.store(packet)
                broadcastRouteRequest(decision.destination)
            }

            is RoutingDecision.Drop -> {
                Log.d(TAG, "Dropped packet: ${decision.reason}")
            }
        }
    }

    private fun dispatchPacket(packet: MeshPacket) {
        // Check if we have a direct route
        val ip = peerRegistry.getIpFor(packet.dstId)
        if (ip != null) {
            wifiTransport.send(packet, ip)
            Log.d(TAG, "Dispatched ${packet.type} to ${packet.dstId.shortId()} at $ip")
        } else {
            // Store and trigger route discovery
            packetStore.store(packet)
            broadcastRouteRequest(packet.dstId)
            Log.d(TAG, "Stored ${packet.type} for ${packet.dstId.shortId()} — no route yet")
        }
    }

    private fun deliverToApp(packet: MeshPacket) {
        when (packet.type) {
            PacketType.MSG -> {
                val text = packet.payload.toString(Charsets.UTF_8)
                Log.i(TAG, "MSG from ${packet.srcId.shortId()}: $text")
                _incomingMessages.tryEmit(IncomingMessage(from = packet.srcId, text = text))
            }
            PacketType.SOS -> {
                val text = packet.payload.toString(Charsets.UTF_8)
                Log.w(TAG, "SOS from ${packet.srcId.shortId()}: $text")
                _incomingMessages.tryEmit(
                    IncomingMessage(from = packet.srcId, text = "🆘 SOS: $text", isSOS = true)
                )
            }
            PacketType.ROUTE -> {
                // AODV routing packets handled internally — not surfaced to app
                handleRoutingPacket(packet)
            }
            else -> { /* ACK handling in Phase 3 */ }
        }
    }

    private fun handleRoutingPacket(packet: MeshPacket) {
        val payload = packet.payload.toString(Charsets.UTF_8)
        if (payload.startsWith("RREQ:")) {
            if (!router.hasSeenRequest(payload)) {
                router.rememberRequest(payload)
                // We don't know the destination — forward the RREQ
                if (packet.isAlive()) forwardToAllPeers(packet.decrementTTL())
            }
        }
        // RREP handling in Phase 3
    }

    private fun forwardToAllPeers(packet: MeshPacket) {
        val peers = peerRegistry.getAll()
        peers.forEach { peer ->
            if (peer.meshId != packet.srcId) {  // Don't echo back to sender
                wifiTransport.send(packet, peer.ipAddress)
            }
        }
        Log.d(TAG, "Forwarded ${packet.type} to ${peers.size} peer(s)")
    }

    private fun broadcastRouteRequest(destination: MeshID) {
        val rreq = router.buildRouteRequest(destination)
        forwardToAllPeers(rreq)
    }

    // ── Internal — peer discovery ──────────────────────────────────────────────

    private fun observePeerHandshakes() {
        scope.launch {
            wifiTransport.peerHandshakes.collect { handshake ->
                val peer = PeerInfo(
                    meshId    = handshake.meshId,
                    ipAddress = handshake.ipAddress
                )
                peerRegistry.register(peer)
                router.recordRoute(handshake.meshId, handshake.meshId)

                Log.d(TAG, "New peer registered: ${peer.meshId.shortId()} at ${peer.ipAddress}")

                // Deliver any packets we were holding for this peer
                deliverStoredPackets(peer)
            }
        }
    }

    private fun observeDiscoveredNodes() {
        scope.launch {
            bleTransport.discoveredNodes.collect { node ->
                Log.d(TAG, "BLE: MRIT node at ${node.bluetoothAddress} (${node.rssi} dBm)")
                wifiTransport.connectToPeer(node.bluetoothAddress)
            }
        }
    }

    /**
     * When a peer comes into range, flush any packets we stored for them.
     */
    private fun deliverStoredPackets(peer: PeerInfo) {
        val pending = packetStore.getPendingFor(peer.meshId)
        if (pending.isEmpty()) return

        Log.d(TAG, "Delivering ${pending.size} stored packet(s) to ${peer.meshId.shortId()}")
        pending.forEach { packet ->
            wifiTransport.send(packet.decrementTTL(), peer.ipAddress)
        }
        packetStore.clearDelivered(peer.meshId)
    }

    // ── Maintenance ────────────────────────────────────────────────────────────

    private fun startMaintenanceLoop() {
        scope.launch {
            while (true) {
                delay(CLEANUP_INTERVAL)
                peerRegistry.removeStale()
                packetStore.purgeExpired()
            }
        }
    }

    // ── MeshID persistence ─────────────────────────────────────────────────────

    private fun loadOrCreateMeshId(): MeshID {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_MESH_ID, null)
        return if (stored != null) {
            MeshID.fromHex(stored).also { Log.d(TAG, "Loaded MeshID: ${it.shortId()}") }
        } else {
            MeshID.generate().also {
                prefs.edit().putString(KEY_MESH_ID, it.toString()).apply()
                Log.d(TAG, "Generated new MeshID: ${it.shortId()}")
            }
        }
    }

    // ── Data model ─────────────────────────────────────────────────────────────

    data class IncomingMessage(
        val from: MeshID,
        val text: String,
        val isSOS: Boolean = false,
        val timestampMs: Long = System.currentTimeMillis()
    )
}
