package com.mrit.mesh.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mrit.mesh.R
import com.mrit.mesh.core.MeshID
import com.mrit.mesh.core.MeshPacket
import com.mrit.mesh.core.PacketType
import com.mrit.mesh.routing.AODVRouter
import com.mrit.mesh.routing.RoutingDecision
import com.mrit.mesh.storage.PacketStore
import com.mrit.mesh.transport.BLETransport
import com.mrit.mesh.transport.WifiDirectTransport
import kotlinx.coroutines.*

/**
 * MeshService — foreground service that keeps the MRIT mesh alive in the background.
 *
 * Responsibilities:
 *   - Keeps BLETransport running for always-on neighbor discovery
 *   - Keeps WifiDirectTransport ready for data transfer
 *   - Routes every incoming packet through AODVRouter
 *   - Delivers packets addressed to us to the app layer
 *   - Stores undeliverable packets in PacketStore
 *   - Delivers stored packets when a destination comes into range
 */
class MeshService : Service() {

    companion object {
        private const val TAG              = "MeshService"
        private const val NOTIFICATION_ID  = 1001
        private const val CHANNEL_ID       = "mrit_mesh_channel"

        // SharedPreferences key for persisting our MeshID across restarts
        private const val PREFS_NAME       = "mrit_prefs"
        private const val KEY_MESH_ID      = "mesh_id_hex"

        fun startIntent(context: Context) = Intent(context, MeshService::class.java)
        fun stopIntent(context: Context)  = Intent(context, MeshService::class.java)
    }

    private lateinit var ourId: MeshID
    private lateinit var router: AODVRouter
    private lateinit var packetStore: PacketStore
    private lateinit var bleTransport: BLETransport
    private lateinit var wifiTransport: WifiDirectTransport

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        ourId        = loadOrCreateMeshId()
        router       = AODVRouter(ourId)
        packetStore  = PacketStore(this)
        bleTransport = BLETransport(this, ourId)
        wifiTransport = WifiDirectTransport(this)

        Log.d(TAG, "MeshService created — our ID: ${ourId.shortId()} (${ourId})")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        bleTransport.start()
        wifiTransport.start()

        observeIncomingPackets()
        observeDiscoveredNodes()
        packetStore.purgeExpired()

        Log.d(TAG, "MeshService running")
        return START_STICKY  // Restart automatically if killed by OS
    }

    override fun onDestroy() {
        bleTransport.stop()
        wifiTransport.stop()
        scope.cancel()
        super.onDestroy()
        Log.d(TAG, "MeshService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null  // Not a bound service

    // ── Packet routing ─────────────────────────────────────────────────────────

    private fun observeIncomingPackets() {
        scope.launch {
            wifiTransport.incomingPackets.collect { packet ->
                handlePacket(packet)
            }
        }
    }

    private fun handlePacket(packet: MeshPacket) {
        Log.d(TAG, "Handling $packet")

        when (val decision = router.process(packet)) {

            is RoutingDecision.Deliver -> {
                Log.d(TAG, "Delivering packet to app: type=${packet.type}")
                deliverToApp(packet)
            }

            is RoutingDecision.DeliverAndForward -> {
                deliverToApp(packet)
                // Forward to all connected peers (broadcast behaviour)
                forwardToAllPeers(packet.decrementTTL())
            }

            is RoutingDecision.Forward -> {
                Log.d(TAG, "Forwarding to ${decision.nextHop.shortId()}")
                // wifiTransport.send(packet.decrementTTL(), resolveIpFor(decision.nextHop))
                // IP resolution will be implemented in Phase 2 (peer address table)
            }

            is RoutingDecision.StoreAndDiscover -> {
                Log.d(TAG, "Route unknown for ${decision.destination.shortId()} — storing packet")
                packetStore.store(packet)
                val rreq = router.buildRouteRequest(decision.destination)
                forwardToAllPeers(rreq)
            }

            is RoutingDecision.Drop -> {
                Log.d(TAG, "Dropping packet: ${decision.reason}")
            }
        }
    }

    // ── Node discovery ─────────────────────────────────────────────────────────

    private fun observeDiscoveredNodes() {
        scope.launch {
            bleTransport.discoveredNodes.collect { node ->
                Log.d(TAG, "New neighbour discovered via BLE: ${node.bluetoothAddress} (${node.rssi} dBm)")
                wifiTransport.connectToPeer(node.bluetoothAddress)

                // Check if we have any pending packets for this node
                // (Node's MeshID learned during WiFi Direct handshake — Phase 2)
            }
        }
    }

    // ── App-layer delivery (placeholder for Phase 2 IPC) ──────────────────────

    private fun deliverToApp(packet: MeshPacket) {
        // Phase 2: broadcast via LocalBroadcastManager or a bound service interface
        // For now, log the content
        if (packet.type == PacketType.MSG) {
            val text = packet.payload.toString(Charsets.UTF_8)
            Log.i(TAG, "MESSAGE from ${packet.srcId.shortId()}: $text")
        } else if (packet.type == PacketType.SOS) {
            Log.w(TAG, "SOS from ${packet.srcId.shortId()}!")
        }
    }

    private fun forwardToAllPeers(packet: MeshPacket) {
        // Phase 2: iterate connected peer list and call wifiTransport.send() for each
        Log.d(TAG, "Forwarding ${packet.type} to all connected peers (TTL=${packet.ttl})")
    }

    // ── MeshID persistence ─────────────────────────────────────────────────────

    private fun loadOrCreateMeshId(): MeshID {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_MESH_ID, null)

        return if (stored != null) {
            MeshID.fromHex(stored).also { Log.d(TAG, "Loaded existing MeshID: ${it.shortId()}") }
        } else {
            MeshID.generate().also { newId ->
                prefs.edit().putString(KEY_MESH_ID, newId.toString()).apply()
                Log.d(TAG, "Generated new MeshID: ${newId.shortId()}")
            }
        }
    }

    // ── Foreground notification ────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MRIT Mesh Network",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the MRIT mesh node running in the background"
            }
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MRIT Mesh Active")
            .setContentText("Node ${ourId.shortId()} — scanning for peers")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }
}
