package com.mrit.mesh.routing

import android.util.Log
import com.mrit.mesh.core.MeshID
import com.mrit.mesh.core.MeshPacket
import com.mrit.mesh.core.PacketType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * AODVRouter — Ad-hoc On-Demand Distance Vector routing for the MRIT mesh.
 *
 * AODV is a well-proven algorithm designed specifically for mobile ad-hoc networks (MANETs).
 * It builds routes on demand (only when needed) rather than maintaining a full topology map,
 * which is critical because phones move and routes change constantly.
 *
 * Route discovery flow:
 *   1. Node A wants to send to Node D — route unknown
 *   2. A broadcasts RREQ (Route Request) to all neighbours
 *   3. Each neighbour that doesn't know D re-broadcasts RREQ (TTL decrements each hop)
 *   4. D (or a node with a fresh route to D) sends RREP (Route Reply) back along reverse path
 *   5. A receives RREP — route to D now known, send queued data
 *
 * Routes expire after [ROUTE_EXPIRY_MS] to handle node movement.
 *
 * Reference: RFC 3561 — Ad hoc On-Demand Distance Vector (AODV) Routing
 */
class AODVRouter(private val ourId: MeshID) {

    companion object {
        private const val TAG = "AODVRouter"
        private const val ROUTE_EXPIRY_MS    = 30_000L  // 30 s — phones move, routes go stale
        private const val CLEANUP_INTERVAL_MS = 10_000L  // 10 s — how often expired routes are purged
        private const val MAX_SEEN_REQUESTS  = 512       // Cap memory for seen RREQ IDs
    }

    /** Routing table: destination MeshID → best next-hop MeshID + expiry timestamp */
    private val routingTable = HashMap<MeshID, RouteEntry>()

    /** Seen RREQ unique IDs — prevents re-broadcasting the same request (loop prevention) */
    private val seenRequests = LinkedHashSet<String>()

    private val scope = CoroutineScope(Dispatchers.Default)

    init {
        startMaintenanceLoop()
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Determine how to handle an incoming packet.
     *
     * @param packet the received packet
     * @return a [RoutingDecision] telling the caller what to do
     */
    fun process(packet: MeshPacket): RoutingDecision {
        // Every packet tells us a valid reverse route back to its source
        recordRoute(destination = packet.srcId, nextHop = packet.srcId)

        return when {
            packet.isForUs(ourId)   -> RoutingDecision.Deliver
            packet.isBroadcast()    -> RoutingDecision.DeliverAndForward
            !packet.isAlive()       -> RoutingDecision.Drop("TTL expired")
            packet.type == PacketType.SOS -> {
                // SOS always forwarded regardless of known route
                RoutingDecision.DeliverAndForward
            }
            else -> {
                val nextHop = getNextHop(packet.dstId)
                if (nextHop != null) {
                    RoutingDecision.Forward(nextHop)
                } else {
                    RoutingDecision.StoreAndDiscover(packet.dstId)
                }
            }
        }
    }

    /**
     * Look up the next hop toward [destination].
     * Returns null if no route is known (or if the route has expired).
     */
    fun getNextHop(destination: MeshID): MeshID? {
        val entry = routingTable[destination] ?: return null
        return if (System.currentTimeMillis() <= entry.expiryTime) {
            entry.nextHop
        } else {
            routingTable.remove(destination)
            null
        }
    }

    /**
     * Record a route to [destination] via [nextHop].
     * Called when an RREP arrives, or when we receive any packet from a known source.
     */
    fun recordRoute(destination: MeshID, nextHop: MeshID) {
        val expiresAt = System.currentTimeMillis() + ROUTE_EXPIRY_MS
        routingTable[destination] = RouteEntry(nextHop, expiresAt)
        Log.d(TAG, "Route: ${destination.shortId()} via ${nextHop.shortId()} (expires in ${ROUTE_EXPIRY_MS / 1000}s)")
    }

    /**
     * Build a Route Request packet — broadcast to discover the path to [destination].
     *
     * Payload format (per PROTOCOL.md): "RREQ:{requestId}:{destinationFullHex}"
     *   - requestId          : random 8 hex chars — uniquely identifies this RREQ
     *                          for loop prevention via [hasSeenRequest]/[rememberRequest]
     *   - destinationFullHex : full 64-char hex MeshID (NOT the 8-char shortId —
     *                          MeshID.fromHex() requires exactly 64 chars)
     *
     * The request ID is remembered immediately so we never re-process our own RREQ
     * if it loops back to us.
     */
    fun buildRouteRequest(destination: MeshID): MeshPacket {
        val requestId = java.util.UUID.randomUUID().toString().replace("-", "").take(8)
        rememberRequest(requestId)

        return MeshPacket(
            type    = PacketType.ROUTE,
            srcId   = ourId,
            dstId   = destination,
            ttl     = MeshPacket.DEFAULT_TTL,
            payload = "RREQ:$requestId:${destination}".toByteArray(Charsets.UTF_8)
        )
    }

    /**
     * Check if we have already seen and forwarded this request ID.
     * Prevents broadcast storms (loop prevention).
     */
    fun hasSeenRequest(rreqId: String): Boolean = seenRequests.contains(rreqId)

    fun rememberRequest(rreqId: String) {
        if (seenRequests.size >= MAX_SEEN_REQUESTS) {
            // Evict the oldest entry (LinkedHashSet preserves insertion order)
            seenRequests.remove(seenRequests.iterator().next())
        }
        seenRequests.add(rreqId)
    }

    /** Total number of active (non-expired) routes */
    fun routeCount(): Int = routingTable.count { (_, v) -> System.currentTimeMillis() <= v.expiryTime }

    // ── Maintenance ────────────────────────────────────────────────────────────

    /** Periodically remove routes that have expired due to node movement */
    private fun startMaintenanceLoop() {
        scope.launch {
            while (true) {
                delay(CLEANUP_INTERVAL_MS)
                val now = System.currentTimeMillis()
                val expired = routingTable.entries
                    .filter { it.value.expiryTime < now }
                    .map { it.key }
                expired.forEach { routingTable.remove(it) }
                if (expired.isNotEmpty()) {
                    Log.d(TAG, "Purged ${expired.size} expired routes — ${routeCount()} routes remaining")
                }
            }
        }
    }

    // ── Data models ────────────────────────────────────────────────────────────

    data class RouteEntry(
        val nextHop: MeshID,
        val expiryTime: Long          // System.currentTimeMillis() + ROUTE_EXPIRY_MS
    )
}

/**
 * What the router tells the caller to do with a received packet.
 */
sealed class RoutingDecision {
    /** Packet is addressed to this node — deliver to the application layer */
    object Deliver : RoutingDecision()

    /** Broadcast packet — deliver locally AND forward to all neighbours */
    object DeliverAndForward : RoutingDecision()

    /** TTL reached 0 or packet is malformed — discard */
    data class Drop(val reason: String) : RoutingDecision()

    /** Route to destination is known — forward to this next-hop node */
    data class Forward(val nextHop: MeshID) : RoutingDecision()

    /** Route to destination is unknown — store packet, send RREQ to discover route */
    data class StoreAndDiscover(val destination: MeshID) : RoutingDecision()
}
