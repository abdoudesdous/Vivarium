package su.vivarium.app

import android.annotation.SuppressLint
import android.app.Dialog
import android.os.Bundle
import android.os.Message
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
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
        }
        fullscreenContainer = FrameLayout(this).apply { visibility = View.GONE }

        rootLayout.addView(webView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        rootLayout.addView(fullscreenContainer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(rootLayout)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

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
                if (AdBlocker.isAd(url.toString())) return true
                return true
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
                            view?.loadUrl(request?.url.toString())
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
                // Check if JS can close an active website modal/popup or exit player fullscreen first
                webView.evaluateJavascript("(window.__tvBack && window.__tvBack()) || false") { result ->
                    if (result == "true") {
                        // JS handled closing a modal or player menu
                        return@evaluateJavascript
                    }
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

    // Intercept TV Remote D-Pad & Media Keys and route to our JS TV Engine
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
            // Let standard typing work if the user is inside a text input / search bar
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

                // 1. COSMETIC AD BLOCKER + HIGH-CONTRAST TV FOCUS RING
                const style = document.createElement('style');
                style.innerHTML = `
                    [class*="ads-"], [class*="-ads"], [id*="ads-"], [id*="-ads"],
                    [class*="ad-banner"], [id*="ad-banner"], [class*="rek-"],
                    iframe[src*="ads"], iframe[src*="doubleclick"], iframe[src*="yandex"],
                    div[id^="yandex_rtb"], .adsbygoogle, .ad-container, .popup-ad {
                        display: none !important;
                        height: 0 !important;
                        visibility: hidden !important;
                        pointer-events: none !important;
                    }
                    .__tv-focused {
                        outline: 4px solid #ff0055 !important;
                        outline-offset: 3px !important;
                        box-shadow: 0 0 20px rgba(255, 0, 85, 0.85) !important;
                        transition: outline 0.1s ease, transform 0.15s ease !important;
                        z-index: 99999 !important;
                    }
                `;
                document.head.appendChild(style);

                let currentFocus = null;
                let playerControlsVisible = false;
                let hideControlsTimer = null;

                function isVisible(el) {
                    if (!el || !el.getBoundingClientRect) return false;
                    const style = window.getComputedStyle(el);
                    if (style.display === 'none' || style.visibility === 'hidden' || parseFloat(style.opacity) === 0) return false;
                    const rect = el.getBoundingClientRect();
                    return rect.width > 10 && rect.height > 10 && rect.bottom > 0 && rect.right > 0 && rect.top < window.innerHeight && rect.left < window.innerWidth;
                }

                function getFocusableElements() {
                    const player = document.querySelector('[data-player="true"]');
                    // If video is playing and controls are hidden, only focus inside player if woken up
                    const selector = [
                        'a[href]',
                        'button:not([disabled])',
                        'input:not([disabled])',
                        'select:not([disabled])',
                        '[role="button"]',
                        '[role="slider"]',
                        '[role="tab"]',
                        '[role="menuitem"]',
                        '[tabindex="0"]',
                        '[class*="card" i]',
                        '[class*="poster" i]',
                        '[class*="item" i][onclick]',
                        '[class*="episode" i]'
                    ].join(',');

                    const all = Array.from(document.querySelectorAll(selector));
                    return all.filter(el => {
                        if (!isVisible(el)) return false;
                        // Avoid focusing nested children inside already clickable buttons/links
                        if (el.parentElement && el.parentElement.closest('a[href], button') && el.tagName !== 'A' && el.tagName !== 'BUTTON') {
                            return false;
                        }
                        return true;
                    });
                }

                function setFocus(el) {
                    if (!el) return;
                    if (currentFocus && currentFocus !== el) {
                        currentFocus.classList.remove('__tv-focused');
                    }
                    currentFocus = el;
                    if (!currentFocus.hasAttribute('tabindex')) {
                        currentFocus.setAttribute('tabindex', '-1');
                    }
                    currentFocus.classList.add('__tv-focused');
                    currentFocus.focus({ preventScroll: true });
                    currentFocus.scrollIntoView({ behavior: 'smooth', block: 'center', inline: 'nearest' });
                }

                // Wake up Vivarium's custom player HUD by dispatching mouse movement
                function wakePlayerControls() {
                    const player = document.querySelector('[data-player="true"]');
                    if (!player) return;
                    playerControlsVisible = true;
                    const rect = player.getBoundingClientRect();
                    player.dispatchEvent(new MouseEvent('mousemove', {
                        bubbles: true,
                        cancelable: true,
                        clientX: rect.left + rect.width / 2,
                        clientY: rect.top + rect.height / 2
                    }));
                    clearTimeout(hideControlsTimer);
                    hideControlsTimer = setTimeout(() => {
                        playerControlsVisible = false;
                        if (currentFocus && player.contains(currentFocus)) {
                            currentFocus.classList.remove('__tv-focused');
                        }
                    }, 4000);
                }

                // Auto-expand video player to fill TV screen when playback begins
                document.addEventListener('play', function(e) {
                    if (e.target && e.target.tagName === 'VIDEO') {
                        const player = document.querySelector('[data-player="true"]');
                        if (player) {
                            player.style.position = 'fixed';
                            player.style.top = '0';
                            player.style.left = '0';
                            player.style.width = '100vw';
                            player.style.height = '100vh';
                            player.style.zIndex = '999999';
                        }
                    }
                }, true);

                // 2D Spatial Navigation Calculation
                function moveFocus(direction) {
                    const elements = getFocusableElements();
                    if (elements.length === 0) return;

                    if (!currentFocus || !document.body.contains(currentFocus) || !isVisible(currentFocus)) {
                        // Prioritize close buttons if a popup is open
                        const closeBtn = elements.find(el => {
                            const label = (el.getAttribute('aria-label') || el.innerText || '').toLowerCase();
                            return label === 'close' || label === '×' || label === 'x' || label.includes('dismiss');
                        });
                        setFocus(closeBtn || elements[0]);
                        return;
                    }

                    const curRect = currentFocus.getBoundingClientRect();
                    const curX = curRect.left + curRect.width / 2;
                    const curY = curRect.top + curRect.height / 2;

                    let bestCandidate = null;
                    let bestScore = Infinity;

                    for (const el of elements) {
                        if (el === currentFocus) continue;
                        const rect = el.getBoundingClientRect();
                        const x = rect.left + rect.width / 2;
                        const y = rect.top + rect.height / 2;

                        const dx = x - curX;
                        const dy = y - curY;

                        let isValid = false;
                        let primaryDist = 0;
                        let orthoDist = 0;

                        if (direction === 'RIGHT' && dx > 5) {
                            isValid = true; primaryDist = dx; orthoDist = Math.abs(dy);
                        } else if (direction === 'LEFT' && dx < -5) {
                            isValid = true; primaryDist = -dx; orthoDist = Math.abs(dy);
                        } else if (direction === 'DOWN' && dy > 5) {
                            isValid = true; primaryDist = dy; orthoDist = Math.abs(dx);
                        } else if (direction === 'UP' && dy < -5) {
                            isValid = true; primaryDist = -dy; orthoDist = Math.abs(dx);
                        }

                        if (isValid) {
                            // Weight orthogonal distance heavily so D-pad stays in the same row/column
                            const score = primaryDist + (orthoDist * 3.2);
                            if (score < bestScore) {
                                bestScore = score;
                                bestCandidate = el;
                            }
                        }
                    }

                    if (bestCandidate) {
                        setFocus(bestCandidate);
                    } else {
                        // Scroll page if no element found in that vertical direction
                        if (direction === 'DOWN') window.scrollBy({ top: 350, behavior: 'smooth' });
                        if (direction === 'UP') window.scrollBy({ top: -350, behavior: 'smooth' });
                    }
                }

                // Main Remote Key Handler called from Kotlin
                window.__tvKey = function(key) {
                    const video = document.querySelector('video.ztf-video') || document.querySelector('video');
                    const player = document.querySelector('[data-player="true"]');
                    const nextEpBtn = document.querySelector('button.ctrlGlass');

                    // Direct Media Keys
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

                    // If "Next episode" button is visible on screen, highlight or click it
                    if (nextEpBtn && isVisible(nextEpBtn) && key === 'OK' && currentFocus === nextEpBtn) {
                        nextEpBtn.click();
                        return;
                    }

                    // YOUTUBE-STYLE TV PLAYER MODE (When video player is active on screen)
                    if (player && video && isVisible(player)) {
                        const isPlayerFullscreen = player.style.position === 'fixed' || document.fullscreenElement != null;

                        if (!playerControlsVisible && isPlayerFullscreen) {
                            if (key === 'OK') {
                                if (nextEpBtn && isVisible(nextEpBtn)) {
                                    nextEpBtn.click();
                                    return;
                                }
                                const playVeil = document.querySelector('button[aria-label="Play"]');
                                if (video.paused) {
                                    if (playVeil && isVisible(playVeil)) playVeil.click();
                                    else video.play();
                                } else {
                                    video.pause();
                                    wakePlayerControls();
                                }
                                return;
                            }
                            if (key === 'LEFT') {
                                const back10 = document.querySelector('button[aria-label="Back 10 seconds"]');
                                if (back10) back10.click(); else video.currentTime = Math.max(0, video.currentTime - 10);
                                wakePlayerControls();
                                return;
                            }
                            if (key === 'RIGHT') {
                                const fwd10 = document.querySelector('button[aria-label="Forward 10 seconds"]');
                                if (fwd10) fwd10.click(); else video.currentTime += 10;
                                wakePlayerControls();
                                return;
                            }
                            if (key === 'UP' || key === 'DOWN') {
                                wakePlayerControls();
                                const playCtl = document.querySelector('button[aria-label="Play"], button[aria-label="Pause"], button[aria-label="Episodes"]');
                                if (playCtl) setFocus(playCtl);
                                return;
                            }
                        } else if (playerControlsVisible) {
                            wakePlayerControls(); // Reset 4s auto-hide timer while browsing player buttons
                        }
                    }

                    // STANDARD BROWSING & HUD NAVIGATION
                    if (key === 'UP' || key === 'DOWN' || key === 'LEFT' || key === 'RIGHT') {
                        moveFocus(key);
                    } else if (key === 'OK') {
                        if (currentFocus && isVisible(currentFocus)) {
                            currentFocus.click();
                            if (currentFocus.tagName === 'INPUT') {
                                currentFocus.focus();
                            }
                        } else if (video) {
                            // If nothing focused yet but video is on page, start playing and expand
                            const playVeil = document.querySelector('button[aria-label="Play"]');
                            if (playVeil) playVeil.click(); else video.play();
                        }
                    }
                };

                // Smart Back Button: Closes popups/modals or exits fullscreen video before leaving page
                window.__tvBack = function() {
                    // 1. Check for active close buttons on modals/popups
                    const closeSelectors = [
                        'button[aria-label*="close" i]',
                        'button[aria-label*="dismiss" i]',
                        '[class*="modal" i] button',
                        '[class*="popup" i] button',
                        '[class*="dialog" i] button'
                    ];
                    for (const sel of closeSelectors) {
                        const btn = document.querySelector(sel);
                        if (btn && isVisible(btn)) {
                            btn.click();
                            return true;
                        }
                    }

                    // 2. If video player is expanded to full TV screen, shrink it back so user can pick another show
                    const player = document.querySelector('[data-player="true"]');
                    const video = document.querySelector('video.ztf-video') || document.querySelector('video');
                    if (player && player.style.position === 'fixed') {
                        if (video && !video.paused) video.pause();
                        player.style.position = '';
                        player.style.top = '';
                        player.style.left = '';
                        player.style.width = '';
                        player.style.height = '';
                        player.style.zIndex = '';
                        return true;
                    }

                    return false;
                };
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }
}
