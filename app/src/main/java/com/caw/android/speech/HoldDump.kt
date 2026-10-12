package com.caw.android.speech

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Debug builds only: keeps a copy of each hold's audio as a 16 kHz mono WAV in [dir], newest
 * [KEEP] holds, so a miss can be pulled off the phone (`adb shell run-as com.caw.android cat
 * files/holds/<name>`) and listened to or run through the model on the Mac. Pure Kotlin.
 *
 * [add] runs on the mic thread; [save] on any other thread after the mic has closed.
 */
class HoldDump(private val dir: File) {

    companion object {
        const val KEEP = 5
        private const val SAMPLE_RATE = AudioFeed.SAMPLE_RATE
    }

    private val pcm = ByteArrayOutputStream()

    /** Mic thread. Appends one chunk of float samples as 16-bit PCM. */
    @Synchronized
    fun add(chunk: FloatArray) {
        val buf = ByteBuffer.allocate(chunk.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (x in chunk) {
            val v = (x.coerceIn(-1f, 1f) * 32767f).toInt()
            buf.putShort(v.toShort())
        }
        pcm.write(buf.array())
    }

    /** Writes `<name>.wav`, drops the oldest files past [KEEP], and returns the file written. */
    @Synchronized
    fun save(name: String): File {
        dir.mkdirs()
        val data = pcm.toByteArray()
        val file = File(dir, "$name.wav")
        RandomAccessFile(file, "rw").use { f ->
            f.setLength(0)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray())
            header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                .putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
            header.put("data".toByteArray()).putInt(data.size)
            f.write(header.array())
            f.write(data)
        }
        dir.listFiles { _, n -> n.endsWith(".wav") }
            ?.sortedBy { it.name }
            ?.dropLast(KEEP)
            ?.forEach { it.delete() }
        return file
    }
}
