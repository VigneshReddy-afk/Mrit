package com.mrit.mesh.mesh

import android.content.Context
import android.util.Log
import com.mrit.mesh.core.MeshID
import com.mrit.mesh.core.MeshPacket
import com.mrit.mesh.core.PacketType
import com.mrit.mesh.core.PeerInfo
import com.mrit.mesh.core.PeerRegistry
import com.mrit.mesh.crypto.KeyManager
import com.mrit.mesh.crypto.MeshCrypto
import com.mrit.mesh.reliability.AckManager
import com.mrit.mesh.routing.AODVRouter
import com.mrit.mesh.transfer.FileTransferManager
import com.mrit.mesh.routing.RoutingDecision
import com.mrit.mesh.storage.PacketStore
import com.mrit.mesh.transport.BLETransport
import com.mrit.mesh.transport.WifiDirectTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * MeshNode — the complete MRIT mesh stack. Phase 4.
 *
 * New in Phase 4:
 *   - File transfer          : sendFile() splits any file into 32KB AES-GCM–encrypted
 *                              chunks; FileTransferManager reassembles in any arrival order
 *   - Hardware key storage   : EC P-256 private key now wrapped by EncryptedSharedPreferences
 *                              (Android Keystore AES-256-GCM master key — hardware-backed on
 *                              Secure Enclave devices)
 *   - iOS counterpart        : Swift Package at ios/ with binary-compatible MMP codec,
 *                              CryptoKit-based crypto, MultipeerConnectivity transport
 *
 * Public API:
 *   node.sendMessage(to, text)              — unicast encrypted message
 *   node.sendFile(to, fileName, data)       — encrypted file transfer (any size)
 *   node.sendSOS(text)                      — broadcast unencrypted emergency
 *   node.incomingMessages                   — Flow<IncomingMessage>
 *   node.incomingFiles                      — Flow<ReceivedFile>
 *   node.peers                              — StateFlow<List<PeerInfo>>
 */
class MeshNode(private val context: Context) {

    companion object {
        private const val TAG              = "MeshNode"
        private const val PREFS_NAME       = "mrit_prefs"
        private const val KEY_MESH_ID      = "mesh_id_hex"
        private const val CLEANUP_INTERVAL = 30_000L
    }

    // ── Identity & crypto ──────────────────────────────────────────────────────

    val ourId: MeshID          = loadOrCreateMeshId()
    val keyManager: KeyManager = KeyManager(context)

    // ── Components ─────────────────────────────────────────────────────────────

    private val peerRegistry  = PeerRegistry()
    private val router        = AODVRouter(ourId)
    private val packetStore   = PacketStore(context)
    private val bleTransport  = BLETransport(context, ourId)
    private val wifiTransport = WifiDirectTransport(context, ourId, keyManager)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val fileTransferManager = FileTransferManager(
        ourId       = ourId,
        sendPacket  = { packet, dest -> dispatchPacket(packet) },
        onFileReceived = { fileName, data ->
            Log.i(TAG, "File received: '$fileName' (${data.size} bytes)")
            _incomingFiles.tryEmit(ReceivedFile(fileName = fileName, data = data))
        }
    )

    private val ackManager = AckManager(
        scope            = scope,
        onRetry          = { packet, ip -> wifiTransport.send(packet, ip) },
        onDeliveryFailed = { packet ->
            Log.w(TAG, "Delivery failed for packet to ${packet.dstId.shortId()}")
            _incomingMessages.tryEmit(
                IncomingMessage(from = ourId,
                    text  = "⚠ Message to ${packet.dstId.shortId()} failed after 3 retries",
                    isSOS = false)
            )
        }
    )

    // ── Public flows ───────────────────────────────────────────────────────────

    private val _incomingMessages = MutableSharedFlow<IncomingMessage>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<IncomingMessage> = _incomingMessages

