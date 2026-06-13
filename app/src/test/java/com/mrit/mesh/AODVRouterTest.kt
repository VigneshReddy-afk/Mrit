package com.mrit.mesh

import com.mrit.mesh.core.MeshID
import com.mrit.mesh.core.MeshPacket
import com.mrit.mesh.core.PacketType
import com.mrit.mesh.routing.AODVRouter
import com.mrit.mesh.routing.AODVRouter.RouteSnapshot
import com.mrit.mesh.routing.RoutingDecision
import org.junit.Assert.*
import org.junit.Test

class AODVRouterTest {

    private fun makePacket(
        srcId: MeshID,
        dstId: MeshID,
        type: PacketType = PacketType.MSG,
        ttl: Int = MeshPacket.DEFAULT_TTL
    ) = MeshPacket(
        type    = type,
        srcId   = srcId,
        dstId   = dstId,
        ttl     = ttl,
        payload = "test".toByteArray()
    )

    @Test
    fun `recordRoute publishes a direct route snapshot`() {
        val us     = MeshID.generate()
        val peer   = MeshID.generate()
        val router = AODVRouter(us)

        router.recordRoute(destination = peer, nextHop = peer)

        assertTrue(router.routes.value.contains(RouteSnapshot(peer, peer)))
    }

    @Test
    fun `recordRoute publishes a multi-hop route snapshot`() {
        val us      = MeshID.generate()
        val relay   = MeshID.generate()
        val faraway = MeshID.generate()
        val router  = AODVRouter(us)

        router.recordRoute(destination = faraway, nextHop = relay)

        assertTrue(router.routes.value.contains(RouteSnapshot(faraway, relay)))
    }

    @Test
    fun `direct route snapshot has equal destination and nextHop`() {
        val us     = MeshID.generate()
        val peer   = MeshID.generate()
        val router = AODVRouter(us)

        router.recordRoute(destination = peer, nextHop = peer)

        val entry = router.routes.value.first { it.destination == peer }
        assertEquals(entry.destination, entry.nextHop)
    }

    @Test
    fun `multi-hop route snapshot has a different nextHop than destination`() {
        val us      = MeshID.generate()
        val relay   = MeshID.generate()
        val faraway = MeshID.generate()
        val router  = AODVRouter(us)

        router.recordRoute(destination = faraway, nextHop = relay)

        val entry = router.routes.value.first { it.destination == faraway }
        assertNotEquals(entry.destination, entry.nextHop)
        assertEquals(relay, entry.nextHop)
    }

    @Test
    fun `recordRoute overwrites the previous nextHop for the same destination`() {
        val us      = MeshID.generate()
        val viaA    = MeshID.generate()
        val viaB    = MeshID.generate()
        val faraway = MeshID.generate()
        val router  = AODVRouter(us)

        router.recordRoute(destination = faraway, nextHop = viaA)
        router.recordRoute(destination = faraway, nextHop = viaB)

        val matches = router.routes.value.filter { it.destination == faraway }
        assertEquals(1, matches.size)
        assertEquals(viaB, matches.first().nextHop)
    }

    @Test
    fun `getNextHop returns the recorded next hop`() {
        val us      = MeshID.generate()
        val relay   = MeshID.generate()
        val faraway = MeshID.generate()
        val router  = AODVRouter(us)

        router.recordRoute(destination = faraway, nextHop = relay)

        assertEquals(relay, router.getNextHop(faraway))
    }

    @Test
    fun `getNextHop returns null for an unknown destination`() {
        val router = AODVRouter(MeshID.generate())
        assertNull(router.getNextHop(MeshID.generate()))
    }

    @Test
    fun `routeCount reflects the number of active routes`() {
        val router = AODVRouter(MeshID.generate())
        assertEquals(0, router.routeCount())

        router.recordRoute(MeshID.generate(), MeshID.generate())
        router.recordRoute(MeshID.generate(), MeshID.generate())

        assertEquals(2, router.routeCount())
    }

    @Test
    fun `process records a direct reverse route for the sender`() {
        val us     = MeshID.generate()
        val sender = MeshID.generate()
        val router = AODVRouter(us)

        router.process(makePacket(srcId = sender, dstId = us))

        assertTrue(router.routes.value.contains(RouteSnapshot(sender, sender)))
    }

    @Test
    fun `process returns Deliver for a packet addressed to us`() {
        val us     = MeshID.generate()
        val sender = MeshID.generate()
        val router = AODVRouter(us)

        val decision = router.process(makePacket(srcId = sender, dstId = us))
        assertTrue(decision is RoutingDecision.Deliver)
    }

    @Test
    fun `process returns StoreAndDiscover for an unknown destination`() {
        val us          = MeshID.generate()
        val sender      = MeshID.generate()
        val destination = MeshID.generate()
        val router      = AODVRouter(us)

        val decision = router.process(makePacket(srcId = sender, dstId = destination))
        assertTrue(decision is RoutingDecision.StoreAndDiscover)
    }

    @Test
    fun `process returns Forward when a route to the destination is known`() {
        val us          = MeshID.generate()
        val sender      = MeshID.generate()
        val destination = MeshID.generate()
        val router      = AODVRouter(us)

        router.recordRoute(destination = destination, nextHop = sender)

        val decision = router.process(makePacket(srcId = sender, dstId = destination))
        assertTrue(decision is RoutingDecision.Forward)
        assertEquals(sender, (decision as RoutingDecision.Forward).nextHop)
    }
}
