package com.caw.android.ui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Build
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream

/**
 * Crumb, the 3D mascot, as the hold-to-talk orb (docs/crumb-spec.md). A transparent WebView shows
 * `assets/crumb/index.html`; this view takes every touch itself (the WebView never sees one) and
 * reports press/release through [onHold].
 *
 * Pushing (spec 4 and 6): [update] only records the wanted state. A main-thread timer sends it as
 * `window.updateCrumb({...})` 30 times a second while active and 4 times a second while idle,
 * skipping a push that equals the last one sent or while the previous one has not returned.
 * Once idle, sent and unchanged for [SETTLE_MS] (long enough for a celebration and the settle
 * redraw), or as soon as the panel is off screen, the timer stops and the WebView is paused.
 * Any change resumes it with an immediate push.
 *
 * If the page cannot draw (no WebGL) the blue [OrbView] takes its place for the rest of the
 * process. If the renderer process dies (on a small phone the system kills it whenever memory is
 * short and the panel is hidden) the orb stands in and the page is loaded again the next time the
 * panel is on screen, up to [MAX_RELOADS] times a process; the orb stays on top until the new
 * page is ready, so the square is never empty.
 *
 * Main thread only. One instance per process, owned by [CrumbHost].
 */
@SuppressLint("ViewConstructor")
class CrumbView(context: Context) : FrameLayout(context) {

    /** true on press, false on release or cancel. */
    var onHold: ((Boolean) -> Unit)? = null

    /** True once the blue orb has replaced Crumb. */
    val isFallback: Boolean get() = orb != null

    private val handler = Handler(Looper.getMainLooper())
    private val tick = Runnable { onTick() }

    private var web: WebView? = null
    private var orb: OrbView? = null

    private var payload = CrumbPayload()
    private var lastSent: String? = null
    private var pending = false
    private var pendingSince = 0L
    /** Bumped on every page load, so a callback from an earlier page is ignored. */
    private var generation = 0
    private var ready = false
    private var windowShown = false
    private var parked = false
    private var scheduled = false
    private var fastCadence = false
    private var lastChangeAt = 0L
    /** Load the page again when the panel is next shown (renderer died). */
    private var reloadWanted = false
    private var reloads = 0
    private var loadStartedAt = 0L
    /**
     * Until this time `active` is sent as true even when idle. The page clears its canvas when it
     * is resized and, idle, draws again only when its state changes; so after Crumb moves to a
     * slot of another size (the dictation screen, the touchpad, the pill) he would stay blank.
     * A short wake makes the page draw him again.
     */
    private var wakeUntil = 0L

