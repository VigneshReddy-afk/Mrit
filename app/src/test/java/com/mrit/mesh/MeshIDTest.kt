package com.mrit.mesh

import com.mrit.mesh.core.MeshID
import org.junit.Assert.*
import org.junit.Test

class MeshIDTest {

    @Test
    fun `generated MeshID is exactly 32 bytes`() {
        val id = MeshID.generate()
        assertEquals(32, id.bytes.size)
    }

    @Test
    fun `two generated MeshIDs are unique`() {
        val id1 = MeshID.generate()
        val id2 = MeshID.generate()
        assertNotEquals(id1, id2)
    }

    @Test
    fun `toString produces 64-char hex string`() {
        val id = MeshID.generate()
        assertEquals(64, id.toString().length)
        assertTrue(id.toString().all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `fromHex round-trips correctly`() {
        val original = MeshID.generate()
        val restored = MeshID.fromHex(original.toString())
        assertEquals(original, restored)
    }

    @Test
    fun `BROADCAST address is all 0xFF bytes`() {
        assertTrue(MeshID.BROADCAST.bytes.all { it == 0xFF.toByte() })
    }

    @Test
    fun `shortId returns first 8 uppercase hex chars`() {
        val id = MeshID.generate()
        val short = id.shortId()
        assertEquals(8, short.length)
        assertEquals(id.toString().take(8).uppercase(), short)
    }
}
