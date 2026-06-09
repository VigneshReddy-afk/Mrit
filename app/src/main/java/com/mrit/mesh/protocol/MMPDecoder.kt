package com.mrit.mesh.protocol

import com.mrit.mesh.core.MeshID
import com.mrit.mesh.core.MeshPacket
import com.mrit.mesh.core.PacketType
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MMPDecoder — deserialises raw bytes back into a MeshPacket.
 *
 * Returns null on any malformed input — never throws.
 * Malformed packets are silently dropped (standard mesh protocol behaviour).
 */
object MMPDecoder {

    /**
     * Decode raw bytes into a MeshPacket.
     *
     * @param data raw bytes received from transport layer
     * @return parsed MeshPacket, or null if data is malformed or too short
     */
    fun decode(data: ByteArray): MeshPacket? {
        if (data.size < MeshPacket.HEADER_SIZE) return null

        return try {
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

            val version = buffer.get()                           // [0]      VERSION
            val type    = PacketType.fromByte(buffer.get())      // [1]      TYPE

            val srcBytes = ByteArray(32).also { buffer.get(it) } // [2..33]  SRC_ID
            val dstBytes = ByteArray(32).also { buffer.get(it) } // [34..65] DST_ID

            val ttl           = buffer.get().toInt() and 0xFF    // [66]     TTL  (unsigned)
            val payloadLength = buffer.getShort().toInt() and 0xFFFF // [67..68] LENGTH (unsigned)

            // Guard: make sure the declared payload length actually fits in the data
            if (data.size < MeshPacket.HEADER_SIZE + payloadLength) return null

            val payload = ByteArray(payloadLength).also { buffer.get(it) } // [69..] PAYLOAD

            MeshPacket(
                version = version,
                type    = type,
                srcId   = MeshID.fromBytes(srcBytes),
                dstId   = MeshID.fromBytes(dstBytes),
                ttl     = ttl,
                payload = payload
            )
        } catch (e: Exception) {
            null  // Malformed packet — drop silently
        }
    }
}
