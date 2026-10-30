package su.vivarium.app

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.os.Message
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var fullscreenContainer: FrameLayout
    private var backPressedTime: Long = 0
    private var popupDialog: Dialog? = null

    private val TARGET_URL = "https://vivarium.su/"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AdBlocker.init(this)

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val rootLayout = FrameLayout(this)
        webView = WebView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
        fullscreenContainer = FrameLayout(this).apply { visibility = View.GONE }

        rootLayout.addView(webView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        rootLayout.addView(fullscreenContainer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(rootLayout)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // Native Bridge to force Android TV Keyboard to open/close
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun showKeyboard() {
                runOnUiThread {
                    webView.requestFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(webView, InputMethodManager.SHOW_FORCED)
                }
            }

            @JavascriptInterface
            fun hideKeyboard() {
                runOnUiThread {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(webView.windowToken, 0)
                }
            }
        }, "AndroidBridge")

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = userAgentString.replace("; wv", "").replace("Version/4.0 ", "")
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                if (AdBlocker.isAd(url)) {
                    return AdBlocker.createEmptyResource()
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url ?: return false
                val host = url.host?.lowercase() ?: return true

                if (host.contains("vivarium.su")) return false
                if (host.contains("google.com") || host.contains("googleapis.com") || host.contains("gstatic.com")) return false
                return true // Block all external ad redirects
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectTvEngineAndAdBlocker(view)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                val newWebView = WebView(this@MainActivity).apply {
                    settings.javaScriptEnabled = true
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.setSupportMultipleWindows(true)
                    settings.domStorageEnabled = true
                    settings.userAgentString = view?.settings?.userAgentString

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val url = request?.url?.toString() ?: return true
                            val host = request.url?.host?.lowercase() ?: return true
                            // Only allow Google Login inside popup windows; kill ad popups immediately
                            if (host.contains("google.com") || host.contains("googleapis.com") || host.contains("vivarium.su")) {
                                view?.loadUrl(url)
                                return true
                            }
                            popupDialog?.dismiss()
                            return true
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onCloseWindow(window: WebView?) {
                            popupDialog?.dismiss()
                            popupDialog = null
                        }
                    }
                }

                popupDialog = Dialog(this@MainActivity).apply {
                    setContentView(newWebView)
                    window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    show()
                    setOnDismissListener { newWebView.destroy() }
                }

                val transport = resultMsg?.obj as WebView.WebViewTransport
                transport.webView = newWebView
                resultMsg?.sendToTarget()
                return true
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                fullscreenContainer.addView(view)
                fullscreenContainer.visibility = View.VISIBLE
                webView.visibility = View.GONE
                windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
            }

            override fun onHideCustomView() {
                if (customView == null) return
                fullscreenContainer.removeView(customView)
                fullscreenContainer.visibility = View.GONE
                customView = null
                customViewCallback?.onCustomViewHidden()
                webView.visibility = View.VISIBLE
                windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (popupDialog?.isShowing == true) {
                    popupDialog?.dismiss()
                    popupDialog = null
                    return
                }
                if (customView != null) {
                    webView.webChromeClient?.onHideCustomView()
                    return
                }
                webView.evaluateJavascript("(window.__tvBack && window.__tvBack()) || false") { result ->
                    if (result == "true") return@evaluateJavascript
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        if (backPressedTime + 2000 > System.currentTimeMillis()) {
                            finish()
                        } else {
                            Toast.makeText(this@MainActivity, "Press back again to exit", Toast.LENGTH_SHORT).show()
                        }
                        backPressedTime = System.currentTimeMillis()
                    }
                }
            }
        })

        webView.loadUrl(TARGET_URL)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (popupDialog?.isShowing == true) {
            return super.dispatchKeyEvent(event)
        }

        val keyCode = event.keyCode
        val isHandledKey = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_REWIND -> true
            else -> false
        }

        if (isHandledKey) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val dir = when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> "UP"
                    KeyEvent.KEYCODE_DPAD_DOWN -> "DOWN"
                    KeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"
                    KeyEvent.KEYCODE_DPAD_RIGHT -> "RIGHT"
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "OK"
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> "PLAY_PAUSE"
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "FF"
                    KeyEvent.KEYCODE_MEDIA_REWIND -> "RW"
                    else -> ""
                }
                if (dir.isNotEmpty()) {
                    webView.evaluateJavascript("window.__tvKey && window.__tvKey('$dir');", null)
                    return true
                }
            }
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    private fun injectTvEngineAndAdBlocker(view: WebView?) {
        val js = """
            (function() {
                if (window.__tvEngineInstalled) return;
                window.__tvEngineInstalled = true;

                // 1. ULTRA-LIGHTWEIGHT CSS FOCUS RING (Zero GPU Lag)
                const style = document.createElement('style');
                style.innerHTML = `
                    [class*="ads-"], [class*="-ads"], [id*="ads-"], [id*="-ads"],
                    [class*="ad-banner"], [id*="ad-banner"], [class*="rek-"],
                    iframe[src*="ads"], iframe[src*="doubleclick"], iframe[src*="yandex"],
                    div[id^="yandex_rtb"], .adsbygoogle, .ad-container, .popup-ad {
                        display: none !important;
                    }
                    .__tv-focused {
                        outline: 3px solid #ff0055 !important;
                        outline-offset: 2px !important;
                    }
                `;
                document.head.appendChild(style);

                // 2. AUTO-KILL "CONFIRM YOU'RE NOT A ROBOT" QR SCAM OVERLAY
                function nukeRobotOverlay() {
                    // Kill any element containing the fake robot text
                    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
                    let node;
                    while ((node = walker.nextNode())) {
                        const txt = node.nodeValue ? node.nodeValue.toLowerCase() : '';
                        if (txt.includes("confirm you're not a robot") || txt.includes("confirm you are not a robot")) {
                            let container = node.parentElement;
                            // Walk up to find the fixed/absolute overlay wrapper and destroy it
                            while (container && container !== document.body) {
                                const pos = window.getComputedStyle(container).position;
                                if (pos === 'fixed' || pos === 'absolute' || container.style.zIndex > 100) {
                                    container.remove();
                                    return;
                                }
                                container = container.parentElement;
                            }
                            if (node.parentElement) node.parentElement.remove();
                        }
                    }
                    // Also remove transparent click-hijack overlays sitting on top of the page
                    document.querySelectorAll('div, iframe, a').forEach(el => {
                        if (el.hasAttribute('data-player') || el.closest('[data-player="true"]')) return;
                        const z = parseInt(el.style.zIndex || 0, 10);
                        if (z > 99999 && !el.classList.contains('__tv-focused')) {
                            el.remove();
                        }
                    });
                }
                setInterval(nukeRobotOverlay, 600);

                let currentFocus = null;
                let playerControlsVisible = false;
                let hideControlsTimer = null;

                // Fast visibility check without calling expensive getComputedStyle on every node
                function isFastVisible(el) {
                    if (!el || (!el.offsetWidth && !el.offsetHeight && el.getClientRects().length === 0)) return false;
                    const rect = el.getBoundingClientRect();
                    return rect.width > 12 && rect.height > 12 &&
                           rect.bottom > 0 && rect.top < window.innerHeight &&
                           rect.right > 0 && rect.left < window.innerWidth;
                }

                function getFocusableElements() {
                    const selector = 'a[href], button:not([disabled]), input:not([disabled]), [role="button"], [role="slider"], [tabindex="0"]';
                    const raw = document.querySelectorAll(selector);
                    const result = [];
                    for (let i = 0; i < raw.length; i++) {
                        const el = raw[i];
                        if (isFastVisible(el)) {
                            result.push({ el: el, rect: el.getBoundingClientRect() });
                        }
                    }
                    return result;
                }

                function setFocus(el) {
                    if (!el) return;
                    if (currentFocus && currentFocus !== el) {
                        currentFocus.classList.remove('__tv-focused');
                        if (currentFocus.tagName === 'INPUT' && window.AndroidBridge) {
                            window.AndroidBridge.hideKeyboard();
                        }
                    }
                    currentFocus = el;
                    currentFocus.classList.add('__tv-focused');
                    currentFocus.focus({ preventScroll: true });

                    // Instant scroll only if element is near screen edges (eliminates smooth-scroll lag)
                    const rect = currentFocus.getBoundingClientRect();
                    if (rect.top < 80 || rect.bottom > window.innerHeight - 80) {
                        window.scrollBy(0, rect.top - (window.innerHeight / 2) + (rect.height / 2));
                    }
                }

                function wakePlayerControls() {
                    const player = document.querySelector('[data-player="true"]');
                    if (!player) return;
                    playerControlsVisible = true;
                    player.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, clientX: 200, clientY: 200 }));
                    clearTimeout(hideControlsTimer);
                    hideControlsTimer = setTimeout(() => {
                        playerControlsVisible = false;
                        if (currentFocus && player.contains(currentFocus)) {
                            currentFocus.classList.remove('__tv-focused');
                        }
                    }, 4000);
                }

                document.addEventListener('play', function(e) {
                    if (e.target && e.target.tagName === 'VIDEO') {
                        const player = document.querySelector('[data-player="true"]');
                        if (player) {
                            player.style.cssText = 'position:fixed !important; top:0 !important; left:0 !important; width:100vw !important; height:100vh !important; z-index:99999 !important;';
                        }
                    }
                }, true);

                function moveFocus(direction) {
                    const items = getFocusableElements();
                    if (items.length === 0) return;

                    if (!currentFocus || !document.body.contains(currentFocus) || !isFastVisible(currentFocus)) {
                        setFocus(items[0].el);
                        return;
                    }

                    const curRect = currentFocus.getBoundingClientRect();
                    const curX = curRect.left + curRect.width / 2;
                    const curY = curRect.top + curRect.height / 2;

                    let bestEl = null;
                    let bestScore = Infinity;

                    for (let i = 0; i < items.length; i++) {
                        const item = items[i];
                        if (item.el === currentFocus) continue;
                        const rect = item.rect;
                        const x = rect.left + rect.width / 2;
                        const y = rect.top + rect.height / 2;

                        const dx = x - curX;
                        const dy = y - curY;

                        let valid = false;
                        let primary = 0;
                        let ortho = 0;

                        if (direction === 'RIGHT' && dx > 4) { valid = true; primary = dx; ortho = Math.abs(dy); }
                        else if (direction === 'LEFT' && dx < -4) { valid = true; primary = -dx; ortho = Math.abs(dy); }
                        else if (direction === 'DOWN' && dy > 4) { valid = true; primary = dy; ortho = Math.abs(dx); }
                        else if (direction === 'UP' && dy < -4) { valid = true; primary = -dy; ortho = Math.abs(dx); }

                        if (valid) {
                            const score = primary + (ortho * 3.0);
                            if (score < bestScore) {
                                bestScore = score;
                                bestEl = item.el;
                            }
                        }
                    }

                    if (bestEl) {
                        setFocus(bestEl);
                    } else {
                        if (direction === 'DOWN') window.scrollBy(0, 320);
                        if (direction === 'UP') window.scrollBy(0, -320);
                    }
                }

                window.__tvKey = function(key) {
                    nukeRobotOverlay();

                    const video = document.querySelector('video.ztf-video') || document.querySelector('video');
                    const player = document.querySelector('[data-player="true"]');
                    const nextEpBtn = document.querySelector('button.ctrlGlass');

                    if (key === 'PLAY_PAUSE' && video) {
                        if (video.paused) video.play(); else video.pause();
                        return;
                    }
                    if (key === 'FF' && video) {
                        const fwdBtn = document.querySelector('button[aria-label="Forward 10 seconds"]');
                        if (fwdBtn) fwdBtn.click(); else video.currentTime += 10;
                        return;
                    }
                    if (key === 'RW' && video) {
                        const backBtn = document.querySelector('button[aria-label="Back 10 seconds"]');
                        if (backBtn) backBtn.click(); else video.currentTime -= 10;
                        return;
                    }

                    if (player && video && isFastVisible(player)) {
                        const isPlayerFullscreen = player.style.position === 'fixed' || document.fullscreenElement != null;
                        if (!playerControlsVisible && isPlayerFullscreen) {
                            if (key === 'OK') {
                                if (nextEpBtn && isFastVisible(nextEpBtn)) { nextEpBtn.click(); return; }
                                if (video.paused) video.play(); else { video.pause(); wakePlayerControls(); }
                                return;
                            }
                            if (key === 'LEFT') {
                                const b = document.querySelector('button[aria-label="Back 10 seconds"]');
                                if (b) b.click(); else video.currentTime = Math.max(0, video.currentTime - 10);
                                wakePlayerControls();
                                return;
                            }
                            if (key === 'RIGHT') {
                                const f = document.querySelector('button[aria-label="Forward 10 seconds"]');
                                if (f) f.click(); else video.currentTime += 10;
                                wakePlayerControls();
                                return;
                            }
                            if (key === 'UP' || key === 'DOWN') {
                                wakePlayerControls();
                                const ctl = document.querySelector('button[aria-label="Play"], button[aria-label="Pause"], button[aria-label="Episodes"]');
                                if (ctl) setFocus(ctl);
                                return;
                            }
                        } else if (playerControlsVisible) {
                            wakePlayerControls();
                        }
                    }

                    if (key === 'UP' || key === 'DOWN' || key === 'LEFT' || key === 'RIGHT') {
                        moveFocus(key);
                    } else if (key === 'OK') {
                        if (currentFocus && isFastVisible(currentFocus)) {
                            // If it's the Search input, force open the native Android TV keyboard!
                            if (currentFocus.tagName === 'INPUT' || currentFocus.getAttribute('role') === 'searchbox') {
                                currentFocus.focus();
                                currentFocus.click();
                                if (window.AndroidBridge) window.AndroidBridge.showKeyboard();
                                return;
                            }
                            currentFocus.click();
                        } else if (video) {
                            const playVeil = document.querySelector('button[aria-label="Play"]');
                            if (playVeil) playVeil.click(); else video.play();
                        }
                    }
                };

                window.__tvBack = function() {
                    nukeRobotOverlay();
                    if (currentFocus && currentFocus.tagName === 'INPUT') {
                        currentFocus.blur();
                        if (window.AndroidBridge) window.AndroidBridge.hideKeyboard();
                        return true;
                    }
                    const player = document.querySelector('[data-player="true"]');
                    const video = document.querySelector('video.ztf-video') || document.querySelector('video');
                    if (player && player.style.position === 'fixed') {
                        if (video && !video.paused) video.pause();
                        player.style.cssText = '';
                        return true;
                    }
                    return false;
                };
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }
}
