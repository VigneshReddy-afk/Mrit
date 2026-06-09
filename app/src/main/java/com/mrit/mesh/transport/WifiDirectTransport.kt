package com.mrit.mesh.transport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import com.mrit.mesh.core.MeshPacket
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
 * Role: Bulk data transfer (fast, ~200 m range per hop, ~250 Mbps)
 * Discovery is handled by BLETransport (always-on, low-power).
 *
 * Architecture:
 *   Group Owner → ServerSocket on PORT 8988, accepts incoming connections
 *   Client      → connects to group owner IP via Socket
 *
 * Data framing over TCP:
 *   [4 bytes: packet length as Int] [N bytes: MMP encoded packet]
 */
class WifiDirectTransport(private val context: Context) {

    companion object {
        private const val TAG  = "WiFiDirectTransport"
        private const val PORT = 8988
        private const val SOCKET_TIMEOUT_MS = 10_000
    }

    private val manager: WifiP2pManager by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    }
    private lateinit var channel: WifiP2pManager.Channel

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var isRunning = false

    /** All incoming MeshPackets are emitted here for the router to process */
    private val _incomingPackets = MutableSharedFlow<MeshPacket>(extraBufferCapacity = 128)
    val incomingPackets: SharedFlow<MeshPacket> = _incomingPackets

    /** WiFi P2P broadcast receiver — listens for state and connection changes */
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        Log.w(TAG, "WiFi Direct is disabled on this device")
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val netInfo = intent.getParcelableExtra<android.net.NetworkInfo>(
                        WifiP2pManager.EXTRA_NETWORK_INFO
                    )
                    if (netInfo?.isConnected == true) {
                        // Connection established — find out if we are group owner or client
                        manager.requestConnectionInfo(channel, connectionInfoListener)
                    }
                }
            }
        }
    }

    /** Handles result of WifiP2pManager.requestConnectionInfo() */
    private val connectionInfoListener = WifiP2pManager.ConnectionInfoListener { info ->
        if (!info.groupFormed) return@ConnectionInfoListener

        if (info.isGroupOwner) {
            Log.d(TAG, "We are the group owner — starting server socket")
            startServer()
        } else {
            val ownerAddress = info.groupOwnerAddress?.hostAddress ?: return@ConnectionInfoListener
            Log.d(TAG, "We are a client — group owner is at $ownerAddress")
            // Address stored by MeshNode for outgoing sends
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

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
        Log.d(TAG, "WifiDirectTransport started")
    }

    fun stop() {
        isRunning = false
        serverSocket?.close()
        scope.cancel()
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        Log.d(TAG, "WifiDirectTransport stopped")
    }

    // ── Peer discovery ─────────────────────────────────────────────────────────

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

    // ── Data transfer ──────────────────────────────────────────────────────────

    /**
     * Send a MeshPacket to a peer by their IP address.
     * Called by the router after a route is known.
     */
    fun send(packet: MeshPacket, peerIpAddress: String) {
        scope.launch {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(peerIpAddress, PORT), SOCKET_TIMEOUT_MS)
                    val out = DataOutputStream(socket.getOutputStream())
                    val encoded = MMPEncoder.encode(packet)
                    out.writeInt(encoded.size)   // 4-byte length prefix
                    out.write(encoded)
                    out.flush()
                    Log.d(TAG, "Sent ${encoded.size} bytes to $peerIpAddress (${packet.type})")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Send to $peerIpAddress failed: ${e.message}")
            }
        }
    }

    /** Start the server socket — only called when this device is group owner */
    private fun startServer() {
        scope.launch {
            try {
                serverSocket = ServerSocket(PORT)
                Log.d(TAG, "Server socket listening on port $PORT")
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    launch { handleIncomingConnection(client) }
                }
            } catch (e: Exception) {
                if (isRunning) Log.e(TAG, "Server error: ${e.message}")
            }
        }
    }

    /** Read one MeshPacket from an incoming socket connection */
    private suspend fun handleIncomingConnection(socket: Socket) {
        try {
            socket.use {
                val input = DataInputStream(socket.getInputStream())
                val length = input.readInt()

                // Sanity check — reject absurdly large declared sizes
                if (length <= 0 || length > MeshPacket.HEADER_SIZE + MeshPacket.MAX_PAYLOAD_SIZE) {
                    Log.w(TAG, "Rejected packet with invalid declared length: $length")
                    return
                }

                val data = ByteArray(length)
                input.readFully(data)

                val packet = MMPDecoder.decode(data)
                if (packet != null) {
                    Log.d(TAG, "Received $packet")
                    _incomingPackets.emit(packet)
                } else {
                    Log.w(TAG, "Received malformed packet from ${socket.inetAddress.hostAddress} — dropped")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading from client: ${e.message}")
        }
    }
}
