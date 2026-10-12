package com.caw.android.speech

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Debug
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DEBUG BUILDS ONLY. A speed benchmark for comparing recognizers on the phone; it never touches the
 * hold flow or the app's own loaded model.
 *
 *     adb shell am broadcast -a com.caw.android.debug.BENCH --es engine baseline|whisper-base|moonshine
 *
 * "baseline" is the recognizer SpeechEngine would pick from the APK (Moonshine when bundled). Other
 * models are pushed by hand (never in the APK) to filesDir/bench/<model>/, recordings to
 * filesDir/bench/wavs/ (16 kHz mono PCM16). Results go to logcat, tag CawSpeech, lines "bench: ...".
 * Each run loads one model on the SpeechEngine worker, recognises every WAV twice, then releases it.
 *
 *     adb shell am broadcast -a com.caw.android.debug.BENCH --es engine replay --es wav <name>.wav
 *
 * queues filesDir/bench/wavs/<name>.wav to stand in for the mic on the NEXT hold, so a recorded hold
 * goes through the whole real pipeline (AudioFeed stats, the app's recognizer, HoldSession, the pill).
 */
object DebugBench {

    const val ACTION = "com.caw.android.debug.BENCH"
    private const val TAG = "CawSpeech"
    private const val SAMPLE_RATE = 16_000

    @Volatile private var registered = false

    /** Registers the receiver once per process, and only in a debuggable build. */
    fun registerIfDebug(context: Context) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        synchronized(this) {
            if (registered) return
            registered = true
        }
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val engine = intent.getStringExtra("engine") ?: "baseline"
                Log.i(TAG, "bench: request $engine")
                if (engine == "replay") {
                    queueReplay(app, intent.getStringExtra("wav") ?: "")
                } else {
                    SpeechEngine.onWorker { run(app, engine) }
                }
            }
        }
        val filter = IntentFilter(ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
        }
    }

    @Volatile private var pendingReplay: FloatArray? = null

    /** The WAV queued by "replay" for the next hold, once; null otherwise (always in release builds). */
    fun takeReplay(): FloatArray? = synchronized(this) {
        pendingReplay.also { pendingReplay = null }
    }

    private fun queueReplay(context: Context, name: String) {
        try {
            val samples = readWav(File(File(File(context.filesDir, "bench"), "wavs"), name))
            synchronized(this) { pendingReplay = samples }
            Log.i(TAG, "bench: replay $name queued for the next hold (${"%.2f".format(samples.size / SAMPLE_RATE.toDouble())} s)")
        } catch (t: Throwable) {
            Log.e(TAG, "bench: replay $name failed", t)
        }
    }

    private interface Engine {
        fun recognize(samples: FloatArray): String
        fun release()
    }

    private fun run(context: Context, name: String) {
        var engine: Engine? = null
        try {
            val bench = File(context.filesDir, "bench")
            val t0 = System.nanoTime()
            engine = when (name) {
                "baseline" -> baseline(context)
                "whisper-base" -> whisper(File(bench, "whisper-base"))
                "moonshine" -> moonshine(File(bench, "moonshine"))
                else -> throw IllegalArgumentException("unknown engine $name")
            }
            Log.i(TAG, "bench: $name loaded in ${ms(t0)} ms, pss ${Debug.getPss() / 1024} MB")
            val wavs = File(bench, "wavs").listFiles { _, n -> n.endsWith(".wav") }?.sortedBy { it.name }.orEmpty()
            if (wavs.isEmpty()) Log.w(TAG, "bench: no WAVs in ${File(bench, "wavs")}")
            for (wav in wavs) {
                val samples = readWav(wav)
                for (round in 1..2) {
                    val t = System.nanoTime()
                    val text = engine.recognize(samples)
                    Log.i(TAG, "bench: $name ${wav.name} ${ms(t)} ms: \"$text\" (run $round, ${"%.2f".format(samples.size / SAMPLE_RATE.toDouble())} s audio)")
                }
            }
            Log.i(TAG, "bench: $name peak-ish pss ${Debug.getPss() / 1024} MB before release")
        } catch (t: Throwable) {
            Log.e(TAG, "bench: $name failed", t)
        } finally {
            try {
                engine?.release()
            } catch (t: Throwable) {
                Log.e(TAG, "bench: $name release failed", t)
            }
            System.gc()
            Log.i(TAG, "bench: $name done, pss ${Debug.getPss() / 1024} MB")
        }
    }

    private fun ms(since: Long) = (System.nanoTime() - since) / 1_000_000

    /** The recognizer SpeechEngine would load: Moonshine when its files are in the APK. */
    private fun baseline(context: Context): Engine {
        val moonshine = MoonshineRecognizer.isBundled(context.assets)
        Log.i(TAG, "bench: baseline is ${if (moonshine) "moonshine-base-en" else "sherpa-streaming-en"}")
        val r: Recognizer = if (moonshine) MoonshineRecognizer(context.assets) else SherpaStreamingRecognizer(context.assets)
        return object : Engine {
            override fun recognize(samples: FloatArray): String {
                r.start()
                var i = 0
                while (i < samples.size) {
                    val end = minOf(i + 1_600, samples.size)
                    r.feed(samples.copyOfRange(i, end))
                    i = end
                }
                return r.finish()
            }

            override fun release() = r.close()
        }
    }

    private fun need(f: File): String {
        // A missing file aborts the whole process in native code, so check first.
        if (!f.isFile) throw IllegalStateException("missing ${f.absolutePath}")
        return f.absolutePath
    }

    private fun whisper(dir: File): Engine = offline(
        OfflineModelConfig(
            whisper = OfflineWhisperModelConfig(
                encoder = need(File(dir, "base.en-encoder.int8.onnx")),
                decoder = need(File(dir, "base.en-decoder.int8.onnx")),
                language = "en",
                task = "transcribe",
            ),
            tokens = need(File(dir, "base.en-tokens.txt")),
            numThreads = 2,
        ),
    )

    private fun moonshine(dir: File): Engine = offline(
        OfflineModelConfig(
            moonshine = OfflineMoonshineModelConfig(
                preprocessor = need(File(dir, "preprocess.onnx")),
                encoder = need(File(dir, "encode.int8.onnx")),
                uncachedDecoder = need(File(dir, "uncached_decode.int8.onnx")),
                cachedDecoder = need(File(dir, "cached_decode.int8.onnx")),
            ),
            tokens = need(File(dir, "tokens.txt")),
            numThreads = 2,
        ),
    )

    private fun offline(model: OfflineModelConfig): Engine {
        val r = OfflineRecognizer(
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = model,
            ),
        )
        return object : Engine {
            override fun recognize(samples: FloatArray): String {
                val s = r.createStream()
                try {
                    s.acceptWaveform(samples, SAMPLE_RATE)
                    r.decode(s)
                    return r.getResult(s).text
                } finally {
                    s.release()
                }
            }

            override fun release() = r.release()
        }
    }

    /** 16-bit PCM WAV to floats in -1..1; finds the "data" chunk rather than assuming 44 bytes. */
    private fun readWav(f: File): FloatArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= b.limit()) {
            val id = String(ByteArray(4) { b.get(pos + it) }, Charsets.US_ASCII)
            val size = b.getInt(pos + 4)
            if (id == "data") {
                val n = minOf(size, b.limit() - pos - 8) / 2
                return FloatArray(n) { b.getShort(pos + 8 + it * 2) / 32768f }
            }
            pos += 8 + size + (size and 1)
        }
        throw IllegalStateException("no data chunk in ${f.name}")
    }
}
