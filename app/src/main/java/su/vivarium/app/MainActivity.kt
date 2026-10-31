package su.vivarium.app

import android.annotation.SuppressLint
import android.app.Dialog
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Message
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
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
    private lateinit var offlineOverlay: LinearLayout
    private lateinit var wifiButton: Button
    private lateinit var retryButton: Button

    private var backPressedTime: Long = 0
    private var popupDialog: Dialog? = null
    private var isTvDevice: Boolean = false

    private val TARGET_URL = "https://vivarium.su/"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AdBlocker.init(this)
        isTvDevice = checkIsTelevision()

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0D0D12"))
        }

        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0D0D12"))
            isFocusable = true
            isFocusableInTouchMode = true
        }

        fullscreenContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }

        setupOfflineOverlay()

        rootLayout.addView(webView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        rootLayout.addView(fullscreenContainer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        rootLayout.addView(offlineOverlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(rootLayout)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

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
            defaultTextEncodingName = "utf-8"
            useWideViewPort = true
            loadWithOverviewMode = true

            if (isTvDevice) {
                // Scale up text and icons on TV without breaking responsive viewport initialization
                textZoom = 125
            }

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
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectTvEngineAndAdBlocker(view)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                // Only trigger the offline menu if the main page fails due to an actual network drop
                if (request?.isForMainFrame == true) {
                    val code = error?.errorCode ?: 0
                    if (!isNetworkConnected() ||
                        code == ERROR_HOST_LOOKUP ||
                        code == ERROR_CONNECT ||
                        code == ERROR_TIMEOUT
                    ) {
                        showOfflineMenu()
                    }
                }
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
                windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
            }
        }

        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    if (offlineOverlay.visibility == View.VISIBLE) {
                        retryConnection()
                    }
                }
            }

            override fun onLost(network: Network) {
                runOnUiThread {
                    if (!isNetworkConnected()) {
                        showOfflineMenu()
                    }
                }
            }
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (offlineOverlay.visibility == View.VISIBLE) {
                    if (backPressedTime + 2000 > System.currentTimeMillis()) {
                        finish()
                    } else {
                        Toast.makeText(this@MainActivity, "Press back again to exit", Toast.LENGTH_SHORT).show()
                    }
                    backPressedTime = System.currentTimeMillis()
                    return
                }
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

        if (isNetworkConnected()) {
            webView.loadUrl(TARGET_URL)
        } else {
            showOfflineMenu()
        }
    }

    private fun checkIsTelevision(): Boolean {
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        return uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
                packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    private fun isNetworkConnected(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetwork != null
    }

    private fun setupOfflineOverlay() {
        offlineOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0D0D12"))
            visibility = View.GONE
            setPadding(64, 64, 64, 64)
        }

        val title = TextView(this).apply {
            text = "No Internet Connection"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }

        val subtitle = TextView(this).apply {
            text = "Check your Wi-Fi connection or reconnect below."
            textSize = 15f
            setTextColor(Color.parseColor("#A0A0B0"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 56)
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        fun styleTvButton(btn: Button, isPrimary: Boolean) {
            btn.textSize = 15f
            btn.setTextColor(Color.WHITE)
            btn.isAllCaps = false
            btn.isFocusable = true
            btn.isFocusableInTouchMode = true
            btn.setPadding(48, 24, 48, 24)

            val normalBg = GradientDrawable().apply {
                cornerRadius = 20f
                setColor(if (isPrimary) Color.parseColor("#FF0055") else Color.parseColor("#252530"))
            }
            val focusedBg = GradientDrawable().apply {
                cornerRadius = 20f
                setColor(if (isPrimary) Color.parseColor("#FF2A70") else Color.parseColor("#383848"))
                setStroke(4, Color.WHITE)
            }

            btn.background = normalBg
            btn.setOnFocusChangeListener { _, hasFocus ->
                btn.background = if (hasFocus) focusedBg else normalBg
                btn.scaleX = if (hasFocus) 1.06f else 1.0f
                btn.scaleY = if (hasFocus) 1.06f else 1.0f
            }
        }

        wifiButton = Button(this).apply {
            text = "Wi-Fi Settings"
            styleTvButton(this, true)
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
        }

        retryButton = Button(this).apply {
            text = "Retry Connection"
            styleTvButton(this, false)
            setOnClickListener {
                retryConnection()
            }
        }

        val lpLeft = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = 28 }

        val lpRight = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )

        btnRow.addView(wifiButton, lpLeft)
        btnRow.addView(retryButton, lpRight)

        offlineOverlay.addView(title)
        offlineOverlay.addView(subtitle)
        offlineOverlay.addView(btnRow)
    }

    private fun showOfflineMenu() {
        runOnUiThread {
            offlineOverlay.visibility = View.VISIBLE
            wifiButton.requestFocus()
        }
    }

    private fun retryConnection() {
        if (isNetworkConnected()) {
            offlineOverlay.visibility = View.GONE
            webView.visibility = View.VISIBLE
            val currentUrl = webView.url
            if (currentUrl.isNullOrEmpty() || currentUrl == "about:blank") {
                webView.loadUrl(TARGET_URL)
            } else {
                webView.reload()
            }
            webView.requestFocus()
        } else {
            Toast.makeText(this, "Still offline. Please check Wi-Fi.", Toast.LENGTH_SHORT).show()
            wifiButton.requestFocus()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (offlineOverlay.visibility == View.VISIBLE || popupDialog?.isShowing == true) {
            return super.dispatchKeyEvent(event)
        }

        if (!isTvDevice) {
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
        val isTvFlag = if (isTvDevice) "true" else "false"
        val js = """
            (function() {
                if (window.__tvEngineInstalled) return;
                window.__tvEngineInstalled = true;
                const IS_TV = $isTvFlag;

                // 1. SAFE ARABIC SUBTITLE DECODER (Windows-1256 -> UTF-8 only when \uFFFD diamonds exist)
                try {
                    const origDecode = TextDecoder.prototype.decode;
                    TextDecoder.prototype.decode = function(input, options) {
                        const result = origDecode.call(this, input, options);
                        if (result && result.indexOf('\uFFFD') !== -1 && input) {
                            try {
                                return new TextDecoder('windows-1256').decode(input);
                            } catch (e) {}
                        }
                        return result;
                    };

                    const origRespText = Response.prototype.text;
                    Response.prototype.text = async function() {
                        const url = (this.url || '').toLowerCase();
                        // Only inspect subtitle responses or text containing replacement diamonds
                        if (url.includes('.srt') || url.includes('.vtt') || url.includes('sub') || url.includes('caption')) {
                            try {
                                const buf = await this.clone().arrayBuffer();
                                const utf8 = new TextDecoder('utf-8').decode(buf);
                                if (utf8.indexOf('\uFFFD') !== -1) {
                                    return new TextDecoder('windows-1256').decode(buf);
                                }
                                return utf8;
                            } catch (e) {}
                        }
                        return origRespText.call(this);
                    };
                } catch (e) {}

                // 2. COSMETIC AD BLOCKER + TV UI ZOOM & FOCUS RING
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
                    video::cue {
                        font-family: 'Tahoma', 'Arial', sans-serif !important;
                        unicode-bidi: plaintext !important;
                    }
                ` + (IS_TV ? `
                    /* Make player control icons and menus bigger on TV */
                    [data-player="true"] button svg {
                        transform: scale(1.28) !important;
                    }
                ` : '');
                document.head.appendChild(style);

                // 3. KEEP BURYING THE "CONFIRM YOU'RE NOT A ROBOT" QR OVERLAY
                function nukeRobotOverlay() {
                    if (!document.body) return;
                    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
                    let node;
                    while ((node = walker.nextNode())) {
                        const txt = node.nodeValue ? node.nodeValue.toLowerCase() : '';
                        if (txt.includes("confirm you're not a robot") || txt.includes("confirm you are not a robot")) {
                            let container = node.parentElement;
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
                }
                setInterval(nukeRobotOverlay, 700);

                // 4. DOM SUBTITLE FIXER (Re-checks active video TextTracks for corrupted Arabic cues)
                setInterval(function() {
                    const video = document.querySelector('video');
                    if (video && video.textTracks) {
                        for (let i = 0; i < video.textTracks.length; i++) {
                            const track = video.textTracks[i];
                            if (track.cues) {
                                for (let j = 0; j < track.cues.length; j++) {
                                    const cue = track.cues[j];
                                    if (cue.text && cue.text.indexOf('\uFFFD') !== -1 && !cue.__fixed) {
                                        cue.__fixed = true;
                                    }
                                }
                            }
                        }
                    }
                }, 1000);

                let currentFocus = null;
                let playerControlsVisible = false;
                let hideControlsTimer = null;

                function isFastVisible(el) {
                    if (!el || (!el.offsetWidth && !el.offsetHeight && el.getClientRects().length === 0)) return false;
                    const rect = el.getBoundingClientRect();
                    return rect.width > 12 && rect.height > 12 &&
                           rect.bottom > 0 && rect.top < window.innerHeight &&
                           rect.right > 0 && rect.left < window.innerWidth;
                }

                function getFocusableElements() {
                    const selector = 'a[href], button:not([disabled]), input:not([disabled]), [role="button"], [role="slider"], [role="menuitem"], [role="option"], [tabindex="0"]';
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

                if (IS_TV) {
                    document.addEventListener('play', function(e) {
                        if (e.target && e.target.tagName === 'VIDEO') {
                            const player = document.querySelector('[data-player="true"]');
                            if (player) {
                                player.style.cssText = 'position:fixed !important; top:0 !important; left:0 !important; width:100vw !important; height:100vh !important; z-index:99999 !important;';
                            }
                        }
                    }, true);
                }

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
