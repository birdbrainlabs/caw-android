package com.caw.android.speech

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.IOException

/**
 * sherpa-onnx streaming zipformer transducer, loaded from the APK's assets.
 * Model: sherpa-onnx-streaming-zipformer-en-2023-06-26 (int8), put in place by scripts/fetch-model.sh.
 *
 * When sherpa-onnx detects an endpoint (a long pause) it resets its stream; the text before the pause
 * is banked here, so [partial] always returns everything heard since [start].
 */
class SherpaStreamingRecognizer(assets: AssetManager) : Recognizer {

    companion object {
        /** Folder inside app/src/main/assets. */
        const val MODEL_DIR = "models/sherpa-streaming-en"
        const val ENCODER = "encoder.int8.onnx"
        const val DECODER = "decoder.int8.onnx"
        const val JOINER = "joiner.int8.onnx"
        const val TOKENS = "tokens.txt"

        private const val SAMPLE_RATE = 16_000
        private const val TAG = "CawSpeech"
    }

    private val engine: OnlineRecognizer
    private var stream: OnlineStream? = null
    private val segments = mutableListOf<String>()
    private var current = ""

    init {
        // A missing model file makes the native code abort the whole process, so check first.
        for (name in listOf(ENCODER, DECODER, JOINER, TOKENS)) {
            try {
                assets.open("$MODEL_DIR/$name").close()
            } catch (e: IOException) {
                throw IllegalStateException("the speech model file $MODEL_DIR/$name is missing")
            }
        }
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$MODEL_DIR/$ENCODER",
                    decoder = "$MODEL_DIR/$DECODER",
                    joiner = "$MODEL_DIR/$JOINER",
                ),
                tokens = "$MODEL_DIR/$TOKENS",
                numThreads = 2,
                modelType = "zipformer2",
            ),
            // Same rules as the sherpa-onnx Android demo. An endpoint only splits the text into
            // segments; it never ends the recording (the hold does that).
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0.0f),
                rule2 = EndpointRule(true, 1.4f, 0.0f),
                // The demo's 20 s cap split long speech mid-word; use the 300 s max hold instead.
                rule3 = EndpointRule(false, 0.0f, 300.0f),
            ),
            enableEndpoint = true,
        )
        engine = OnlineRecognizer(assetManager = assets, config = config)
    }

    override fun start() {
        stream?.release()
        stream = engine.createStream()
        segments.clear()
        current = ""
    }

    override fun feed(samples: FloatArray) {
        val s = stream ?: return
        s.acceptWaveform(samples, SAMPLE_RATE)
        decodeReady(s)
        // Checked only after a whole chunk is in and decoded. An endpoint needs 1.4 s (rule2) or
        // 2.4 s (rule1) of trailing silence, or 300 s of utterance (rule3), so it never falls inside
        // a word; reset keeps any frames not yet decoded, it only clears the text and model state.
        if (engine.isEndpoint(s)) {
            bank()
            engine.reset(s)
        }
    }

    override fun partial(): String = RecognizerText.tidy(RecognizerText.join(segments + current))

    override fun finish(): String {
        val s = stream ?: return partial()
        // A little silence so the last word clears the model's look-ahead.
        s.acceptWaveform(FloatArray(SAMPLE_RATE * 3 / 10), SAMPLE_RATE)
        s.inputFinished()
        decodeReady(s)
        bank()
        s.release()
        stream = null
        // The model's own text (ALL CAPS, before Caw touches it), quoted so spaces show.
        Log.i(TAG, "raw: \"${RecognizerText.join(segments)}\" (${segments.size} segment(s))")
        return partial()
    }

    override fun close() {
        stream?.release()
        stream = null
        engine.release()
    }

    private fun decodeReady(s: OnlineStream) {
        while (engine.isReady(s)) engine.decode(s)
        current = engine.getResult(s).text
    }

    private fun bank() {
        val t = current.trim()
        if (t.isNotEmpty()) segments += t
        current = ""
    }
}
