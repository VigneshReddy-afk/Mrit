package com.mrit.mesh.protocol

import com.mrit.mesh.core.MeshPacket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MMPEncoder — serialises a MeshPacket into raw bytes for transmission.
 *
 * All multi-byte fields are big-endian (network byte order).
 *
 * Output layout:
 *   [0]      VERSION  — 1 byte
 *   [1]      TYPE     — 1 byte
 *   [2..33]  SRC_ID   — 32 bytes
 *   [34..65] DST_ID   — 32 bytes
 *   [66]     TTL      — 1 byte (unsigned 0–255)
 *   [67..68] LENGTH   — 2 bytes (unsigned short, big-endian)
 *   [69..]   PAYLOAD  — LENGTH bytes
 */
object MMPEncoder {

    /**
     * Encode a MeshPacket to a byte array ready for transmission.
     *
     * @param packet the packet to encode
     * @return raw bytes: 69-byte header + payload
     */
    fun encode(packet: MeshPacket): ByteArray {
        val totalSize = MeshPacket.HEADER_SIZE + packet.payload.size
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        buffer.put(packet.version)                         // [0]      VERSION
        buffer.put(packet.type.byte)                       // [1]      TYPE
        buffer.put(packet.srcId.bytes)                     // [2..33]  SRC_ID
        buffer.put(packet.dstId.bytes)                     // [34..65] DST_ID
        buffer.put(packet.ttl.toByte())                    // [66]     TTL
        buffer.putShort(packet.payload.size.toShort())     // [67..68] LENGTH
        buffer.put(packet.payload)                         // [69..]   PAYLOAD

        return buffer.array()
    }
}
