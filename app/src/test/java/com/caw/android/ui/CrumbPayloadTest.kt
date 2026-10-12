package com.caw.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CrumbPayloadTest {

    @Test
    fun matchesTheMacStringForm() {
        val p = CrumbPayload(
            active = true, recording = true, processing = false, waiting = false,
            level = 0.42f, delivery = 3, gentle = false,
        )
        assertEquals(
            "window.updateCrumb({\"active\":true,\"delivery\":3,\"gentle\":false,\"level\":0.42," +
                "\"processing\":false,\"recording\":true,\"waiting\":false})",
            p.toScript(),
        )
    }

    @Test
    fun levelIsAPlainJsonNumber() {
        fun lvl(f: Float) = Regex("\"level\":([^,]*),").find(CrumbPayload(level = f).toJson())!!.groupValues[1]
        assertEquals("0", lvl(0f))
        assertEquals("0", lvl(0.001f))
        assertEquals("0.05", lvl(0.05f))
        assertEquals("0.5", lvl(0.5f))
        assertEquals("1", lvl(1f))
        assertEquals("1", lvl(3f))
        assertEquals("0", lvl(-1f))
    }

    @Test
    fun tinyLevelChangesAreNotAChange() {
        assertEquals(CrumbPayload(level = 0.421f), CrumbPayload(level = 0.419f))
        assertNotEquals(CrumbPayload(level = 0.42f), CrumbPayload(level = 0.45f))
    }
}