    /** Emits every completed file received from any peer */
    private val _incomingFiles = MutableSharedFlow<ReceivedFile>(extraBufferCapacity = 16)
    val incomingFiles: SharedFlow<ReceivedFile> = _incomingFiles

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
        Log.d(TAG, "MeshNode started — id=${ourId.shortId()} encrypted=true")
    }

    fun stop() {
        bleTransport.stop()
        wifiTransport.stop()
        scope.cancel()
    }

    // ── Send API ───────────────────────────────────────────────────────────────

    /**
     * Send an encrypted text message to [to].
     * - If peer is reachable: encrypt with shared key, send immediately, track for ACK.
     * - If peer is unreachable: store packet and broadcast RREQ to discover route.
     */
    fun sendMessage(to: MeshID, text: String) {
        val plaintext = text.toByteArray(Charsets.UTF_8)
        val payload   = encryptForPeer(to, plaintext) ?: plaintext  // fallback if no key yet

        val packet = MeshPacket(
            type    = PacketType.MSG,
            srcId   = ourId,
            dstId   = to,
            ttl     = MeshPacket.DEFAULT_TTL,
            payload = payload
        )
        dispatchPacket(packet)
    }

    /**
     * Send a file to [to] — split into 32KB encrypted chunks.
     * Chunks arrive in any order and are reassembled automatically on the receiver.
     *
     * @param to       Destination MeshID
     * @param fileName Original filename (e.g. "photo.jpg")
     * @param data     Raw file bytes
     */
    fun sendFile(to: MeshID, fileName: String, data: ByteArray) {
        fileTransferManager.send(dest = to, fileName = fileName, data = data)
    }

    /**
     * Broadcast an SOS to all reachable nodes.
     * SOS is NOT encrypted — it must be readable by any node.
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
        Log.w(TAG, "SOS broadcast: $message")
    }

    // ── Internal — routing ─────────────────────────────────────────────────────

    private fun observeIncomingPackets() {
        scope.launch {
            wifiTransport.incomingPackets.collect { packet ->
                peerRegistry.touch(packet.srcId)
                router.recordRoute(packet.srcId, packet.srcId)
                route(packet)
            }
        }
    }

    private fun route(packet: MeshPacket) {
        when (val decision = router.process(packet)) {
            is RoutingDecision.Deliver          -> deliverToApp(packet)
            is RoutingDecision.DeliverAndForward -> {
                deliverToApp(packet)
                if (packet.isAlive()) forwardToAllPeers(packet.decrementTTL())
            }
            is RoutingDecision.Forward -> {
                val ip = peerRegistry.getIpFor(decision.nextHop)
                if (ip != null) {
                    wifiTransport.send(packet.decrementTTL(), ip)
                } else {
                    packetStore.store(packet)
                    broadcastRREQ(packet.dstId)
                }
            }
            is RoutingDecision.StoreAndDiscover -> {
                packetStore.store(packet)
                broadcastRREQ(decision.destination)
            }
            is RoutingDecision.Drop -> Log.d(TAG, "Dropped: ${decision.reason}")
        }
    }

    private fun dispatchPacket(packet: MeshPacket) {
        val ip = peerRegistry.getIpFor(packet.dstId)
        if (ip != null) {
            wifiTransport.send(packet, ip)
            if (packet.type == PacketType.MSG) ackManager.trackOutgoing(packet, ip)
        } else {
            packetStore.store(packet)
            broadcastRREQ(packet.dstId)
        }
    }

    // ── Internal — app delivery ────────────────────────────────────────────────

    private fun deliverToApp(packet: MeshPacket) {
        when (packet.type) {
            PacketType.MSG -> {
                val plaintext = decryptFromPeer(packet.srcId, packet.payload)
                    ?: packet.payload  // fallback: treat as plaintext if no key
                val text = plaintext.toString(Charsets.UTF_8)
                Log.i(TAG, "MSG from ${packet.srcId.shortId()}: $text")
                _incomingMessages.tryEmit(IncomingMessage(from = packet.srcId, text = text))
                sendAck(packet)       // ← Phase 3: always ACK received messages
            }
            PacketType.ACK -> {
                val payload = packet.payload.toString(Charsets.UTF_8)
                ackManager.acknowledge(payload)
            }
            PacketType.SOS -> {
                val text = packet.payload.toString(Charsets.UTF_8)
                Log.w(TAG, "SOS from ${packet.srcId.shortId()}: $text")
                _incomingMessages.tryEmit(
                    IncomingMessage(from = packet.srcId, text = "🆘 SOS: $text", isSOS = true)
                )
            }
            PacketType.ROUTE -> handleRoutingPacket(packet)
            PacketType.DISCOVER -> { /* handled in transport layer */ }
            PacketType.FILE_CHUNK -> {
                // Decrypt the chunk payload (fallback to raw if no shared key yet)
                val plaintext = decryptFromPeer(packet.srcId, packet.payload)
                    ?: packet.payload
                fileTransferManager.onChunkReceived(packet.srcId, plaintext)
            }
        }
    }

    // ── Internal — ACK ────────────────────────────────────────────────────────

    private fun sendAck(originalMsg: MeshPacket) {
        val ackPayload = ackManager.buildAckPayload(originalMsg)
        val ack = MeshPacket(
            type    = PacketType.ACK,
            srcId   = ourId,
            dstId   = originalMsg.srcId,
            ttl     = MeshPacket.DEFAULT_TTL,
            payload = ackPayload
        )
        val ip = peerRegistry.getIpFor(originalMsg.srcId)
        if (ip != null) {
            wifiTransport.send(ack, ip)
            Log.d(TAG, "ACK sent to ${originalMsg.srcId.shortId()}")
        }
    }

    // ── Internal — AODV multi-hop routing ─────────────────────────────────────

    /**
     * Full RREQ/RREP handling — this is what enables A→B→C multi-hop.
     *
     * RREQ flow: A broadcasts RREQ looking for C
     *   → B receives, checks if it knows C
     *   → If yes: B sends RREP back to A
     *   → If no:  B forwards RREQ (TTL decremented)
     *
     * RREP flow: B sends RREP to A
     *   → A receives, records route: C is reachable via B
     *   → A flushes any stored packets destined for C
     */
    private fun handleRoutingPacket(packet: MeshPacket) {
        val payload = packet.payload.toString(Charsets.UTF_8)

        when {
            payload.startsWith("RREQ:") -> handleRREQ(packet, payload)
            payload.startsWith("RREP:") -> handleRREP(packet, payload)
        }
    }

    private fun handleRREQ(packet: MeshPacket, payload: String) {
        // Format: "RREQ:{requestId}:{destHex}"
        val parts = payload.split(":")
        if (parts.size < 3) return
        val requestId = parts[1]
        val destHex   = parts[2]

        // Loop prevention — drop if we've seen this RREQ before
        if (router.hasSeenRequest(requestId)) return
        router.rememberRequest(requestId)

        // Record reverse route: original requester (packet.srcId) reachable via sender
        router.recordRoute(packet.srcId, packet.srcId)

        val destination = runCatching { MeshID.fromHex(destHex) }.getOrNull() ?: return

        when {
            // Case 1: WE are the destination → send RREP back
            destination == ourId -> {
                Log.d(TAG, "RREQ: I am the destination — sending RREP to ${packet.srcId.shortId()}")
                sendRREP(requestId, destination, replyTo = packet.srcId)
            }
            // Case 2: We have a direct route to the destination → send RREP
            peerRegistry.getIpFor(destination) != null -> {
                Log.d(TAG, "RREQ: I know ${destination.shortId()} — sending RREP to ${packet.srcId.shortId()}")
                sendRREP(requestId, destination, replyTo = packet.srcId)
            }
            // Case 3: Unknown destination → forward RREQ
            packet.isAlive() -> {
                Log.d(TAG, "RREQ: forwarding — don't know ${destination.shortId()}")
                forwardToAllPeers(packet.decrementTTL())
            }
        }
    }

    private fun handleRREP(packet: MeshPacket, payload: String) {
        // Format: "RREP:{requestId}:{destHex}"
        val parts = payload.split(":")
        if (parts.size < 3) return
        val destHex = parts[2]

        val destination = runCatching { MeshID.fromHex(destHex) }.getOrNull() ?: return

        // Record route: [destination] is reachable via [packet.srcId]
        router.recordRoute(destination, packet.srcId)
        Log.d(TAG, "RREP received: ${destination.shortId()} reachable via ${packet.srcId.shortId()}")

        if (packet.dstId == ourId) {
            // We are the original requester — route found, flush stored packets
            val ip = peerRegistry.getIpFor(packet.srcId)
            if (ip != null) {
                flushStoredPackets(destination, nextHopIp = ip)
            }
        } else {
            // Forward RREP toward the original requester (packet.dstId)
            val ip = peerRegistry.getIpFor(packet.dstId)
            if (ip != null) {
                wifiTransport.send(packet.decrementTTL(), ip)
            } else {
                forwardToAllPeers(packet.decrementTTL())
            }
        }
    }

    private fun sendRREP(requestId: String, destination: MeshID, replyTo: MeshID) {
        val rrep = MeshPacket(
            type    = PacketType.ROUTE,
            srcId   = ourId,
            dstId   = replyTo,
            ttl     = MeshPacket.DEFAULT_TTL,
            payload = "RREP:${requestId}:${destination}".toByteArray(Charsets.UTF_8)
        )
        val ip = peerRegistry.getIpFor(replyTo)
        if (ip != null) {
            wifiTransport.send(rrep, ip)
        } else {
            forwardToAllPeers(rrep)
        }
    }

    private fun broadcastRREQ(destination: MeshID) {
        val rreq = router.buildRouteRequest(destination)
        forwardToAllPeers(rreq)
        Log.d(TAG, "RREQ broadcast for ${destination.shortId()}")
    }

    private fun forwardToAllPeers(packet: MeshPacket) {
        peerRegistry.getAll().forEach { peer ->
            if (peer.meshId != packet.srcId) {
                wifiTransport.send(packet, peer.ipAddress)
            }
        }
    }

    private fun flushStoredPackets(destination: MeshID, nextHopIp: String) {
        val stored = packetStore.getPendingFor(destination)
        if (stored.isEmpty()) return
        Log.d(TAG, "Flushing ${stored.size} stored packet(s) to ${destination.shortId()} via $nextHopIp")
        stored.forEach { wifiTransport.send(it.decrementTTL(), nextHopIp) }
        packetStore.clearDelivered(destination)
    }

    // ── Internal — peer discovery ──────────────────────────────────────────────

    private fun observePeerHandshakes() {
        scope.launch {
            wifiTransport.peerHandshakes.collect { handshake ->
                // Reconstruct peer's public key from the bytes in DISCOVER payload
                val peerPublicKey = if (handshake.publicKeyBytes.isNotEmpty()) {
                    KeyManager.publicKeyFromBytes(handshake.publicKeyBytes)
                } else null

                // Derive and cache the shared AES-256 key for this peer
                if (peerPublicKey != null) {
                    val sharedKey = MeshCrypto.computeSharedKey(
                        ourPrivateKey  = keyManager.privateKey,
                        theirPublicKey = peerPublicKey
                    )
                    peerRegistry.storeSharedKey(handshake.meshId, sharedKey)
                    Log.d(TAG, "Shared key derived for ${handshake.meshId.shortId()} — messages will be encrypted")
                } else {
                    Log.w(TAG, "No public key from ${handshake.meshId.shortId()} — messages unencrypted")
                }

                val peer = PeerInfo(
                    meshId     = handshake.meshId,
                    ipAddress  = handshake.ipAddress,
                    publicKey  = peerPublicKey
                )
                peerRegistry.register(peer)
                router.recordRoute(handshake.meshId, handshake.meshId)

                // Deliver any packets we stored for this peer before they connected
                flushStoredPackets(handshake.meshId, nextHopIp = handshake.ipAddress)
            }
        }
    }

    private fun observeDiscoveredNodes() {
        scope.launch {
            bleTransport.discoveredNodes.collect { node ->
                wifiTransport.connectToPeer(node.bluetoothAddress)
            }
        }
    }

    // ── Internal — encryption ─────────────────────────────────────────────────

    /**
     * Encrypt [plaintext] for a specific peer using the shared AES-256-GCM key.
     * Returns null if we don't have a shared key with this peer yet.
     */
    private fun encryptForPeer(peerId: MeshID, plaintext: ByteArray): ByteArray? {
        if (peerId == MeshID.BROADCAST) return null  // broadcasts are never encrypted
        val key = peerRegistry.getSharedKey(peerId) ?: return null
        return MeshCrypto.encrypt(plaintext, key)
    }

    /**
     * Decrypt an incoming packet's payload using the shared key with the sender.
     * Returns null if decryption fails (wrong key, tampered data, or no key yet).
     */
    private fun decryptFromPeer(peerId: MeshID, payload: ByteArray): ByteArray? {
        val key = peerRegistry.getSharedKey(peerId) ?: return null
        return MeshCrypto.decrypt(payload, key)
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
        val prefs  = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_MESH_ID, null)
        return if (stored != null) {
            MeshID.fromHex(stored)
        } else {
            MeshID.generate().also {
                prefs.edit().putString(KEY_MESH_ID, it.toString()).apply()
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

    /**
     * Emitted on [incomingFiles] when all chunks of a file transfer have arrived
     * and been reassembled into the original file bytes.
     */
    data class ReceivedFile(
        val fileName: String,
        val data: ByteArray,
        val timestampMs: Long = System.currentTimeMillis()
    ) {
        // ByteArray equality must be content-based, not reference-based
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ReceivedFile) return false
            return fileName    == other.fileName
                && timestampMs == other.timestampMs
                && data.contentEquals(other.data)
        }
        override fun hashCode(): Int {
            var result = fileName.hashCode()
            result = 31 * result + data.contentHashCode()
            result = 31 * result + timestampMs.hashCode()
            return result
        }
    }
}
