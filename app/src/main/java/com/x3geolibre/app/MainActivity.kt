package com.x3geolibre.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Standalone full-screen ChatGPT browser for RayNeo X3 Pro.
 *
 * A single WebView pointed at gemini.google.com, mirrored to both lenses by
 * [BinocularSbsLayout], driven by a floating cursor over the right temple pad.
 *
 * ChatGPT-specific behaviour on top of the TapGarden shell:
 *  - Microphone + WebRTC: RECORD_AUDIO is requested up front and
 *    [WebChromeClient.onPermissionRequest] grants getUserMedia so voice chat works.
 *  - Login persistence: cookies are accepted (incl. third-party for the auth flow)
 *    and flushed to disk on pause/destroy; DOM storage persists in app data. A
 *    signed-in session therefore survives an app restart.
 *  - Voice-button auto-focus: after 10 s of no trackpad/key input the cursor parks
 *    itself on ChatGPT's Voice button (ready to tap).
 *  - Swipe to pick a voice: a deliberate left/right trackpad swipe drives ChatGPT's
 *    voice-selection carousel.
 */
class MainActivity : android.app.Activity(), CustomKeyboardView.OnKeyboardActionListener {
    private lateinit var webView: WebView
    private lateinit var viewport: FrameLayout
    private lateinit var keyboardContainer: FrameLayout
    private lateinit var binocular: BinocularSbsLayout
    private var keyboardView: CustomKeyboardView? = null
    private val imeSuppressor = Handler(Looper.getMainLooper())
    private var suppressImeUntilMs = 0L
    /** Cached desktop UA string for off-UI-thread stylesheet refetching. */
    @Volatile private var desktopUa: String = ""
    private val homeUrl = "https://web.geolibre.app/"
    private var pendingPermissionRequest: PermissionRequest? = null
    private var pendingPermissionResources: Array<String> = emptyArray()
    private val playbackWakeLock: PowerManager.WakeLock by lazy {
        (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "X3GeoLibre:VoiceAudio").apply {
                setReferenceCounted(false)
            }
    }
    private val playbackWifiLock: WifiManager.WifiLock? by lazy {
        (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.run {
            @Suppress("DEPRECATION")
            createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "X3GeoLibre:VoiceAudio").apply {
                setReferenceCounted(false)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        runCatching { com.ffalcon.mercury.android.sdk.MercurySDK.init(application) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        holdPlaybackResources()
        startPlaybackService()
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        )
        enableImmersiveFullscreen()

        if (!hasAudioPermission()) requestAudioPermission()

        // Persist cookies (login) across restarts. Third-party cookies are needed
        // for the OAuth/auth.openai.com hop during sign-in.
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)

        WebView.setWebContentsDebuggingEnabled(true)

        webView = WebView(this).apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scrollBarSize = (16f * resources.displayMetrics.density).toInt()
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = false
            configure(this)
        }
        cookies.setAcceptThirdPartyCookies(webView, true)

        // Install the compatibility shim so it runs BEFORE any page script on every
        // navigation — this is what makes the desktop bundle viable on Chrome 95.
        // (onPageStarted also evaluates it as a best-effort fallback for engines
        // without DOCUMENT_START_SCRIPT, but that path can lose the race.)
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(webView, docStartJs, setOf("*"))
                Log.d(TAG, "document-start compatibility shim installed")
            } else {
                Log.w(TAG, "DOCUMENT_START_SCRIPT unsupported; relying on onPageStarted fallback")
            }
        }.onFailure { Log.w(TAG, "addDocumentStartJavaScript failed: ${it.message}") }

        keyboardContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
            visibility = View.GONE
            elevation = 3000f
        }

        viewport = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            addView(webView)
            addView(keyboardContainer)
        }

        binocular = BinocularSbsLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            logicalClickHandler = { x, y -> handleLogicalClick(x, y) }
            edgePanHandler = { dx, dy -> scrollPage(dx, dy) }
            edgePanStopHandler = { /* wheel/scroll needs no release */ }
            contentInteractionBlocked = { keyboardContainer.visibility == View.VISIBLE }
            addView(viewport, 0)
            setWebViewTarget(webView)
        }
        setContentView(binocular)
        enableImmersiveFullscreen()

        webView.loadUrl(homeUrl)
    }

    private fun holdPlaybackResources() {
        runCatching {
            if (!playbackWakeLock.isHeld) playbackWakeLock.acquire()
        }.onFailure {
            Log.w(TAG, "Unable to acquire playback wake lock: ${it.message}")
        }
        runCatching {
            val lock = playbackWifiLock
            if (lock != null && !lock.isHeld) lock.acquire()
        }.onFailure {
            Log.w(TAG, "Unable to acquire playback Wi-Fi lock: ${it.message}")
        }
    }

    private fun releasePlaybackResources() {
        runCatching {
            if (playbackWifiLock?.isHeld == true) playbackWifiLock?.release()
        }
        runCatching {
            if (playbackWakeLock.isHeld) playbackWakeLock.release()
        }
    }

    private fun startPlaybackService() {
        val intent = Intent(this, GeoPlaybackService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                startService(intent)
            }
        }.onFailure {
            Log.w(TAG, "Unable to start playback service: ${it.message}")
        }
    }

    private fun stopPlaybackService() {
        runCatching {
            stopService(Intent(this, GeoPlaybackService::class.java))
        }.onFailure {
            Log.w(TAG, "Unable to stop playback service: ${it.message}")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(wv: WebView) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION") databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setOffscreenPreRaster(true)
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            // Desktop Chrome identity so ChatGPT serves its standard desktop web app
            // (sidebar + composer with the inline mic/Voice pill). The earlier
            // desktop failure ("unstyled DOM, dead buttons") had the same root cause
            // as the mobile "Content failed to load": Chrome 95 lacks post-2021
            // JS/CSS features ChatGPT's evergreen desktop bundle assumes. That is
            // now compensated by the document-start compatibility shim (docStartJs):
            // ES2022+ polyfills injected before any page script, plus a CSS @layer
            // unwrapper (Chrome 95 drops whole @layer blocks, which strips ALL
            // styling from Tailwind-v4-style sheets). Reuse the engine's real
            // Chrome version token so it matches the actual renderer.
            val chromeVer = Regex("Chrome/([0-9.]+)")
                .find(userAgentString)?.groupValues?.get(1) ?: "125.0.0.0"
            userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/$chromeVer Safari/537.36"
            desktopUa = userAgentString
        }
        runCatching {
            wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
        }
        disableSystemKeyboard(wv)
        wv.addJavascriptInterface(GeoBridge(), "GeoBridge")

        wv.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                // ChatGPT voice chat asks for the mic via getUserMedia.
                val audio = request.resources
                    .filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
                    .toTypedArray()
                if (audio.isEmpty()) {
                    request.deny()
                    return
                }
                if (hasAudioPermission()) {
                    request.grant(audio)
                } else {
                    pendingPermissionRequest = request
                    pendingPermissionResources = audio
                    requestAudioPermission()
                }
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                // Strip CSS cascade layers (@layer, Chrome 99+) out of stylesheets
                // BEFORE this Chrome-95 engine parses them. Chrome 95 drops entire
                // @layer blocks it can't parse, which strips ALL styling from
                // ChatGPT's layer-based sheets -> the "unstyled DOM" failure. Doing
                // it here (not via async JS refetch) is deterministic: the engine
                // never sees @layer, so there's no unstyled flash or race.
                val req = request ?: return null
                if (req.method != "GET") return null
                val url = req.url ?: return null
                val scheme = url.scheme?.lowercase()
                if (scheme != "https" && scheme != "http") return null
                val path = url.path?.lowercase().orEmpty()
                val accept = req.requestHeaders?.get("Accept").orEmpty()
                val isCss = path.endsWith(".css") || accept.contains("text/css", ignoreCase = true)
                if (!isCss) return null
                return runCatching { interceptCss(url.toString(), req.requestHeaders) }.getOrNull()
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Fallback for engines without DOCUMENT_START_SCRIPT (idempotent —
                // guarded by window.__tgptDocStart when the real hook already ran).
                runCatching { webView.evaluateJavascript(docStartJs, null) }
                injectEs2023Polyfills()
                injectDarkMode()
                injectInputSupport()
                injectDialogLayoutFix()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectEs2023Polyfills()
                injectDarkMode()
                injectInputSupport()
                injectGlassesHelpers()
                injectDialogLayoutFix()
                injectComposerVisibilityFix()
                CookieManager.getInstance().flush()
            }
        }
    }

    /**
     * Refetch a stylesheet, strip `@layer` wrappers, and hand it back so the engine
     * parses plain CSS. Runs on a WebView binder thread (blocking I/O is expected
     * here). Returns null on any failure so the WebView loads the sheet normally.
     */
    private fun interceptCss(url: String, reqHeaders: Map<String, String>?): WebResourceResponse? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "text/css,*/*;q=0.1")
            if (desktopUa.isNotEmpty()) setRequestProperty("User-Agent", desktopUa)
            reqHeaders?.get("Referer")?.let { setRequestProperty("Referer", it) }
                ?: setRequestProperty("Referer", "https://web.geolibre.app/")
            CookieManager.getInstance().getCookie(url)?.let { setRequestProperty("Cookie", it) }
        }
        try {
            if (conn.responseCode != 200) return null
            val gzip = conn.contentEncoding?.contains("gzip", true) == true
            val raw = (if (gzip) GZIPInputStream(conn.inputStream) else conn.inputStream)
                .use { it.readBytes() }
            val css = String(raw, Charsets.UTF_8)
            val out = if (css.contains("@layer")) stripCssLayers(css).toByteArray(Charsets.UTF_8) else raw
            return WebResourceResponse(
                "text/css",
                "utf-8",
                200,
                "OK",
                mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-cache"),
                ByteArrayInputStream(out)
            )
        } finally {
            conn.disconnect()
        }
    }

    /** Remove CSS `@layer` statements and unwrap `@layer name { ... }` blocks in place. */
    private fun stripCssLayers(cssIn: String): String {
        var css = cssIn.replace(Regex("@layer[^{};]*;"), "")
        var guard = 0
        while (css.contains("@layer") && guard++ < 2000) {
            val idx = css.indexOf("@layer")
            val braceStart = css.indexOf('{', idx)
            if (braceStart < 0) break
            var depth = 1
            var j = braceStart + 1
            while (j < css.length && depth > 0) {
                when (css[j]) {
                    '{' -> depth++
                    '}' -> depth--
                }
                j++
            }
            css = css.substring(0, idx) + css.substring(braceStart + 1, j - 1) + css.substring(j)
        }
        return css
    }

    private fun disableSystemKeyboard(wv: WebView) {
        runCatching {
            WebView::class.java.getMethod(
                "setShowSoftInputOnFocus",
                java.lang.Boolean.TYPE
            )
                .invoke(wv, false)
        }
        wv.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) hideSystemKeyboard(view)
        }
    }

    private inner class GeoBridge {
        @JavascriptInterface fun onInputFocus(value: String?) = runOnUiThread {
            suppressImeFor(1800L)
            showKeyboard()
        }
        @JavascriptInterface fun onInputBlur() = runOnUiThread {
            hideSystemKeyboard()
        }
    }

    private fun suppressImeFor(durationMs: Long) {
        suppressImeUntilMs = System.currentTimeMillis() + durationMs
        hideSystemKeyboard()
        fun tick() {
            hideSystemKeyboard()
            if (System.currentTimeMillis() < suppressImeUntilMs) {
                imeSuppressor.postDelayed({ tick() }, 90L)
            }
        }
        imeSuppressor.removeCallbacksAndMessages(null)
        tick()
    }

    private fun hideSystemKeyboard(view: View = webView) {
        runCatching {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    // ------------------------------------------------------------------
    //  Injected JavaScript
    // ------------------------------------------------------------------

    /**
     * Document-start compatibility shim: makes ChatGPT's evergreen DESKTOP bundle
     * runnable on this WebView's Chrome 95 (Oct 2021) engine.
     *
     * Three parts, all of which must run before/around page boot:
     *  1. ES2022+ polyfills — every missing runtime API we know ChatGPT's code can
     *     call unconditionally (findLastIndex was the proven boot-crasher; the rest
     *     are the standard set added to Chrome after 95: structuredClone,
     *     toSorted/with, Object.groupBy, Promise.withResolvers, AbortSignal.timeout,
     *     popover stubs, startViewTransition, ...). Added via defineProperty
     *     (non-enumerable) so for..in loops over arrays/objects don't break.
     *  2. Dark mode — prefers-color-scheme spoof + theme storage, before first paint.
     *  3. DOM-phase fixes once <head> exists:
     *     - viewport locked to width=1024 (desktop layout width; user-scalable=no
     *       per the X3 guide §7 to stop trackpad zoom-loops). useWideViewPort then
     *       scales it to fit the 640px eye.
     *     - CSS @layer unwrapper: Chrome 95 predates @layer (Chrome 99) and drops
     *       entire @layer blocks — on layer-based sheets that strips ALL styling,
     *       which is exactly the "unstyled DOM" failure seen earlier. We refetch
     *       each same-origin stylesheet that uses @layer, strip the layer wrappers
     *       (keeping rule content and order), and swap it in.
     */
    private val docStartJs = """
        (function(){
          if (window.__tgptDocStart) return; window.__tgptDocStart = true;
          function def(o, n, fn){
            try { if (!o[n]) Object.defineProperty(o, n, {value: fn, writable: true, configurable: true}); }
            catch(e) { try { if (!o[n]) o[n] = fn; } catch(_) {} }
          }
          /* ---- 1. ES2022+ polyfills (Chrome 95 baseline) ---- */
          def(Array.prototype, 'findLastIndex', function(cb, th){ for (var i=this.length-1;i>=0;i--){ if (cb.call(th, this[i], i, this)) return i; } return -1; });
          def(Array.prototype, 'findLast', function(cb, th){ for (var i=this.length-1;i>=0;i--){ if (cb.call(th, this[i], i, this)) return this[i]; } });
          def(Array.prototype, 'toSorted', function(cmp){ return this.slice().sort(cmp); });
          def(Array.prototype, 'toReversed', function(){ return this.slice().reverse(); });
          def(Array.prototype, 'toSpliced', function(){ var a=this.slice(); a.splice.apply(a, arguments); return a; });
          def(Array.prototype, 'with', function(i, v){ var a=this.slice(); a[i < 0 ? a.length + i : i] = v; return a; });
          def(Array, 'fromAsync', function(it, mapFn){ return (async function(){ var out=[], i=0; for await (var v of it){ out.push(mapFn ? await mapFn(v, i++) : v); } return out; })(); });
          def(Object, 'groupBy', function(items, cb){ var out=Object.create(null), i=0; [].slice.call(items).forEach(function(it){ var k=cb(it, i++); (out[k] = out[k] || []).push(it); }); return out; });
          def(Map, 'groupBy', function(items, cb){ var out=new Map(), i=0; [].slice.call(items).forEach(function(it){ var k=cb(it, i++); if (!out.has(k)) out.set(k, []); out.get(k).push(it); }); return out; });
          def(Promise, 'withResolvers', function(){ var res, rej, p = new Promise(function(a, b){ res = a; rej = b; }); return {promise: p, resolve: res, reject: rej}; });
          def(String.prototype, 'isWellFormed', function(){ try { encodeURIComponent(this.toString()); return true; } catch(e) { return false; } });
          def(String.prototype, 'toWellFormed', function(){
            var s = this.toString(), out = '';
            for (var i = 0; i < s.length; i++) {
              var c = s.charCodeAt(i);
              if (c >= 0xD800 && c <= 0xDBFF) {
                var d = s.charCodeAt(i + 1);
                if (d >= 0xDC00 && d <= 0xDFFF) { out += s[i] + s[i+1]; i++; } else out += '�';
              } else if (c >= 0xDC00 && c <= 0xDFFF) { out += '�'; }
              else out += s[i];
            }
            return out;
          });
          if (typeof structuredClone !== 'function') {
            window.structuredClone = function sc(v, seen){
              seen = seen || new Map();
              if (v === null || typeof v !== 'object') return v;
              if (seen.has(v)) return seen.get(v);
              if (v instanceof Date) return new Date(v.getTime());
              if (v instanceof RegExp) return new RegExp(v.source, v.flags);
              if (v instanceof ArrayBuffer) return v.slice(0);
              if (ArrayBuffer.isView(v)) return new v.constructor(v.buffer.slice(0), v.byteOffset, v.length);
              if (v instanceof Map) { var m = new Map(); seen.set(v, m); v.forEach(function(val, key){ m.set(sc(key, seen), sc(val, seen)); }); return m; }
              if (v instanceof Set) { var st = new Set(); seen.set(v, st); v.forEach(function(val){ st.add(sc(val, seen)); }); return st; }
              if (Array.isArray(v)) { var a = []; seen.set(v, a); for (var i = 0; i < v.length; i++) a[i] = sc(v[i], seen); return a; }
              var o = {}; seen.set(v, o);
              Object.keys(v).forEach(function(k){ o[k] = sc(v[k], seen); });
              return o;
            };
          }
          if (typeof AbortSignal !== 'undefined') {
            def(AbortSignal, 'timeout', function(ms){ var c = new AbortController(); setTimeout(function(){ try { c.abort(new DOMException('TimeoutError', 'TimeoutError')); } catch(e) { c.abort(); } }, ms); return c.signal; });
            def(AbortSignal, 'any', function(signals){ var c = new AbortController(); [].slice.call(signals).forEach(function(s){ if (s.aborted) { try { c.abort(s.reason); } catch(e) { c.abort(); } } else s.addEventListener('abort', function(){ try { c.abort(s.reason); } catch(e) { c.abort(); } }); }); return c.signal; });
          }
          if (typeof URL !== 'undefined') def(URL, 'canParse', function(u, b){ try { new URL(u, b); return true; } catch(e) { return false; } });
          if (typeof reportError !== 'function') { try { window.reportError = function(e){ try { console.error(e); } catch(_) {} }; } catch(e) {} }
          /* navigator.permissions is ABSENT in this WebView. ChatGPT's voice mode
             calls navigator.permissions.query({name:'microphone'}); the throw makes
             it show "enable microphone in settings" and never start capture -- even
             though getUserMedia works and onPermissionRequest grants the mic. Provide
             a stub that reports the mic as granted so it proceeds. */
          if (!navigator.permissions || typeof navigator.permissions.query !== 'function') {
            var permStub = { query: function(desc){
              var n = desc && desc.name;
              var state = (n === 'microphone') ? 'granted' : 'prompt';
              return Promise.resolve({ state: state, name: n, onchange: null,
                addEventListener: function(){}, removeEventListener: function(){}, dispatchEvent: function(){ return false; } });
            } };
            try { Object.defineProperty(navigator, 'permissions', {value: permStub, configurable: true}); }
            catch(e) { try { navigator.permissions = permStub; } catch(_) {} }
          }
          if (typeof HTMLElement !== 'undefined' && !HTMLElement.prototype.showPopover) {
            def(HTMLElement.prototype, 'showPopover', function(){});
            def(HTMLElement.prototype, 'hidePopover', function(){});
            def(HTMLElement.prototype, 'togglePopover', function(){ return false; });
          }
          if (typeof Element !== 'undefined') def(Element.prototype, 'checkVisibility', function(){ var s = getComputedStyle(this); return s.display !== 'none' && s.visibility !== 'hidden' && this.getClientRects().length > 0; });
          if (document && !document.startViewTransition) {
            document.startViewTransition = function(cb){ var done = Promise.resolve().then(function(){ if (cb) return cb(); }); return {finished: done, ready: done, updateCallbackDone: done, skipTransition: function(){}}; };
          }
          /* ---- 2. dark mode before first paint ---- */
          try { ['theme','color-theme','ui-theme'].forEach(function(k){ localStorage.setItem(k, 'dark'); }); } catch(e) {}
          try {
            var mm = window.matchMedia ? window.matchMedia.bind(window) : null;
            if (mm && !window.__tgptMm) {
              window.__tgptMm = true;
              window.matchMedia = function(q){
                var qq = String(q);
                if (qq.indexOf('prefers-color-scheme') >= 0) {
                  var dark = qq.indexOf('dark') >= 0;
                  return {matches: dark, media: qq, onchange: null, addListener: function(){}, removeListener: function(){}, addEventListener: function(){}, removeEventListener: function(){}, dispatchEvent: function(){ return false; }};
                }
                return mm(q);
              };
            }
          } catch(e) {}
          /* ---- 3. DOM-phase fixes ---- */
          function unwrapLayers(css){
            css = css.replace(/@layer[^{};]*;/g, '');
            var guard = 0;
            while (css.indexOf('@layer') >= 0 && guard++ < 400) {
              var idx = css.indexOf('@layer');
              var braceStart = css.indexOf('{', idx);
              if (braceStart < 0) break;
              var depth = 1, j = braceStart + 1;
              while (j < css.length && depth > 0) {
                var ch = css.charAt(j);
                if (ch === '{') depth++;
                else if (ch === '}') depth--;
                j++;
              }
              css = css.slice(0, idx) + css.slice(braceStart + 1, j - 1) + css.slice(j);
            }
            return css;
          }
          function fixSheet(link){
            if (link.__tgptL) return; link.__tgptL = true;
            fetch(link.href).then(function(r){ return r.text(); }).then(function(css){
              if (css.indexOf('@layer') < 0) return;
              var st = document.createElement('style');
              st.setAttribute('data-tgpt-unlayered', '1');
              st.textContent = unwrapLayers(css);
              link.parentNode.insertBefore(st, link.nextSibling);
              link.disabled = true;
              console.log('[tgpt] unlayered stylesheet: ' + link.href.split('/').pop());
            }).catch(function(){});
          }
          function fixInline(st){
            if (st.__tgptL || st.hasAttribute('data-tgpt-unlayered')) return; st.__tgptL = true;
            var css = st.textContent || '';
            if (css.indexOf('@layer') < 0) return;
            st.textContent = unwrapLayers(css);
            console.log('[tgpt] unlayered inline style');
          }
          function scanStyles(){
            [].slice.call(document.querySelectorAll('link[rel="stylesheet"]')).forEach(fixSheet);
            [].slice.call(document.querySelectorAll('style')).forEach(fixInline);
          }
          function fixViewport(){
            var v = document.querySelector('meta[name="viewport"]');
            if (!v) { v = document.createElement('meta'); v.setAttribute('name', 'viewport'); (document.head || document.documentElement).appendChild(v); }
            var want = 'width=1024, user-scalable=no';
            if (v.getAttribute('content') !== want) v.setAttribute('content', want);
          }
          function domFixes(){
            try { fixViewport(); } catch(e) {}
            try { scanStyles(); } catch(e) {}
            try {
              document.documentElement.classList.add('dark');
              document.documentElement.style.colorScheme = 'dark';
              document.documentElement.style.background = '#0d0d0d';
            } catch(e) {}
          }
          function whenDom(fn){
            if (document.head) { fn(); return; }
            var o = new MutationObserver(function(){ if (document.head) { o.disconnect(); fn(); } });
            o.observe(document.documentElement || document, {childList: true, subtree: true});
          }
          whenDom(function(){
            domFixes();
            var obs = new MutationObserver(function(){
              clearTimeout(window.__tgptDomFixT);
              window.__tgptDomFixT = setTimeout(domFixes, 150);
            });
            obs.observe(document.documentElement, {childList: true, subtree: true});
            [400, 1200, 3000].forEach(function(ms){ setTimeout(domFixes, ms); });
          });
        })();
    """.trimIndent()

    /**
     * Polyfill ES2023 Array methods missing from this WebView's Chrome 95 engine.
     *
     * Root cause (confirmed on-device via WebView DevTools console): ChatGPT's boot
     * code calls `Array.prototype.findLastIndex` (shipped in Chrome 97) inside a
     * `disablePerfDetector` initializer. On Chrome 95 that throws
     * `TypeError: t.findLastIndex is not a function`, uncaught, which crashes the
     * component tree that was mounting and trips React's error boundary --
     * permanently showing "Content failed to load" for that page load, since this
     * happens once at boot rather than on a recurring timer. Injected first, before
     * any other script, so it wins the race against that boot-time call.
     */
    private fun injectEs2023Polyfills() {
        val js = """
            (function(){
              if (!Array.prototype.findLastIndex) {
                Array.prototype.findLastIndex = function(cb, thisArg){
                  for (var i = this.length - 1; i >= 0; i--) {
                    if (cb.call(thisArg, this[i], i, this)) return i;
                  }
                  return -1;
                };
              }
              if (!Array.prototype.findLast) {
                Array.prototype.findLast = function(cb, thisArg){
                  for (var i = this.length - 1; i >= 0; i--) {
                    if (cb.call(thisArg, this[i], i, this)) return this[i];
                  }
                  return undefined;
                };
              }
            })();
        """.trimIndent()
        runCatching { webView.evaluateJavascript(js, null) }
    }

    /** Force ChatGPT into its dark theme by spoofing prefers-color-scheme + theme storage. */
    private fun injectDarkMode() {
        val js = """
            (function(){
              try{ ['theme','color-theme','ui-theme'].forEach(function(k){ localStorage.setItem(k,'dark'); }); }catch(e){}
              if(!window.__tgptDark){
                window.__tgptDark = true;
                var mm = window.matchMedia ? window.matchMedia.bind(window) : null;
                if(mm){
                  window.matchMedia = function(q){
                    var qq = String(q);
                    if(qq.indexOf('prefers-color-scheme') >= 0){
                      var dark = qq.indexOf('dark') >= 0;
                      return { matches:dark, media:qq, onchange:null,
                        addListener:function(){}, removeListener:function(){},
                        addEventListener:function(){}, removeEventListener:function(){},
                        dispatchEvent:function(){ return false; } };
                    }
                    return mm(q);
                  };
                }
              }
              try{
                var de = document.documentElement;
                de.classList.add('dark');
                de.style.colorScheme = 'dark';
                de.style.background = '#0d0d0d';
              }catch(e){}
            })();
        """.trimIndent()
        runCatching { webView.evaluateJavascript(js, null) }
    }

    /**
     * Auto-repair ChatGPT's "bottom sheet" login/verification dialogs.
     *
     * On this WebView's Chrome 95 engine, the CSS-custom-property-driven reveal
     * animation these dialogs use (`--bottom-sheet-fallback-reveal-progress`) never
     * runs, so the `<dialog>` and its scrollable inner container collapse to a
     * zero-height box while their content still renders far below the fold
     * (confirmed on-device: the email field ends up around y=900 in a 480px-tall
     * viewport, so tapping "Log in" appears to do nothing). The fix is generic
     * (matches any `dialog[open]` with a collapsed box, not a specific build-hashed
     * class name, since ChatGPT's CSS module hashes change across deploys): force
     * the dialog and its first child to occupy the viewport with an explicit height
     * and internal scrolling.
     */
    private fun injectDialogLayoutFix() {
        val js = """
            (function(){
              if (window.__tgptDialogFix) return; window.__tgptDialogFix = true;
              var FIXED_ATTR = 'data-tgpt-dialog-fixed';
              function vh(){ return window.innerHeight || document.documentElement.clientHeight || 480; }
              function vw(){ return window.innerWidth || document.documentElement.clientWidth || 640; }
              function fixDialog(dlg){
                if(!dlg || dlg.hasAttribute(FIXED_ATTR)) return;
                var r = dlg.getBoundingClientRect();
                // Only intervene when the dialog's own box has collapsed (the broken
                // reveal animation on this engine) while it still has content -- a
                // healthy, normally-sized dialog is left untouched.
                if (r.height > 40 || dlg.children.length === 0) return;
                dlg.setAttribute(FIXED_ATTR, '1');
                var H = vh(), W = vw();
                dlg.style.setProperty('position','fixed','important');
                dlg.style.setProperty('top','0px','important');
                dlg.style.setProperty('left','0px','important');
                dlg.style.setProperty('right','0px','important');
                dlg.style.setProperty('bottom','auto','important');
                dlg.style.setProperty('width', W+'px','important');
                dlg.style.setProperty('height', H+'px','important');
                dlg.style.setProperty('max-height', H+'px','important');
                dlg.style.setProperty('margin','0','important');
                dlg.style.setProperty('overflow','hidden','important');
                // These sheets nest their scrollable content in a single wrapper
                // child; give it a real height + its own scroll so tall forms fit.
                var scroller = dlg.firstElementChild;
                if (scroller) {
                  scroller.style.setProperty('height', H+'px','important');
                  scroller.style.setProperty('max-height', H+'px','important');
                  scroller.style.setProperty('overflow-y','auto','important');
                  scroller.style.setProperty('position','relative','important');
                }
              }
              // Re-center any modal that renders partly off-screen. Chrome 95 fails
              // to center ChatGPT's Settings modal (it lands at left:512 in a 1024
              // viewport and its right half is clipped). If a [role=dialog]/dialog
              // that fits the viewport is nonetheless positioned outside it, pin it
              // fixed-centered.
              var FIT_ATTR = 'data-tgpt-fit';
              function fitModal(el){
                if (!el || el.getAttribute(FIT_ATTR) === '1') return;
                var r = el.getBoundingClientRect();
                var W = vw(), H = vh();
                if (r.width < 40 || r.height < 40) return;
                var overflows = r.right > W + 2 || r.left < -2 || r.bottom > H + 2 || r.top < -2;
                if (!overflows) return;
                if (r.width > W && r.height > H) return; // genuinely bigger than screen
                el.setAttribute(FIT_ATTR, '1');
                el.style.setProperty('position','fixed','important');
                el.style.setProperty('left','50%','important');
                el.style.setProperty('top','50%','important');
                el.style.setProperty('right','auto','important');
                el.style.setProperty('bottom','auto','important');
                el.style.setProperty('inset','auto','important');
                el.style.setProperty('transform','translate(-50%,-50%)','important');
                el.style.setProperty('margin','0','important');
                el.style.setProperty('max-width','100vw','important');
                el.style.setProperty('max-height','100vh','important');
              }
              function scan(){
                document.querySelectorAll('dialog[open]').forEach(fixDialog);
                document.querySelectorAll('[role="dialog"], dialog[open]').forEach(fitModal);
              }
              scan();
              var obs = new MutationObserver(function(){
                clearTimeout(window.__tgptDialogFixT);
                window.__tgptDialogFixT = setTimeout(scan, 80);
              });
              obs.observe(document.documentElement || document, {childList:true, subtree:true, attributes:true, attributeFilter:['open','style','class']});
              // The broken measurement sometimes settles a beat after `open` is
              // set; keep re-checking for a short window after each mutation burst.
              [150,350,700,1400].forEach(function(ms){ setTimeout(scan, ms); });
            })();
        """.trimIndent()
        runCatching { webView.evaluateJavascript(js, null) }
    }

    /** Persistent bridge that lets the custom keyboard edit whatever field ChatGPT has focused. */
    private fun injectInputSupport() {
        val js = """
            (function(){
              if (window.__tgptHooked) return;
              // Too early (document-start race): documentElement may not exist yet.
              // Return WITHOUT setting the flag so the onPageFinished pass retries —
              // setting it first and then crashing left input support permanently
              // dead for that page load (the intermittent "clicks do nothing" bug).
              if (!document.documentElement) return;
              window.__tgptHooked = true;
              function isInput(el){ return el && (el.tagName==='INPUT' || el.tagName==='TEXTAREA' || el.isContentEditable); }
              try {
                var style=document.createElement('style');
                style.textContent='[data-tgpt-active="1"]{outline:2px solid #10a37f!important;outline-offset:2px!important;box-shadow:0 0 0 3px rgba(16,163,127,.28)!important;}';
                document.documentElement.appendChild(style);
              } catch(e) {}
              function markActive(el){
                document.querySelectorAll('[data-tgpt-active="1"]').forEach(function(n){
                  if(n!==el) n.removeAttribute('data-tgpt-active');
                });
                try{ el.setAttribute('data-tgpt-active','1'); }catch(_){}
              }
              function visible(el){
                if(!el || !el.getClientRects || !el.getClientRects().length) return false;
                var s=getComputedStyle(el);
                return s.visibility!=='hidden' && s.display!=='none' && el.offsetWidth>0 && el.offsetHeight>0;
              }
              function findPromptInput(){
                var pref=document.querySelector('.ql-editor, [contenteditable="true"][aria-label*="prompt" i], [contenteditable="true"][aria-label*="Gemini" i], #prompt-textarea, textarea[data-testid="prompt-textarea"], textarea[placeholder]');
                if(pref && visible(pref)) return pref;
                var candidates=[].slice.call(document.querySelectorAll('textarea, input, [contenteditable="true"]'));
                return candidates.find(function(el){
                  var p=((el.getAttribute('placeholder')||'')+' '+(el.getAttribute('aria-label')||'')+' '+(el.name||'')+' '+(el.type||'')).toLowerCase();
                  return visible(el) && (p.indexOf('message')>=0 || p.indexOf('ask')>=0 || p.indexOf('prompt')>=0 || p.indexOf('chatgpt')>=0 || p.indexOf('search')>=0);
                }) || candidates.find(visible) || null;
              }
              function remember(el){
                if(isInput(el)){
                  window.__tgptActiveInput = el;
                  markActive(el);
                  try{ GeoBridge.onInputFocus(typeof el.value === 'string' ? el.value : (el.textContent || '')); }catch(_){}
                }
              }
              function activeInput(){
                var el = window.__tgptActiveInput;
                if(!isInput(el) || !document.contains(el)) el = document.activeElement;
                if(!isInput(el)) el = findPromptInput();
                if(!isInput(el)) return null;
                window.__tgptActiveInput = el;
                markActive(el);
                try { if(el.focus) el.focus({preventScroll:true}); } catch(e) { try{ el.focus(); }catch(_){} }
                return el;
              }
              function setNativeValue(el, value){
                var oldValue = typeof el.value === 'string' ? el.value : '';
                var proto = Object.getPrototypeOf(el);
                var desc = proto && Object.getOwnPropertyDescriptor(proto, 'value');
                if(!desc && el instanceof HTMLInputElement) desc = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
                if(!desc && el instanceof HTMLTextAreaElement) desc = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value');
                if(desc && desc.set) desc.set.call(el, value); else el.value = value;
                if(el._valueTracker) { try { el._valueTracker.setValue(oldValue); } catch(_){} }
              }
              function notify(el){
                try { el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:el.value})); }
                catch(e) { el.dispatchEvent(new Event('input',{bubbles:true})); }
                el.dispatchEvent(new Event('change',{bubbles:true}));
                el.dispatchEvent(new KeyboardEvent('keyup',{key:'Unidentified',bubbles:true}));
              }
              document.addEventListener('focusin', function(e){ remember(e.target); }, true);
              document.addEventListener('pointerdown', function(e){ if(isInput(e.target)) remember(e.target); }, true);
              document.addEventListener('mousedown', function(e){ if(isInput(e.target)) remember(e.target); }, true);
              document.addEventListener('touchstart', function(e){ if(isInput(e.target)) remember(e.target); }, true);
              document.addEventListener('click', function(e){ if(isInput(e.target)) remember(e.target); }, true);
              document.addEventListener('focusout', function(e){ if(isInput(e.target)){ try{ GeoBridge.onInputBlur(); }catch(_){} } }, true);
              if (isInput(document.activeElement)) remember(document.activeElement);
              window.__tgptDefocus = function(){
                var el = window.__tgptActiveInput || document.activeElement;
                if(isInput(el)){ try{ el.blur(); }catch(_){} }
                window.__tgptActiveInput = null;
              };
              function caret(el){
                var s = (typeof el.selectionStart === 'number') ? el.selectionStart : (el.value||'').length;
                var e = (typeof el.selectionEnd === 'number') ? el.selectionEnd : s;
                return [s, e];
              }
              window.__tgptInsert = function(text){
                var el=activeInput(); if(!el) return;
                if(el.isContentEditable){
                  try{ el.focus({preventScroll:true}); }catch(_){ try{el.focus();}catch(__){} }
                  if(!document.execCommand || !document.execCommand('insertText', false, text)){
                    el.textContent = (el.textContent||'') + text;
                  }
                  el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:text}));
                  return;
                }
                if(typeof el.value!=='string') return;
                var c=caret(el), s=c[0], e=c[1], v=el.value;
                var nv=v.slice(0,s)+text+v.slice(e);
                setNativeValue(el,nv);
                var pos=s+text.length;
                if(typeof el.selectionStart==='number') el.selectionStart=el.selectionEnd=pos;
                notify(el);
              };
              window.__tgptBackspace = function(){
                var el=activeInput(); if(!el) return;
                if(el.isContentEditable){
                  try{ el.focus({preventScroll:true}); }catch(_){ try{el.focus();}catch(__){} }
                  if(!document.execCommand || !document.execCommand('delete', false)){
                    el.textContent = (el.textContent||'').slice(0,-1);
                  }
                  el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'deleteContentBackward'}));
                  return;
                }
                if(typeof el.value!=='string') return;
                var c=caret(el), s=c[0], e=c[1], v=el.value, nv, pos;
                if(s!==e){ nv=v.slice(0,s)+v.slice(e); pos=s; }
                else if(s>0){ nv=v.slice(0,s-1)+v.slice(s); pos=s-1; }
                else return;
                setNativeValue(el,nv);
                if(typeof el.selectionStart==='number') el.selectionStart=el.selectionEnd=pos;
                notify(el);
              };
              window.__tgptClear = function(){
                var el=activeInput(); if(!el) return;
                if(el.isContentEditable){
                  el.textContent='';
                  el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'deleteContent'}));
                  return;
                }
                if(typeof el.value!=='string') return;
                setNativeValue(el,'');
                if(typeof el.selectionStart==='number') el.selectionStart=el.selectionEnd=0;
                notify(el);
              };
              window.__tgptMoveCaret = function(delta){
                var el=activeInput(); if(!el) return;
                if(typeof el.selectionStart!=='number') return;
                var len=(el.value||'').length;
                var pos=Math.max(0, Math.min(len, el.selectionStart + delta));
                el.selectionStart=el.selectionEnd=pos;
              };
              window.__tgptEnter = function(){
                var el=activeInput();
                if(el){
                  ['keydown','keypress','keyup'].forEach(function(t){ el.dispatchEvent(new KeyboardEvent(t,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true})); });
                  if(el.form){ try{ el.form.requestSubmit ? el.form.requestSubmit() : el.form.submit(); }catch(e){} }
                }
                var sendSel=['[data-testid="send-button"]','button[data-testid="send-button"]','button[aria-label*="Send" i]','button[aria-label*="send" i]'];
                for(var i=0;i<sendSel.length;i++){ var b=document.querySelector(sendSel[i]); if(b && b.offsetParent!==null && !b.disabled){ b.click(); break; } }
              };
            })();
        """.trimIndent()
        runCatching { webView.evaluateJavascript(js, null) }
    }

    /**
     * Keep the composer's mic/Voice buttons on-screen.
     *
     * On the signed-in composer, ChatGPT's quick-action suggestion chips ("Create an
     * image", "Write or edit", "Look something up") push the mic/Voice row down; on
     * this 480px-tall viewport that leaves "Start Voice" a few pixels below the
     * fold (confirmed on-device: y=485 in a 480px view). Rather than guess at
     * ChatGPT's auto-generated utility class names to hide the chips, just scroll
     * the button into view whenever it ends up outside the viewport.
     */
    private fun injectComposerVisibilityFix() {
        val js = """
            (function(){
              if (window.__tgptComposerVis) return; window.__tgptComposerVis = true;
              function ensureVisible(){
                var btns = [].slice.call(document.querySelectorAll('button'));
                var voiceBtn = btns.find(function(b){ return (b.getAttribute('aria-label')||'').toLowerCase()==='start voice'; });
                if(!voiceBtn) return;
                var r = voiceBtn.getBoundingClientRect();
                var h = window.innerHeight || 480;
                if (r.bottom > h || r.top < 0) {
                  try { voiceBtn.scrollIntoView({block:'end', inline:'nearest'}); } catch(e){}
                }
              }
              ensureVisible();
              var obs = new MutationObserver(function(){
                clearTimeout(window.__tgptComposerVisT);
                window.__tgptComposerVisT = setTimeout(ensureVisible, 120);
              });
              obs.observe(document.documentElement || document, {childList:true, subtree:true});
              [200,600,1200].forEach(function(ms){ setTimeout(ensureVisible, ms); });
            })();
        """.trimIndent()
        runCatching { webView.evaluateJavascript(js, null) }
    }

    /** Voice-button locator, page scroller and voice-carousel swipe. Called on demand. */
    private fun injectGlassesHelpers() {
        val js = """
            (function(){
              if(window.__tgptHelpers) return; window.__tgptHelpers=true;
              function visible(el){
                if(!el || !el.getClientRects || !el.getClientRects().length) return false;
                var s=getComputedStyle(el);
                return s.visibility!=='hidden' && s.display!=='none' && el.offsetWidth>0 && el.offsetHeight>0;
              }
              function findVoiceButton(){
                var sels=[
                  'button[aria-label*="Live" i]',
                  'button[aria-label*="microphone" i]',
                  '[data-testid="composer-speech-button"]',
                  '[data-testid*="speech" i]',
                  '[data-testid*="voice" i]',
                  'button[aria-label*="voice" i]',
                  'button[aria-label*="speak" i]',
                  'button[aria-label*="dictate" i]',
                  'a[aria-label*="voice" i]'
                ];
                for(var i=0;i<sels.length;i++){
                  var els=document.querySelectorAll(sels[i]);
                  for(var j=0;j<els.length;j++){ if(visible(els[j])) return els[j]; }
                }
                // Fallback: the desktop composer's "Voice" pill (match by visible text).
                var btns=document.querySelectorAll('button, a, [role="button"]');
                for(var k=0;k<btns.length;k++){
                  var t=((btns[k].innerText||btns[k].textContent||'')+'').trim().toLowerCase();
                  if((t==='voice' || t==='live' || t==='gemini live' || t==='use voice mode' || t==='start voice mode') && visible(btns[k])) return btns[k];
                }
                return null;
              }
              window.__tgptVoiceButtonRect=function(){
                var el=findVoiceButton(); if(!el) return {found:false};
                var r=el.getBoundingClientRect();
                return {found:true, x:r.left+r.width/2, y:r.top+r.height/2,
                  iw:(window.innerWidth||document.documentElement.clientWidth||640),
                  ih:(window.innerHeight||document.documentElement.clientHeight||480)};
              };
              window.__tgptClickVoiceButton=function(){ var el=findVoiceButton(); if(el){ el.click(); return true; } return false; };
              // Click at a viewport point. The X3 WebView's raw touch->click emulation
              // doesn't reliably fire ChatGPT's React buttons, but a DOM click does, so
              // we synthesize the full pointer/mouse sequence and call .click() on the
              // actionable element under the cursor.
              window.__tgptClickAt=function(x,y,vw,vh){
                // x/y arrive in Android-view px; convert to CSS px when the layout
                // viewport is wider than the view (desktop mode: 1024 CSS in 640 px).
                if (vw > 0 && vh > 0 && window.innerWidth > 0 && window.innerHeight > 0) {
                  x = Math.round(x * (window.innerWidth / vw));
                  y = Math.round(y * (window.innerHeight / vh));
                }
                var el=document.elementFromPoint(x,y);
                console.log('[tgpt] clickAt',x,y,'elementFromPoint=',el?(el.tagName+'.'+(el.className||'')):'null');
                if(!el) return false;
                var act=el.closest?el.closest('button,a,[role="button"],[role="menuitem"],[role="option"],[role="tab"],[role="switch"],[role="checkbox"],summary,label,input,textarea,select'):null;
                // ChatGPT's in-page "Log in" bottom sheet doesn't render correctly on
                // this WebView's older engine -- its content collapses off-screen (its
                // own reveal animation never completes, and it self-closes as a
                // failure fallback; injectDialogLayoutFix's CSS patch can't outrun
                // that). The hosted login page at /auth/login_with is a plain
                // server-rendered page and renders correctly here, so route straight
                // there instead of letting the broken in-page modal open.
                /* (ChatGPT-era hosted-login reroute removed: Gemini's sign-in
                   goes through accounts.google.com, which renders fine here.) */
                var base={bubbles:true,cancelable:true,view:window,clientX:x,clientY:y,screenX:x,screenY:y,button:0};
                function P(t,btn){ if(typeof PointerEvent!=='undefined'){ try{ el.dispatchEvent(new PointerEvent(t,Object.assign({pointerId:1,pointerType:'mouse',isPrimary:true,buttons:btn},base))); }catch(e){} } }
                function M(t,btn){ try{ el.dispatchEvent(new MouseEvent(t,Object.assign({buttons:btn},base))); }catch(e){} }
                P('pointerover',1); M('mouseover',1);
                P('pointerdown',1); M('mousedown',1);
                P('pointerup',0); M('mouseup',0);
                var isField=act&&((act.tagName==='INPUT'&&act.type!=='button'&&act.type!=='submit'&&act.type!=='checkbox'&&act.type!=='radio')||act.tagName==='TEXTAREA'||act.isContentEditable);
                console.log('[tgpt] clickAt actionable=',act?(act.tagName+' "'+((act.innerText||act.textContent||'').trim().slice(0,24))+'"'):'null','isField=',isField);
                if(isField){ try{ act.focus(); }catch(e){} M('click',0); }
                else if(act&&act.click){ try{ act.click(); }catch(e){} }
                else { M('click',0); if(el.isContentEditable){ try{ el.focus(); }catch(e){} } }
                return true;
              };
              window.__tgptScrollBy=function(dx,dy){
                var x=Math.round((window.innerWidth||640)*0.5), y=Math.round((window.innerHeight||480)*0.5);
                var el=document.elementFromPoint(x,y)||document.scrollingElement||document.body, node=el;
                while(node && node!==document.body){
                  var s=getComputedStyle(node);
                  if(/(auto|scroll)/.test(s.overflowY) && node.scrollHeight>node.clientHeight){ node.scrollTop+=dy; node.scrollLeft+=dx; break; }
                  node=node.parentElement;
                }
                if(!node || node===document.body){
                  var se=document.scrollingElement||document.documentElement||document.body;
                  se.scrollTop+=dy; se.scrollLeft+=dx; try{ window.scrollBy(dx,dy); }catch(e){}
                }
                try{ el.dispatchEvent(new WheelEvent('wheel',{deltaX:dx,deltaY:dy,bubbles:true,cancelable:true})); }catch(e){}
              };
              function pickSwipeTarget(x,y){
                var el=document.elementFromPoint(x,y), node=el;
                while(node && node!==document.body){
                  var s=getComputedStyle(node);
                  if(/(auto|scroll)/.test(s.overflowX) && node.scrollWidth>node.clientWidth) return node;
                  var tag=((node.className&&node.className.baseVal!==undefined?node.className.baseVal:node.className)||'')+' '+((node.getAttribute&&node.getAttribute('data-testid'))||'');
                  if(/(carousel|swiper|slider|voice)/i.test(tag)) return node;
                  node=node.parentElement;
                }
                return el || document.body;
              }
              function fireP(el,type,x,y,btn){ if(typeof PointerEvent==='undefined') return; el.dispatchEvent(new PointerEvent(type,{bubbles:true,cancelable:true,view:window,pointerId:11,pointerType:'touch',isPrimary:true,clientX:x,clientY:y,button:0,buttons:btn})); }
              function fireT(el,type,x,y){ try{ var t=new Touch({identifier:11,target:el,clientX:x,clientY:y,pageX:x,pageY:y}); var l=(type==='touchend')?[]:[t]; el.dispatchEvent(new TouchEvent(type,{bubbles:true,cancelable:true,touches:l,targetTouches:l,changedTouches:[t]})); }catch(e){} }
              function fireM(el,type,x,y,btn){ el.dispatchEvent(new MouseEvent(type,{bubbles:true,cancelable:true,view:window,clientX:x,clientY:y,button:0,buttons:btn})); }
              function swipeElement(el,x0,y0,x1,y1){
                el=el||document.body;
                fireP(el,'pointerdown',x0,y0,1); fireT(el,'touchstart',x0,y0); fireM(el,'mousedown',x0,y0,1);
                var steps=6;
                for(var i=1;i<=steps;i++){ var x=x0+(x1-x0)*i/steps, y=y0+(y1-y0)*i/steps; fireP(el,'pointermove',x,y,1); fireT(el,'touchmove',x,y); fireM(el,'mousemove',x,y,1); }
                fireP(el,'pointerup',x1,y1,0); fireT(el,'touchend',x1,y1); fireM(el,'mouseup',x1,y1,0);
              }
              window.__tgptVoiceSwipe=function(dir){
                dir = dir < 0 ? -1 : 1;
                var arrowSel = dir > 0
                  ? ['button[aria-label*="previous" i]','button[aria-label*="prev" i]','button[aria-label*="back" i]']
                  : ['button[aria-label*="next" i]','button[aria-label*="forward" i]'];
                for(var i=0;i<arrowSel.length;i++){ var b=document.querySelector(arrowSel[i]); if(b && b.offsetParent!==null && !b.disabled){ b.click(); return true; } }
                var cx=Math.round((window.innerWidth||640)*0.5), cy=Math.round((window.innerHeight||480)*0.5);
                var target=pickSwipeTarget(cx,cy);
                var dist=Math.round((window.innerWidth||640)*0.5);
                swipeElement(target, cx-(dir*dist)/2, cy, cx+(dir*dist)/2, cy);
                var key = dir>0 ? 'ArrowLeft' : 'ArrowRight';
                ['keydown','keyup'].forEach(function(t){ (target||document).dispatchEvent(new KeyboardEvent(t,{key:key,code:key,bubbles:true})); });
                return true;
              };
            })();
        """.trimIndent()
        runCatching { webView.evaluateJavascript(js, null) }
    }

    // ------------------------------------------------------------------
    //  Cursor / gesture handlers wired into BinocularSbsLayout
    // ------------------------------------------------------------------

    /** Edge-of-screen cursor scroll → scroll the conversation. */
    private fun scrollPage(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        webView.evaluateJavascript("window.__tgptScrollBy && window.__tgptScrollBy($dx,$dy)", null)
    }

    // ------------------------------------------------------------------
    //  On-screen keyboard
    // ------------------------------------------------------------------

    private fun showKeyboard() {
        suppressImeFor(1200L)
        if (keyboardView == null) {
            keyboardView = CustomKeyboardView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                )
                setOnKeyboardActionListener(this@MainActivity)
            }
            keyboardContainer.addView(keyboardView)
        }
        keyboardView?.visibility = View.VISIBLE
        keyboardContainer.visibility = View.VISIBLE
        keyboardContainer.bringToFront()
        keyboardView?.bringToFront()
    }

    private fun hideKeyboard() {
        keyboardContainer.visibility = View.GONE
    }

    private fun handleLogicalClick(x: Float, y: Float): Boolean {
        Log.d(TAG, "handleLogicalClick x=$x y=$y webViewSize=${webView.width}x${webView.height} keyboardVisible=${keyboardContainer.visibility == View.VISIBLE}")
        suppressImeFor(1500L)
        if (keyboardContainer.visibility == View.VISIBLE) {
            val keyboard = keyboardView
            val top = keyboardContainer.top.toFloat()
            val bottom = keyboardContainer.bottom.toFloat()
            if (keyboard != null && y in top..bottom) {
                // Tap landed on the on-screen keyboard.
                if (keyboard.handleAnchoredTap(x, y - top)) suppressImeFor(900L)
                return true
            }
            // Tapped the page above the keyboard: dismiss it, then click through.
            hideKeyboard()
        }
        // All page clicks go through a JS DOM click at the cursor point — the raw
        // touch-to-click path doesn't fire ChatGPT's React buttons on this WebView.
        jsClickAt(x, y)
        return true
    }

    /** Dispatch a DOM click at the cursor position (view px; JS converts to CSS px). */
    private fun jsClickAt(x: Float, y: Float) {
        val xi = x.toInt()
        val yi = y.toInt()
        val vw = webView.width
        val vh = webView.height
        Log.d(TAG, "jsClickAt xi=$xi yi=$yi view=${vw}x$vh")
        webView.evaluateJavascript("window.__tgptClickAt && window.__tgptClickAt($xi,$yi,$vw,$vh)") { result ->
            Log.d(TAG, "jsClickAt result=$result")
        }
    }

    private fun js(expr: String) = runCatching {
        suppressImeFor(900L)
        webView.requestFocus()
        webView.evaluateJavascript(expr, null)
    }

    override fun onKeyPressed(key: String) {
        js("window.__tgptInsert && window.__tgptInsert(${JSONObject.quote(key)})")
    }

    override fun onBackspacePressed() {
        js("window.__tgptBackspace && window.__tgptBackspace()")
    }

    override fun onEnterPressed() {
        js("window.__tgptEnter && window.__tgptEnter()")
    }
    override fun onHideKeyboard() = hideKeyboard()
    override fun onClearPressed() {
        js("window.__tgptClear && window.__tgptClear()")
    }
    override fun onMoveCursorLeft() {
        js("window.__tgptMoveCaret && window.__tgptMoveCaret(-1)")
    }
    override fun onMoveCursorRight() {
        js("window.__tgptMoveCaret && window.__tgptMoveCaret(1)")
    }
    override fun onMicrophonePressed() {
        // The keyboard's Mic key is Groq dictation: record → Whisper →
        // type the transcript into the focused Gemini field.
        val groq = dictation ?: GroqDictation(
            context = this,
            keyProvider = { GeoPrefs.groqKey(this) },
            onState = { rec -> runOnUiThread { keyboardView?.setMicActive(rec) } },
            onResult = { text ->
                runOnUiThread {
                    js("window.__tgptInsert && window.__tgptInsert(${JSONObject.quote(text)})")
                }
            },
            onError = { msg -> Log.w(TAG, "dictation: $msg") }
        ).also { dictation = it }
        groq.toggle()
    }

    private var dictation: GroqDictation? = null

    // ------------------------------------------------------------------
    //  Window / lifecycle
    // ------------------------------------------------------------------

    private fun enableImmersiveFullscreen() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        holdPlaybackResources()
        if (hasFocus) enableImmersiveFullscreen()
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestAudioPermission() {
        if (hasAudioPermission()) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQ_AUDIO
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO) {
            val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            val req = pendingPermissionRequest
            if (req != null) {
                if (granted) req.grant(pendingPermissionResources) else req.deny()
                pendingPermissionRequest = null
                pendingPermissionResources = emptyArray()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Persist login as soon as we lose focus (process may be killed later under
        // RAM pressure without onDestroy — guide gotcha #6).
        runCatching { CookieManager.getInstance().flush() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            this::keyboardContainer.isInitialized && keyboardContainer.visibility == View.VISIBLE -> hideKeyboard()
            this::webView.isInitialized && webView.canGoBack() -> webView.goBack()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onDestroy() {
        runCatching { CookieManager.getInstance().flush() }
        releasePlaybackResources()
        stopPlaybackService()
        runCatching { webView.destroy() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "X3GeoLibre"
        private const val REQ_AUDIO = 2001
    }
}
