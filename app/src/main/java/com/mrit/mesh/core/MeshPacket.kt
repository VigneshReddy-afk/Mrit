package com.mrit.mesh.core

/**
 * MeshPacket — the fundamental unit of data in the MRIT Mobile Mesh Protocol (MMP).
 *
 * Binary wire format (69-byte fixed header + variable payload):
 *
 * ┌──────────┬──────────┬────────────┬────────────┬──────────┬──────────┬──────────────┐
 * │ VERSION  │   TYPE   │   SRC_ID   │   DST_ID   │   TTL    │  LENGTH  │   PAYLOAD    │
 * │  1 byte  │  1 byte  │  32 bytes  │  32 bytes  │  1 byte  │  2 bytes │   N bytes    │
 * └──────────┴──────────┴────────────┴────────────┴──────────┴──────────┴──────────────┘
 *   [0]         [1]        [2..33]      [34..65]     [66]      [67..68]    [69..69+N-1]
 *
 * Total minimum size: 69 bytes (empty payload)
 * Total maximum size: 69 + 65535 = 65604 bytes
 */
data class MeshPacket(
    val version: Byte = MMP_VERSION,
    val type: PacketType,
    val srcId: MeshID,
    val dstId: MeshID,
    val ttl: Int,              // 0–255, decremented at each hop — packet dropped when it hits 0
    val payload: ByteArray
) {

    init {
        require(ttl in 0..255)          { "TTL must be 0–255, got $ttl" }
        require(payload.size <= MAX_PAYLOAD_SIZE) {
            "Payload size ${payload.size} exceeds max $MAX_PAYLOAD_SIZE bytes"
        }
    }

    /** Return a copy with TTL decremented by 1. Called at every relay hop. */
    fun decrementTTL(): MeshPacket = copy(ttl = ttl - 1)

    /** True if this packet can still be forwarded (TTL > 0). */
    fun isAlive(): Boolean = ttl > 0

    /** True if this packet's destination is our own node. */
    fun isForUs(ourId: MeshID): Boolean = dstId == ourId

    /** True if this is a broadcast packet intended for all nearby nodes. */
    fun isBroadcast(): Boolean = dstId == MeshID.BROADCAST

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MeshPacket) return false
        return version == other.version
            && type == other.type
            && srcId == other.srcId
            && dstId == other.dstId
            && ttl == other.ttl
            && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = version.toInt()
        result = 31 * result + type.hashCode()
        result = 31 * result + srcId.hashCode()
        result = 31 * result + dstId.hashCode()
        result = 31 * result + ttl
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String =
        "MeshPacket(v=$version type=$type src=${srcId.shortId()} dst=${dstId.shortId()} ttl=$ttl payloadBytes=${payload.size})"

    companion object {
        const val MMP_VERSION: Byte = 0x01
        const val HEADER_SIZE: Int = 69            // bytes
        const val MAX_PAYLOAD_SIZE: Int = 65535    // bytes (2-byte unsigned length field)
        const val DEFAULT_TTL: Int = 64            // hops — standard for mesh protocols
        const val SOS_TTL: Int = 255               // maximum TTL for emergency packets
    }
}

/**
 * All packet types supported by MMP v0.1
 */
enum class PacketType(val byte: Byte) {
    MSG(0x01),       // User message — text, file chunk, or binary blob
    ACK(0x02),       // Delivery acknowledgement
    DISCOVER(0x03),  // Node discovery broadcast — "who is nearby?"
    ROUTE(0x04),     // AODV routing control (RREQ / RREP)
    SOS(0x05);       // Emergency broadcast — highest priority, max TTL, no drop

    companion object {
        fun fromByte(b: Byte): PacketType =
            entries.firstOrNull { it.byte == b }
                ?: throw IllegalArgumentException("Unknown MMP packet type: 0x${"%02x".format(b)}")
    }
}
