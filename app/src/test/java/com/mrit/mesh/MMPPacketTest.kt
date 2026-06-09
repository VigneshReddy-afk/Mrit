package com.mrit.mesh

import com.mrit.mesh.core.MeshID
import com.mrit.mesh.core.MeshPacket
import com.mrit.mesh.core.PacketType
import com.mrit.mesh.protocol.MMPDecoder
import com.mrit.mesh.protocol.MMPEncoder
import org.junit.Assert.*
import org.junit.Test

class MMPPacketTest {

    private fun makePacket(
        type: PacketType = PacketType.MSG,
        ttl: Int = MeshPacket.DEFAULT_TTL,
        payload: ByteArray = "hello mesh".toByteArray()
    ) = MeshPacket(
        type    = type,
        srcId   = MeshID.generate(),
        dstId   = MeshID.generate(),
        ttl     = ttl,
        payload = payload
    )

    @Test
    fun `encoded packet has correct total size`() {
        val payload = ByteArray(100)
        val packet  = makePacket(payload = payload)
        val encoded = MMPEncoder.encode(packet)
        assertEquals(MeshPacket.HEADER_SIZE + 100, encoded.size)
    }

    @Test
    fun `encode then decode round-trips correctly`() {
        val original = makePacket()
        val encoded  = MMPEncoder.encode(original)
        val decoded  = MMPDecoder.decode(encoded)

        assertNotNull(decoded)
        assertEquals(original.type,    decoded!!.type)
        assertEquals(original.srcId,   decoded.srcId)
        assertEquals(original.dstId,   decoded.dstId)
        assertEquals(original.ttl,     decoded.ttl)
        assertArrayEquals(original.payload, decoded.payload)
    }

    @Test
    fun `decode returns null for data shorter than header`() {
        val tooShort = ByteArray(MeshPacket.HEADER_SIZE - 1)
        assertNull(MMPDecoder.decode(tooShort))
    }

    @Test
    fun `decode returns null for all-zero garbage data`() {
        // All zeros: type byte 0x00 is not a valid PacketType
        val garbage = ByteArray(100)
        assertNull(MMPDecoder.decode(garbage))
    }

    @Test
    fun `decrementTTL reduces TTL by exactly 1`() {
        val packet    = makePacket(ttl = 10)
        val decremented = packet.decrementTTL()
        assertEquals(9, decremented.ttl)
    }

    @Test
    fun `packet with TTL 0 is not alive`() {
        val dead = makePacket(ttl = 0)
        assertFalse(dead.isAlive())
    }

    @Test
    fun `packet with TTL 1 is alive`() {
        val alive = makePacket(ttl = 1)
        assertTrue(alive.isAlive())
    }

    @Test
    fun `isBroadcast returns true only for BROADCAST dst`() {
        val broadcast = makePacket().copy(dstId = MeshID.BROADCAST)
        val unicast   = makePacket()
        assertTrue(broadcast.isBroadcast())
        assertFalse(unicast.isBroadcast())
    }

    @Test
    fun `encode empty payload produces 69-byte packet`() {
        val packet  = makePacket(payload = ByteArray(0))
        val encoded = MMPEncoder.encode(packet)
        assertEquals(MeshPacket.HEADER_SIZE, encoded.size)
    }
}