    init {
        isClickable = true
        isFocusable = true
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        web = makeWebView()
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) listenForDebugCrash()
    }

    /**
     * Debug builds only: `adb shell am broadcast -a com.caw.android.debug.CRASH_CRUMB` kills the
     * renderer the way a memory squeeze does, so the reload path can be tried on a phone.
     */
    private fun listenForDebugCrash() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                Log.w(TAG, "debug: crashing Crumb's renderer on request")
                web?.loadUrl("chrome://crash")
            }
        }
        val filter = IntentFilter(DEBUG_CRASH_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    fun update(
        active: Boolean,
        recording: Boolean,
        processing: Boolean,
        waiting: Boolean,
        level: Float,
        delivery: Int,
        gentle: Boolean,
    ) {
        val next = CrumbPayload(active, recording, processing, waiting, level, delivery, gentle)
        if (next == payload) return
        payload = next
        orb?.let { applyToOrb(it) }
        changed()
    }

    // ---- touch: the whole square is the hold button ----

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onHold?.invoke(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onHold?.invoke(false)
        }
        return true
    }

    // ---- on screen or not ----

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) wake()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        wake()
    }

    /** Draw him again (see [wakeUntil]). */
    private fun wake() {
        wakeUntil = SystemClock.uptimeMillis() + WAKE_MS
        changed()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        val shown = visibility == View.VISIBLE
        if (shown == windowShown) return
        windowShown = shown
        if (shown) maybeReload()
        changed()
    }

    private fun maybeReload() {
        if (!reloadWanted || web != null) return
        reloadWanted = false
        reloads++
        Log.i(TAG, "loading Crumb again (reload $reloads of $MAX_RELOADS)")
        web = makeWebView()
    }

    // ---- pushing ----

    private fun changed() {
        lastChangeAt = SystemClock.uptimeMillis()
        val w = web ?: return
        if (parked) {
            parked = false
            w.onResume()
        }
        // Already ticking at 30/s: the next tick picks it up. Otherwise push now.
        if (!(scheduled && fastCadence)) schedule(0L, fastCadence)
    }

    private fun schedule(delayMs: Long, fast: Boolean) {
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, delayMs)
        scheduled = true
        fastCadence = fast
    }

    private fun onTick() {
        scheduled = false
        val w = web ?: return
        if (!ready) return // onPageFinished pushes
        val now = SystemClock.uptimeMillis()
        if (pending && now - pendingSince > PENDING_GIVE_UP_MS) pending = false
        val sentActive = (payload.active || now < wakeUntil) && windowShown
        val json = payload.copy(active = sentActive).toJson()
        if (json != lastSent && !pending) push(w, json, now)
        val settled = json == lastSent && !pending
        when {
            sentActive -> schedule(ACTIVE_MS, fast = true)
            settled && (!windowShown || now - lastChangeAt >= SETTLE_MS) -> park(w)
            else -> schedule(IDLE_MS, fast = false)
        }
    }

    private fun push(w: WebView, json: String, now: Long) {
        pending = true
        pendingSince = now
        val gen = generation
        w.evaluateJavascript("window.updateCrumb($json)") {
            if (web === w && gen == generation) {
                pending = false
                lastSent = json
            }
        }
    }

    private fun park(w: WebView) {
        handler.removeCallbacks(tick)
        scheduled = false
        if (!parked) {
            parked = true
            w.onPause()
        }
    }

    // ---- the page ----

    @SuppressLint("SetJavaScriptEnabled")
    private fun makeWebView(): WebView {
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        val w = WebView(context)
        w.setBackgroundColor(Color.TRANSPARENT)
        w.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        w.overScrollMode = View.OVER_SCROLL_NEVER
        w.isFocusable = false
        w.isFocusableInTouchMode = false
        w.isClickable = false
        w.isLongClickable = false
        w.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url) ?: blocked()

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !isOurs(request.url)

            override fun onPageFinished(view: WebView, url: String?) {
                if (view !== web) return
                generation++
                ready = true
                pending = false
                lastSent = null
                Log.i(TAG, "Crumb page loaded in ${SystemClock.uptimeMillis() - loadStartedAt} ms")
                // No updateCrumb means setup threw (no WebGL); the page shows its #error div.
                view.evaluateJavascript("typeof window.updateCrumb === 'function'") { ok ->
                    if (view !== web) return@evaluateJavascript
                    if (ok != "true") {
                        dropWeb("the page did not start (no WebGL?)", retry = false)
                    } else {
                        // A stand-in orb from an earlier renderer death: Crumb is back, let it go.
                        orb?.let { removeView(it) }
                        orb = null
                    }
                }
                changed()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (view === web) dropWeb("renderer gone (crashed=${detail.didCrash()})", retry = true)
                return true
            }
        }
        // Under the stand-in orb, if there is one, until the page is ready.
        addView(w, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        loadStartedAt = SystemClock.uptimeMillis()
        w.loadUrl(PAGE_URL)
        return w
    }

    /**
     * Lets go of the WebView and puts the blue orb in its place. With [retry], the page is loaded
     * again the next time the panel is shown (or now, if it is on screen) while reloads remain.
     */
    private fun dropWeb(why: String, retry: Boolean) {
        val w = web ?: return
        web = null
        ready = false
        handler.removeCallbacks(tick)
        scheduled = false
        parked = false
        removeView(w)
        w.destroy()
        if (orb == null) {
            val o = OrbView(context).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            orb = o
            addView(o, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            applyToOrb(o)
        }
        reloadWanted = retry && reloads < MAX_RELOADS
        if (reloadWanted) {
            Log.w(TAG, "Crumb gone, plain orb until the panel is next shown: $why")
            if (windowShown) handler.postDelayed({ maybeReload() }, RELOAD_DELAY_MS)
        } else {
            Log.w(TAG, "Crumb unavailable, using the plain orb for good: $why")
        }
    }

    private fun applyToOrb(o: OrbView) {
        o.level = if (payload.recording) payload.level else 0f
        o.listening = payload.recording
        o.holdingText = payload.waiting
    }

    companion object {
        private const val TAG = "CawCrumb"
        private const val HOST = "appassets.androidplatform.net"
        private const val PAGE_URL = "https://$HOST/assets/crumb/index.html"
        private const val ACTIVE_MS = 33L // 30 per second
        private const val IDLE_MS = 250L // 4 per second
        /** Idle and unchanged this long before pausing: covers the 1.5 s celebration and settle. */
        private const val SETTLE_MS = 2_000L
        /** How long [wake] keeps the page drawing: a few frames at 30 per second. */
        private const val WAKE_MS = 300L
        /** A push that never answers stops blocking the next one after this long. */
        private const val PENDING_GIVE_UP_MS = 1_000L
        /** Page loads after a renderer death, per process, before the orb stays for good. */
        private const val MAX_RELOADS = 8
        /** Wait before loading again while on screen, so a memory squeeze can pass. */
        private const val RELOAD_DELAY_MS = 1_500L
        private const val DEBUG_CRASH_ACTION = "com.caw.android.debug.CRASH_CRUMB"

        private fun isOurs(url: Uri): Boolean = url.scheme == "https" && url.host == HOST

        private fun blocked() = WebResourceResponse(
            "text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)),
        )
    }
}
