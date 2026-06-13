package com.mrit.mesh.transport

import android.content.Context
import android.util.Log
import com.mrit.mesh.core.MeshID
import com.mrit.mesh.core.MeshPacket
import com.mrit.mesh.protocol.MMPDecoder
import com.mrit.mesh.protocol.MMPEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RelayTransport — internet relay/gateway link for the MRIT mesh (Phase 9).
 *
 * Local transports ([WifiDirectTransport], [BleGattTransport], [BLETransport])
 * only reach peers within ~10-200m. RelayTransport extends reach to anywhere on
 * Earth by connecting to a [relayUrl] WebSocket server that forwards MMP packets
 * between MeshIDs — see PROTOCOL.md §13.
 *
 * The relay is a *fallback*, used only when [com.mrit.mesh.mesh.MeshNode] has no
 * local route to a destination. End-to-end encryption (ECDH + AES-256-GCM) means
 * the relay server never sees plaintext — it only reads the 32-byte DST_ID to
 * decide where to forward an opaque packet.
 *
 * If [relayUrl] is unreachable (no relay deployed, no internet), this transport
 * simply never connects — local mesh functionality is completely unaffected.
 */
class RelayTransport(
    private val context: Context,
    private val ourId: MeshID,
    private val relayUrl: String = DEFAULT_RELAY_URL
) {

    companion object {
        private const val TAG = "RelayTransport"

        /**
         * Default relay server address — loopback, for local testing only.
         * Point this at a deployed instance of relay/server.js (PROTOCOL.md §13)
         * for real long-distance reach.
         */
        const val DEFAULT_RELAY_URL = "ws://127.0.0.1:8765"

        private const val PING_INTERVAL_SECONDS = 20L

        /** Exponential backoff bounds for reconnecting — mirrors BleGattTransport (PROTOCOL.md §11.7). */
        private const val RECONNECT_BASE_DELAY_MS = 1_000L
        private const val RECONNECT_MAX_DELAY_MS = 30_000L
        private const val RECONNECT_MAX_SHIFT = 5 // base * 2^5 = base * 32
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var stopped = true

    private val connected = AtomicBoolean(false)

    private val _incomingPackets = MutableSharedFlow<MeshPacket>(extraBufferCapacity = 64)

    /** Packets relayed to us from the global network — addressed to [ourId]. */
    val incomingPackets: SharedFlow<MeshPacket> = _incomingPackets

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    fun start() {
        stopped = false
        connect()
    }

    fun stop() {
        stopped = true
        reconnectJob?.cancel()
        webSocket?.close(1000, "stop")
        webSocket = null
        connected.set(false)
        scope.cancel()
    }

    /** True if the WebSocket connection to the relay is currently open. */
    fun isConnected(): Boolean = connected.get()

    // ── Send ───────────────────────────────────────────────────────────────────

    /**
     * Send [packet] to the relay for forwarding to its `dstId`.
     * Returns false if not currently connected — caller should fall back to
     * local store-and-forward in that case.
     */
    fun send(packet: MeshPacket): Boolean {
        val ws = webSocket ?: return false
        if (!connected.get()) return false
        val frame = RelayFrame.encodePacket(MMPEncoder.encode(packet))
        return ws.send(frame.toByteString())
    }

    // ── Connection management ─────────────────────────────────────────────────

    private fun connect() {
        if (stopped) return

        val request = Request.Builder().url(relayUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Connected to relay $relayUrl")
                connected.set(true)
                reconnectAttempt = 0
                webSocket.send(RelayFrame.encodeRegister(ourId).toByteString())
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val frame = RelayFrame.decode(bytes.toByteArray()) ?: return
                if (frame is RelayFrame.Frame.Packet) {
                    val packet = MMPDecoder.decode(frame.packetBytes) ?: return
                    _incomingPackets.tryEmit(packet)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Relay connection closed: $reason")
                connected.set(false)
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Relay connection failed: ${t.message}")
                connected.set(false)
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (stopped) return
        reconnectJob?.cancel()
        val shift = reconnectAttempt.coerceAtMost(RECONNECT_MAX_SHIFT)
        val delayMs = (RECONNECT_BASE_DELAY_MS shl shift).coerceAtMost(RECONNECT_MAX_DELAY_MS)
        reconnectAttempt++
        reconnectJob = scope.launch {
            delay(delayMs)
            connect()
        }
    }
}
