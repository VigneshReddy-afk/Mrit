package com.mrit.mesh.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.mrit.mesh.core.MeshID
import com.mrit.mesh.core.MeshPacket
import com.mrit.mesh.protocol.MMPDecoder
import com.mrit.mesh.protocol.MMPEncoder

/**
 * PacketStore — SQLite-backed store-and-forward queue.
 *
 * When a packet can't be delivered immediately (route unknown),
 * it is persisted here. When the destination node comes into range
 * later, stored packets are retrieved and forwarded.
 *
 * Packets older than [MAX_AGE_MS] (24 hours) are automatically purged.
 *
 * Schema:
 *   pending_packets
 *     id          INTEGER PK
 *     dst_id      TEXT     — hex MeshID of the intended recipient
 *     packet_data BLOB     — MMP-encoded packet bytes
 *     stored_at   INTEGER  — Unix millis when packet was queued
 */
class PacketStore(context: Context) : SQLiteOpenHelper(
    context, DB_NAME, null, DB_VERSION
) {

    companion object {
        private const val TAG        = "PacketStore"
        private const val DB_NAME    = "mrit_packets.db"
        private const val DB_VERSION = 1
        private const val TABLE      = "pending_packets"
        private const val MAX_AGE_MS = 24L * 60 * 60 * 1000  // 24 hours in milliseconds
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                dst_id      TEXT    NOT NULL,
                packet_data BLOB    NOT NULL,
                stored_at   INTEGER NOT NULL
            )
        """.trimIndent())

        // Index on dst_id — the most common query pattern
        db.execSQL("CREATE INDEX idx_dst ON $TABLE(dst_id)")
        Log.d(TAG, "PacketStore database created")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    // ── Write operations ───────────────────────────────────────────────────────

    /**
     * Persist a packet for later delivery.
     * Called when the router decides STORE_AND_DISCOVER.
     */
    fun store(packet: MeshPacket) {
        val values = ContentValues().apply {
            put("dst_id",      packet.dstId.toString())
            put("packet_data", MMPEncoder.encode(packet))
            put("stored_at",   System.currentTimeMillis())
        }
        writableDatabase.insert(TABLE, null, values)
        Log.d(TAG, "Stored packet for ${packet.dstId.shortId()} (type=${packet.type})")
    }

    /**
     * Delete all stored packets whose destination is [destination].
     * Called after successful delivery — no need to keep them.
     */
    fun clearDelivered(destination: MeshID) {
        val count = writableDatabase.delete(
            TABLE, "dst_id = ?", arrayOf(destination.toString())
        )
        Log.d(TAG, "Cleared $count delivered packets for ${destination.shortId()}")
    }

    /**
     * Delete all packets older than [MAX_AGE_MS].
     * Call this periodically (e.g., on app start and once daily).
     */
    fun purgeExpired() {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        val count  = writableDatabase.delete(
            TABLE, "stored_at < ?", arrayOf(cutoff.toString())
        )
        if (count > 0) Log.d(TAG, "Purged $count expired packets (older than 24h)")
    }

    // ── Read operations ────────────────────────────────────────────────────────

    /**
     * Retrieve all stored packets for a given [destination], oldest first.
     * Returns an empty list if none are stored.
     */
    fun getPendingFor(destination: MeshID): List<MeshPacket> {
        val packets = mutableListOf<MeshPacket>()

        readableDatabase.query(
            TABLE,
            arrayOf("packet_data"),
            "dst_id = ?",
            arrayOf(destination.toString()),
            null, null,
            "stored_at ASC"       // deliver in the order they were queued
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val blob   = cursor.getBlob(0)
                val packet = MMPDecoder.decode(blob)
                if (packet != null) {
                    packets.add(packet)
                } else {
                    Log.w(TAG, "Skipped corrupt stored packet for ${destination.shortId()}")
                }
            }
        }

        return packets
    }

    /** Total number of packets currently waiting for delivery */
    fun pendingCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** Number of pending packets for a specific destination */
    fun pendingCountFor(destination: MeshID): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLE WHERE dst_id = ?",
            arrayOf(destination.toString())
        ).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }
}
