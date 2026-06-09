package com.mrit.mesh.transport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import com.mrit.mesh.core.MeshID
import com.mrit.mesh.core.MeshPacket
import com.mrit.mesh.core.PacketType
import com.mrit.mesh.crypto.KeyManager
import com.mrit.mesh.protocol.MMPDecoder
import com.mrit.mesh.protocol.MMPEncoder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * WifiDirectTransport — sends and receives MeshPackets over WiFi Direct (P2P).
 *
 * Phase 2 additions:
 *   - Accepts [ourId] to build DISCOVER (handshake) packets
 *   - Performs MeshID handshake immediately after WiFi Direct connects
 *   - Emits [peerHandshakes] when a new peer's MeshID + IP is learned
 *   - Emits [incomingPackets] for all non-handshake data packets
 *
 * Handshake flow (both sides use the same port 8988):
 *   Client → Server : DISCOVER packet  (srcId = client MeshID, payload = empty)
 *   Server → Client : DISCOVER packet  (srcId = server MeshID, payload = empty)
 *   Each side records the other's MeshID ↔ IP mapping in PeerRegistry.
 */
class WifiDirectTransport(
    private val context: Context,
    private val ourId: MeshID,
    private val keyManager: KeyManager   // ← Phase 3: public key embedded in DISCOVER
) {

    companion object {
        private const val TAG              = "WiFiDirectTransport"
        private const val PORT             = 8988
        private const val SOCKET_TIMEOUT_MS = 10_000
    }

    private val manager: WifiP2pManager by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    }
    private lateinit var channel: WifiP2pManager.Channel

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var isRunning = false

    /** Emits decoded data packets (non-handshake) for the router */
    private val _incomingPackets = MutableSharedFlow<MeshPacket>(extraBufferCapacity = 128)
    val incomingPackets: SharedFlow<MeshPacket> = _incomingPackets

    /**
     * Emits a [PeerHandshake] each time we learn a new peer's MeshID ↔ IP mapping.
     * MeshService observes this and registers the peer in PeerRegistry.
     */
    private val _peerHandshakes = MutableSharedFlow<PeerHandshake>(extraBufferCapacity = 32)
    val peerHandshakes: SharedFlow<PeerHandshake> = _peerHandshakes

    // ── WiFi P2P broadcast receiver ────────────────────────────────────────────

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        Log.w(TAG, "WiFi Direct disabled")
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val netInfo = intent.getParcelableExtra<android.net.NetworkInfo>(
                        WifiP2pManager.EXTRA_NETWORK_INFO
                    )
                    if (netInfo?.isConnected == true) {
                        manager.requestConnectionInfo(channel, connectionInfoListener)
                    } else {
                        Log.d(TAG, "WiFi Direct disconnected")
                    }
                }
            }
        }
    }

    private val connectionInfoListener = WifiP2pManager.ConnectionInfoListener { info ->
        if (!info.groupFormed) return@ConnectionInfoListener

        if (info.isGroupOwner) {
            Log.d(TAG, "We are group owner — server socket started")
            startServer()
        } else {
            val ownerIp = info.groupOwnerAddress?.hostAddress ?: return@ConnectionInfoListener
            Log.d(TAG, "We are client — group owner IP: $ownerIp")
            performHandshakeAsClient(ownerIp)
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    fun start() {
        channel = manager.initialize(context, context.mainLooper, null)
        isRunning = true

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        }
        context.registerReceiver(receiver, filter)
        discoverPeers()
        Log.d(TAG, "WifiDirectTransport started (ourId=${ourId.shortId()})")
    }

    fun stop() {
        isRunning = false
        serverSocket?.close()
        scope.cancel()
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        Log.d(TAG, "WifiDirectTransport stopped")
    }

    // ── Peer discovery & connection ────────────────────────────────────────────

    private fun discoverPeers() {
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Log.d(TAG, "Peer discovery started")
            override fun onFailure(reason: Int) = Log.w(TAG, "Peer discovery failed, reason=$reason")
        })
    }

    fun connectToPeer(deviceAddress: String) {
        val config = WifiP2pConfig().apply { this.deviceAddress = deviceAddress }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Log.d(TAG, "Connect initiated to $deviceAddress")
            override fun onFailure(reason: Int) = Log.w(TAG, "Connect to $deviceAddress failed, reason=$reason")
        })
    }

    // ── Handshake ──────────────────────────────────────────────────────────────

    /**
     * CLIENT-SIDE handshake.
     * Sends our DISCOVER packet (with public key) → receives owner's DISCOVER → emits PeerHandshake.
     */
    private fun performHandshakeAsClient(ownerIp: String) {
        scope.launch {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ownerIp, PORT), SOCKET_TIMEOUT_MS)
                    val out   = DataOutputStream(socket.getOutputStream())
                    val input = DataInputStream(socket.getInputStream())

                    // 1. Send our DISCOVER
                    sendDiscover(out)

                    // 2. Receive owner's DISCOVER
                    val packet = readPacket(input)
                    if (packet?.type == PacketType.DISCOVER) {
                        val handshake = PeerHandshake(
                            meshId         = packet.srcId,
                            ipAddress      = ownerIp,
                            publicKeyBytes = packet.payload   // ← Phase 3
                        )
                        _peerHandshakes.emit(handshake)
                        Log.d(TAG, "Handshake complete — owner is ${packet.srcId.shortId()} at $ownerIp")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Client handshake failed: ${e.message}")
            }
        }
    }

    /**
     * SERVER-SIDE handshake — called from [handleIncomingConnection] when the
     * first packet from a new client is a DISCOVER.
     */
    private suspend fun performHandshakeAsServer(
        socket: Socket,
        clientDiscover: MeshPacket
    ) {
        try {
            val clientIp = socket.inetAddress.hostAddress ?: return
            val out = DataOutputStream(socket.getOutputStream())

            // 1. Send our DISCOVER back
            sendDiscover(out)

            // 2. Record the peer
            val handshake = PeerHandshake(
                meshId         = clientDiscover.srcId,
                ipAddress      = clientIp,
                publicKeyBytes = clientDiscover.payload   // ← Phase 3
            )
            _peerHandshakes.emit(handshake)
            Log.d(TAG, "Handshake complete — client is ${clientDiscover.srcId.shortId()} at $clientIp")
        } catch (e: Exception) {
            Log.e(TAG, "Server handshake failed: ${e.message}")
        }
    }

    // ── Data transfer ──────────────────────────────────────────────────────────

    /**
     * Send a [MeshPacket] to a peer at [peerIp].
     * Opens a fresh TCP connection, writes the packet, closes.
     */
    fun send(packet: MeshPacket, peerIp: String) {
        scope.launch {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(peerIp, PORT), SOCKET_TIMEOUT_MS)
                    val out = DataOutputStream(socket.getOutputStream())
                    writePacket(out, packet)
                    Log.d(TAG, "Sent ${packet.type} to $peerIp (TTL=${packet.ttl})")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Send to $peerIp failed: ${e.message}")
            }
        }
    }

    // ── Server socket ──────────────────────────────────────────────────────────

    private fun startServer() {
        scope.launch {
            try {
                serverSocket = ServerSocket(PORT)
                Log.d(TAG, "Listening on :$PORT")
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    launch { handleIncomingConnection(client) }
                }
            } catch (e: Exception) {
                if (isRunning) Log.e(TAG, "Server error: ${e.message}")
            }
        }
    }

    private suspend fun handleIncomingConnection(socket: Socket) {
        try {
            // Deliberately NOT using `socket.use{}` here — handshake needs to write back
            val input  = DataInputStream(socket.getInputStream())
            val packet = readPacket(input)

            if (packet == null) {
                socket.close()
                return
            }

            if (packet.type == PacketType.DISCOVER) {
                // Handshake — respond and register peer (server side)
                performHandshakeAsServer(socket, packet)
            } else {
                // Regular data packet — route it
                Log.d(TAG, "Received $packet")
                _incomingPackets.emit(packet)
            }

            socket.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling connection: ${e.message}")
            runCatching { socket.close() }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Build a DISCOVER (handshake) packet.
     * Phase 3: payload = our EC P-256 public key bytes (X.509 DER, ~91 bytes)
     * The receiver uses this to compute the ECDH shared key.
     */
    private fun buildDiscoverPacket(): MeshPacket = MeshPacket(
        type    = PacketType.DISCOVER,
        srcId   = ourId,
        dstId   = MeshID.BROADCAST,
        ttl     = 1,                          // Handshake never hops — TTL=1
        payload = keyManager.publicKeyBytes   // ← Phase 3: public key in payload
    )

    private fun sendDiscover(out: DataOutputStream) = writePacket(out, buildDiscoverPacket())

    private fun writePacket(out: DataOutputStream, packet: MeshPacket) {
        val encoded = MMPEncoder.encode(packet)
        out.writeInt(encoded.size)
        out.write(encoded)
        out.flush()
    }

    private fun readPacket(input: DataInputStream): MeshPacket? {
        val length = input.readInt()
        if (length <= 0 || length > MeshPacket.HEADER_SIZE + MeshPacket.MAX_PAYLOAD_SIZE) return null
        val data = ByteArray(length)
        input.readFully(data)
        return MMPDecoder.decode(data)
    }

    // ── Data model ────────────────────────────────────────────────────────────

    /**
     * Result of a completed handshake.
     * Phase 3: also carries the peer's raw public key bytes for ECDH key derivation.
     */
    data class PeerHandshake(
        val meshId: MeshID,
        val ipAddress: String,
        val publicKeyBytes: ByteArray = ByteArray(0)   // ← Phase 3
    )
}
