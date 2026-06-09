package com.mrit.mesh.transfer

import android.util.Log
import com.mrit.mesh.core.MeshID
import com.mrit.mesh.core.MeshPacket
import com.mrit.mesh.core.PacketType
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * FileTransferManager — splits files into 32KB encrypted chunks and reassembles them.
 *
 * Send flow:
 *   1. File is split into [CHUNK_SIZE]-byte chunks
 *   2. Each chunk is wrapped in a chunk payload (see format below)
 *   3. MeshNode encrypts and sends each as a FILE_CHUNK packet
 *
 * Receive flow:
 *   1. FILE_CHUNK packets arrive, are decrypted by MeshNode, passed here
 *   2. Chunks are stored by (transferId, chunkIndex)
 *   3. When all chunks arrive (in any order), the file is reassembled and delivered
 *
 * Chunk payload format (big-endian, binary compatible with iOS FileTransferManager):
 *   [ 16 bytes : transfer ID    ]  random bytes, identifies this file transfer
 *   [  4 bytes : chunk index    ]  0-based, UInt32 big-endian
 *   [  4 bytes : total chunks   ]  UInt32 big-endian
 *   [  4 bytes : total file size]  bytes, UInt32 big-endian
 *   [  2 bytes : filename length]  UInt16 big-endian, 0 for non-first chunks
 *   [  N bytes : filename       ]  UTF-8, only present in chunk 0
 *   [  M bytes : chunk data     ]
 */
class FileTransferManager(
    private val ourId: MeshID,
    private val sendPacket: (MeshPacket, MeshID) -> Unit,
    private val onFileReceived: (fileName: String, data: ByteArray) -> Unit
) {

    companion object {
        private const val TAG        = "FileTransferManager"
        private const val CHUNK_SIZE = 32 * 1024   // 32 KB — well within 65,535-byte packet limit
        private const val HEADER_MIN = 30           // 16+4+4+4+2 bytes before filename
    }

    /** In-progress reassembly: transferId hex → session */
    private val sessions = HashMap<String, ReassemblySession>()
    private val lock     = Any()

    // ── Send ───────────────────────────────────────────────────────────────────

    /**
     * Split [data] into chunks and dispatch as FILE_CHUNK packets to [dest].
     *
     * @param dest     Destination MeshID
     * @param fileName Original filename (e.g. "photo.jpg")
     * @param data     Raw file bytes
     */
    fun send(dest: MeshID, fileName: String, data: ByteArray) {
        val transferId  = java.util.UUID.randomUUID().let {
            ByteBuffer.allocate(16).apply {
                putLong(it.mostSignificantBits)
                putLong(it.leastSignificantBits)
            }.array()
        }
        val totalChunks = (data.size + CHUNK_SIZE - 1) / CHUNK_SIZE

        Log.d(TAG, "Sending '$fileName' (${data.size} bytes) as $totalChunks chunk(s) to ${dest.shortId()}")

        for (i in 0 until totalChunks) {
            val start     = i * CHUNK_SIZE
            val end       = minOf(start + CHUNK_SIZE, data.size)
            val chunkData = data.copyOfRange(start, end)
            val name      = if (i == 0) fileName else ""

            val payload = buildPayload(
                transferId  = transferId,
                chunkIndex  = i,
                totalChunks = totalChunks,
                totalSize   = data.size,
                fileName    = name,
                chunkData   = chunkData
            )

            val packet = MeshPacket(
                type    = PacketType.FILE_CHUNK,
                srcId   = ourId,
                dstId   = dest,
                ttl     = MeshPacket.DEFAULT_TTL,
                payload = payload
            )
            sendPacket(packet, dest)
        }
    }

    // ── Receive ────────────────────────────────────────────────────────────────

    /**
     * Called by MeshNode when a FILE_CHUNK packet arrives and has been decrypted.
     *
     * @param srcId   Sender's MeshID
     * @param payload Decrypted chunk payload bytes
     */
    fun onChunkReceived(srcId: MeshID, payload: ByteArray) {
        val header = parseHeader(payload) ?: run {
            Log.w(TAG, "Malformed FILE_CHUNK payload from ${srcId.shortId()} (${payload.size} bytes)")
            return
        }
        val key = header.transferId.joinToString("") { "%02x".format(it) }

        synchronized(lock) {
            val session = sessions.getOrPut(key) {
                ReassemblySession(
                    transferId  = header.transferId,
                    totalChunks = header.totalChunks,
                    totalSize   = header.totalSize
                )
            }
            if (header.fileName.isNotEmpty()) session.fileName = header.fileName
            session.chunks[header.chunkIndex] = header.chunkData

            Log.d(TAG, "Chunk ${header.chunkIndex+1}/${header.totalChunks} received from ${srcId.shortId()}")

            if (session.chunks.size == session.totalChunks) {
                sessions.remove(key)
                val assembled = session.assemble()
                if (assembled != null) {
                    Log.d(TAG, "File '${session.fileName}' assembled (${assembled.size} bytes)")
                    onFileReceived(session.fileName, assembled)
                }
            }
        }
    }

    // ── Payload codec ──────────────────────────────────────────────────────────

    private fun buildPayload(
        transferId:  ByteArray,
        chunkIndex:  Int,
        totalChunks: Int,
        totalSize:   Int,
        fileName:    String,
        chunkData:   ByteArray
    ): ByteArray {
        val nameBytes = fileName.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(HEADER_MIN + nameBytes.size + chunkData.size)
            .order(ByteOrder.BIG_ENDIAN)
        buf.put(transferId)                      // 16 bytes
        buf.putInt(chunkIndex)                   //  4 bytes
        buf.putInt(totalChunks)                  //  4 bytes
        buf.putInt(totalSize)                    //  4 bytes
        buf.putShort(nameBytes.size.toShort())   //  2 bytes
        buf.put(nameBytes)                       //  N bytes
        buf.put(chunkData)                       //  M bytes
        return buf.array()
    }

    private fun parseHeader(data: ByteArray): ChunkHeader? {
        if (data.size < HEADER_MIN) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

        val transferId  = ByteArray(16).also { buf.get(it) }
        val chunkIndex  = buf.int
        val totalChunks = buf.int
        val totalSize   = buf.int
        val nameLen     = buf.short.toInt() and 0xFFFF

        if (data.size < HEADER_MIN + nameLen) return null

        val fileName  = if (nameLen > 0) {
            ByteArray(nameLen).also { buf.get(it) }.toString(Charsets.UTF_8)
        } else {
            buf.position(buf.position() + nameLen)
            ""
        }
        val chunkData = ByteArray(buf.remaining()).also { buf.get(it) }

        return ChunkHeader(transferId, chunkIndex, totalChunks, totalSize, fileName, chunkData)
    }

    // ── Data models ────────────────────────────────────────────────────────────

    private data class ChunkHeader(
        val transferId:  ByteArray,
        val chunkIndex:  Int,
        val totalChunks: Int,
        val totalSize:   Int,
        val fileName:    String,
        val chunkData:   ByteArray
    )

    private class ReassemblySession(
        val transferId:  ByteArray,
        val totalChunks: Int,
        val totalSize:   Int
    ) {
        var fileName = ""
        val chunks   = HashMap<Int, ByteArray>()

        /** Combine all chunks in order into the original file bytes. */
        fun assemble(): ByteArray? {
            if (chunks.size != totalChunks) return null
            val result = ByteArray(totalSize)
            var pos = 0
            for (i in 0 until totalChunks) {
                val chunk = chunks[i] ?: return null
                chunk.copyInto(result, pos)
                pos += chunk.size
            }
            return result
        }
    }
}
