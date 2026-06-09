package com.mrit.mesh

import com.mrit.mesh.core.MeshID
import com.mrit.mesh.core.MeshPacket
import com.mrit.mesh.core.PacketType
import com.mrit.mesh.reliability.AckManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AckManagerTest {

    private fun makePacket(payload: String = "test") = MeshPacket(
        type    = PacketType.MSG,
        srcId   = MeshID.generate(),
        dstId   = MeshID.generate(),
        ttl     = 64,
        payload = payload.toByteArray()
    )

    @Test
    fun `pendingCount increases when packet tracked`() {
        val scope   = TestScope()
        val ack     = AckManager(scope, onRetry = { _, _ -> }, onDeliveryFailed = {})
        val packet  = makePacket()

        assertEquals(0, ack.pendingCount())
        ack.trackOutgoing(packet, "192.168.49.1")
        assertEquals(1, ack.pendingCount())
    }

    @Test
    fun `acknowledge removes packet from pending`() {
        val scope  = TestScope()
        val ack    = AckManager(scope, onRetry = { _, _ -> }, onDeliveryFailed = {})
        val packet = makePacket()

        ack.trackOutgoing(packet, "192.168.49.1")
        assertEquals(1, ack.pendingCount())

        val fp      = ack.fingerprint(packet)
        val payload = "ACK:$fp"
        ack.acknowledge(payload)

        assertEquals(0, ack.pendingCount())
    }

    @Test
    fun `acknowledge ignores invalid payload`() {
        val scope  = TestScope()
        val ack    = AckManager(scope, onRetry = { _, _ -> }, onDeliveryFailed = {})
        val packet = makePacket()

        ack.trackOutgoing(packet, "192.168.49.1")
        ack.acknowledge("GARBAGE")          // should not crash or remove anything
        assertEquals(1, ack.pendingCount())
    }

    @Test
    fun `fingerprint is same for identical packet`() {
        val scope  = TestScope()
        val ack    = AckManager(scope, onRetry = { _, _ -> }, onDeliveryFailed = {})
        val packet = makePacket("hello")

        assertEquals(ack.fingerprint(packet), ack.fingerprint(packet))
    }

    @Test
    fun `fingerprint differs for different payloads`() {
        val scope = TestScope()
        val ack   = AckManager(scope, onRetry = { _, _ -> }, onDeliveryFailed = {})

        val p1 = makePacket("hello")
        val p2 = makePacket("world")
        assertNotEquals(ack.fingerprint(p1), ack.fingerprint(p2))
    }

    @Test
    fun `buildAckPayload starts with ACK prefix`() {
        val scope  = TestScope()
        val ack    = AckManager(scope, onRetry = { _, _ -> }, onDeliveryFailed = {})
        val packet = makePacket()

        val payload = ack.buildAckPayload(packet).toString(Charsets.UTF_8)
        assertTrue(payload.startsWith("ACK:"))
    }
}
