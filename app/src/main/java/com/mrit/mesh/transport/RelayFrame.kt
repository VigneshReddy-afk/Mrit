package com.mrit.mesh.transport

import com.mrit.mesh.core.MeshID

/**
 * RelayFrame — wire framing for the MRIT relay/gateway protocol (Phase 9).
 *
 * The relay server is a dumb forwarder: it doesn't parse MMP packets, it just
 * reads the DST_ID field (bytes [35..66] of a PACKET frame, i.e. MMP header
 * offset [34..65] shifted by the 1-byte frame type) and forwards the frame
 * verbatim to whichever connection most recently registered that MeshID.
 *
 * Frame layout — every WebSocket binary message starts with a 1-byte type:
 *
 *   REGISTER : [0x00][MeshID — 32 bytes]                — sent once after connect
 *   PACKET   : [0x01][MMP-encoded MeshPacket — N bytes] — relayed verbatim
 *
 * See PROTOCOL.md §13 for the full spec.
 */
object RelayFrame {

    const val TYPE_REGISTER: Byte = 0x00
    const val TYPE_PACKET: Byte = 0x01

    private const val REGISTER_FRAME_SIZE = 1 + 32 // type byte + MeshID

    /** Build a REGISTER frame announcing [meshId] to the relay server. */
    fun encodeRegister(meshId: MeshID): ByteArray =
        byteArrayOf(TYPE_REGISTER) + meshId.bytes

    /** Wrap an MMP-encoded packet ([packetBytes], from [com.mrit.mesh.protocol.MMPEncoder]) in a PACKET frame. */
    fun encodePacket(packetBytes: ByteArray): ByteArray =
        byteArrayOf(TYPE_PACKET) + packetBytes

    /** A decoded relay frame. */
    sealed class Frame {
        /** Peer announcing its MeshID — relay should register this connection under [meshId]. */
        data class Register(val meshId: MeshID) : Frame()

        /** An MMP-encoded [MeshPacket][com.mrit.mesh.core.MeshPacket], not yet decoded. */
        data class Packet(val packetBytes: ByteArray) : Frame() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Packet) return false
                return packetBytes.contentEquals(other.packetBytes)
            }
            override fun hashCode(): Int = packetBytes.contentHashCode()
        }
    }

    /**
     * Decode a raw WebSocket binary frame.
     * Returns null for empty, unknown-type, or malformed (wrong-length REGISTER) frames.
     */
    fun decode(data: ByteArray): Frame? {
        if (data.isEmpty()) return null
        return when (data[0]) {
            TYPE_REGISTER -> {
                if (data.size != REGISTER_FRAME_SIZE) return null
                Frame.Register(MeshID.fromBytes(data.copyOfRange(1, REGISTER_FRAME_SIZE)))
            }
            TYPE_PACKET -> {
                if (data.size <= 1) return null
                Frame.Packet(data.copyOfRange(1, data.size))
            }
            else -> null
        }
    }
}
