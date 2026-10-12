package com.caw.android.speech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class HoldDumpTest {

    private fun tempDir(): File = Files.createTempDirectory("holds").toFile()

    @Test
    fun writesAValidWavOfTheSamples() {
        val dir = tempDir()
        val dump = HoldDump(dir)
        dump.add(floatArrayOf(0f, 0.5f, -0.5f))
        dump.add(floatArrayOf(1f, -1f))
        val file = dump.save("hold-1")
        val bytes = file.readBytes()
        assertEquals(44 + 10, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("WAVE", String(bytes, 8, 4))
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16_000, b.getInt(24))
        assertEquals(10, b.getInt(40))
        val samples = ShortArray(5) { b.getShort(44 + it * 2) }
        assertArrayEquals(shortArrayOf(0, 16383, -16383, 32767, -32767), samples)
    }

    @Test
    fun keepsOnlyTheNewestFive() {
        val dir = tempDir()
        for (i in 1..7) HoldDump(dir).apply { add(floatArrayOf(0f)) }.save("hold-%02d".format(i))
        val names = dir.listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf("hold-03.wav", "hold-04.wav", "hold-05.wav", "hold-06.wav", "hold-07.wav"), names)
    }
}
