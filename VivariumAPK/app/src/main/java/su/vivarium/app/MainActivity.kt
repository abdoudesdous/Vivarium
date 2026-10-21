package su.vivarium.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var fullscreenContainer: FrameLayout

    private val TARGET_URL = "https://vivarium.su/"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AdBlocker.init(this)

        val rootLayout = FrameLayout(this)
        webView = WebView(this)
        fullscreenContainer = FrameLayout(this).apply { visibility = View.GONE }

        rootLayout.addView(webView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        rootLayout.addView(fullscreenContainer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(rootLayout)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false) // Kills pop-unders and new tab ads
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = userAgentString.replace("; wv", "")
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

                // Block external ad redirects, allow vivarium.su and legitimate CDNs/auth
                if (host.contains("vivarium.su")) {
                    return false
                }
                if (AdBlocker.isAd(url.toString())) {
                    return true
                }
                // Block forced external browser redirects on clicks
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectCosmeticAdBlocker(view)
            }
        }

        // Support fullscreen video playback
        webView.webChromeClient = object : WebChromeClient() {
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
            }

            override fun onHideCustomView() {
                if (customView == null) return
                fullscreenContainer.removeView(customView)
                fullscreenContainer.visibility = View.GONE
                customView = null
                customViewCallback?.onCustomViewHidden()
                webView.visibility = View.VISIBLE
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> webView.webChromeClient?.onHideCustomView()
                    webView.canGoBack() -> webView.goBack()
                    else -> finish()
                }
            }
        })

        webView.loadUrl(TARGET_URL)
    }

    private fun injectCosmeticAdBlocker(view: WebView?) {
        val js = """
            (function() {
                window.open = function() { return null; };
                const style = document.createElement('style');
                style.innerHTML = 
                    [class*="ads-"], [class*="-ads"], [id*="ads-"], [id*="-ads"],
                    [class*="ad-banner"], [id*="ad-banner"], [class*="rek-"],
                    iframe[src*="ads"], iframe[src*="doubleclick"], iframe[src*="yandex"],
                    div[id^="yandex_rtb"], .adsbygoogle, .ad-container, .popup-ad {
                        display: none !important;
                        height: 0 !important;
                        visibility: hidden !important;
                        pointer-events: none !important;
                    }
                ;
                document.head.appendChild(style);
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }
}
