package com.caw.android.speech

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.IOException
import java.util.Locale
import kotlin.math.sqrt

/**
 * Moonshine base (English, int8) through sherpa-onnx's offline recognizer, loaded from the APK's
 * assets. Moonshine is not streaming, so the hold's audio is buffered and cut at pauses by
 * [PauseSegmenter]; each finished segment is decoded on the speech worker while the hold goes on,
 * and its text is banked. [partial] is the banked text, so HoldSession's banking and its 6 s
 * finalise cap work as with the streaming model. [finish] decodes what is left.
 *
 * Moonshine writes mixed case with punctuation; its text is NOT passed through [RecognizerText.tidy].
 */
class MoonshineRecognizer(assets: AssetManager) : Recognizer {

    companion object {
        /** Folder inside app/src/main/assets. */
        const val MODEL_DIR = "models/moonshine-base-en"
        const val PREPROCESSOR = "preprocess.onnx"
        const val ENCODER = "encode.int8.onnx"
        const val UNCACHED_DECODER = "uncached_decode.int8.onnx"
        const val CACHED_DECODER = "cached_decode.int8.onnx"
        const val TOKENS = "tokens.txt"

        private const val SAMPLE_RATE = 16_000
        private const val CHUNK = PauseSegmenter.CHUNK_SAMPLES
        private const val TAG = "CawSpeech"

        /** True if every Moonshine file is in the APK's assets. */
        fun isBundled(assets: AssetManager): Boolean =
            listOf(PREPROCESSOR, ENCODER, UNCACHED_DECODER, CACHED_DECODER, TOKENS).all { name ->
                try {
                    assets.open("$MODEL_DIR/$name").close()
                    true
                } catch (e: IOException) {
                    false
                }
            }
    }

    private val engine: OfflineRecognizer
    private val segmenter = PauseSegmenter()

    /** The current segment's 100 ms chunks, parallel to the segmenter's levels. */
    private val chunks = ArrayDeque<FloatArray>()
    private val pending = FloatArray(CHUNK)
    private var pendingSize = 0
    private val texts = mutableListOf<String>()
    private var segmentCount = 0

    init {
        // A missing model file makes the native code abort the whole process, so check first.
        for (name in listOf(PREPROCESSOR, ENCODER, UNCACHED_DECODER, CACHED_DECODER, TOKENS)) {
            try {
                assets.open("$MODEL_DIR/$name").close()
            } catch (e: IOException) {
                throw IllegalStateException("the speech model file $MODEL_DIR/$name is missing")
            }
        }
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    preprocessor = "$MODEL_DIR/$PREPROCESSOR",
                    encoder = "$MODEL_DIR/$ENCODER",
                    uncachedDecoder = "$MODEL_DIR/$UNCACHED_DECODER",
                    cachedDecoder = "$MODEL_DIR/$CACHED_DECODER",
                ),
                tokens = "$MODEL_DIR/$TOKENS",
                numThreads = 2,
            ),
        )
        engine = OfflineRecognizer(assetManager = assets, config = config)
    }

    override fun start() {
        segmenter.reset()
        chunks.clear()
        pendingSize = 0
        texts.clear()
        segmentCount = 0
    }

    override fun feed(samples: FloatArray) {
        var i = 0
        while (i < samples.size) {
            val n = minOf(CHUNK - pendingSize, samples.size - i)
            System.arraycopy(samples, i, pending, pendingSize, n)
            pendingSize += n
            i += n
            if (pendingSize == CHUNK) {
                addChunk(pending.copyOf())
                pendingSize = 0
            }
        }
    }

    override fun partial(): String = RecognizerText.joinSentences(texts)

    override fun finish(): String {
        if (pendingSize > 0) {
            // The last part-chunk joins the segment; it is too short to start or end one by itself.
            chunks.addLast(pending.copyOf(pendingSize))
            pendingSize = 0
        }
        val leftover = chunks.sumOf { it.size }
        if (leftover > 0 && hasSound()) decode(chunks.size)
        chunks.clear()
        segmenter.reset()
        Log.i(TAG, "raw: \"${RecognizerText.join(texts)}\" ($segmentCount segment(s))")
        return partial()
    }

    override fun close() {
        chunks.clear()
        engine.release()
    }

    private fun addChunk(chunk: FloatArray) {
        chunks.addLast(chunk)
        val step = segmenter.push(rms(chunk))
        if (step.isNothing) return
        if (step.emit > 0) decode(step.emit)
        repeat(step.drop - step.emit) { chunks.removeFirst() }
    }

    /** Chunks in this segment, including a final part-chunk, that reach the silence floor. */
    private fun hasSound(): Boolean = segmenter.hasSound || chunks.any { rms(it) >= PauseSegmenter.FLOOR }

    /** Decodes the first [count] chunks as one segment, removes them, and banks the text. */
    private fun decode(count: Int) {
        var total = 0
        for (k in 0 until count) total += chunks.elementAt(k).size
        val audio = FloatArray(total)
        var at = 0
        repeat(count) {
            val c = chunks.removeFirst()
            System.arraycopy(c, 0, audio, at, c.size)
            at += c.size
        }
        segmentCount++
        val started = System.nanoTime()
        val stream = engine.createStream()
        val text = try {
            stream.acceptWaveform(audio, SAMPLE_RATE)
            engine.decode(stream)
            engine.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        Log.i(TAG, "segment $segmentCount decoded in $ms ms: \"$text\" (${String.format(Locale.US, "%.1f", total / SAMPLE_RATE.toDouble())} s audio)")
        if (text.isNotEmpty()) texts += text
    }

    private fun rms(chunk: FloatArray): Float {
        if (chunk.isEmpty()) return 0f
        var ss = 0.0
        for (x in chunk) ss += (x * x).toDouble()
        return sqrt(ss / chunk.size).toFloat()
    }
}
