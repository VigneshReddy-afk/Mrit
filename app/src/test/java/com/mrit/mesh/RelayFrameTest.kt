package com.mrit.mesh

import com.mrit.mesh.core.MeshID
import com.mrit.mesh.core.MeshPacket
import com.mrit.mesh.core.PacketType
import com.mrit.mesh.protocol.MMPEncoder
import com.mrit.mesh.transport.RelayFrame
import org.junit.Assert.*
import org.junit.Test

class RelayFrameTest {

    @Test
    fun `encodeRegister produces a 33-byte frame with type 0x00`() {
        val meshId = MeshID.generate()
        val frame = RelayFrame.encodeRegister(meshId)

        assertEquals(33, frame.size)
        assertEquals(RelayFrame.TYPE_REGISTER, frame[0])
        assertArrayEquals(meshId.bytes, frame.copyOfRange(1, 33))
    }

    @Test
    fun `decode REGISTER frame round-trips the MeshID`() {
        val meshId = MeshID.generate()
        val frame = RelayFrame.encodeRegister(meshId)

        val decoded = RelayFrame.decode(frame)

        assertTrue(decoded is RelayFrame.Frame.Register)
        assertEquals(meshId, (decoded as RelayFrame.Frame.Register).meshId)
    }

    @Test
    fun `decode REGISTER frame with wrong length returns null`() {
        val tooShort = ByteArray(10).also { it[0] = RelayFrame.TYPE_REGISTER }
        assertNull(RelayFrame.decode(tooShort))
    }

    @Test
    fun `encodePacket prefixes type 0x01 onto the MMP bytes`() {
        val packet = MeshPacket(
            type = PacketType.MSG,
            srcId = MeshID.generate(),
            dstId = MeshID.generate(),
            ttl = MeshPacket.DEFAULT_TTL,
            payload = "hello mesh".toByteArray()
        )
        val packetBytes = MMPEncoder.encode(packet)
        val frame = RelayFrame.encodePacket(packetBytes)

        assertEquals(packetBytes.size + 1, frame.size)
        assertEquals(RelayFrame.TYPE_PACKET, frame[0])
        assertArrayEquals(packetBytes, frame.copyOfRange(1, frame.size))
    }

    @Test
    fun `decode PACKET frame round-trips the MMP bytes`() {
        val packet = MeshPacket(
            type = PacketType.MSG,
            srcId = MeshID.generate(),
            dstId = MeshID.generate(),
            ttl = MeshPacket.DEFAULT_TTL,
            payload = "hello mesh".toByteArray()
        )
        val packetBytes = MMPEncoder.encode(packet)
        val frame = RelayFrame.encodePacket(packetBytes)

        val decoded = RelayFrame.decode(frame)

        assertTrue(decoded is RelayFrame.Frame.Packet)
        assertArrayEquals(packetBytes, (decoded as RelayFrame.Frame.Packet).packetBytes)
    }

    @Test
    fun `decode returns null for empty data`() {
        assertNull(RelayFrame.decode(ByteArray(0)))
    }

    @Test
    fun `decode returns null for unknown frame type`() {
        val unknown = byteArrayOf(0x7F, 0x00, 0x01)
        assertNull(RelayFrame.decode(unknown))
    }

    @Test
    fun `decode PACKET frame with only the type byte returns null`() {
        val justType = byteArrayOf(RelayFrame.TYPE_PACKET)
        assertNull(RelayFrame.decode(justType))
    }
}
