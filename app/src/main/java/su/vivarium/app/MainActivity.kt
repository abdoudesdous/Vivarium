package su.vivarium.app

import android.annotation.SuppressLint
import android.app.Dialog
import android.os.Bundle
import android.os.Message
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
    
    // Dialog to hold the Google Sign-in popup
    private var popupDialog: Dialog? = null

    private val TARGET_URL = "https://vivarium.su/"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AdBlocker.init(this)

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val rootLayout = FrameLayout(this)
        webView = WebView(this)
        fullscreenContainer = FrameLayout(this).apply { visibility = View.GONE }

        rootLayout.addView(webView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        rootLayout.addView(fullscreenContainer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(rootLayout)

        CookieManager.getInstance().setAcceptCookie(true)
        // Third-party cookies must be true for Google Auth to pass tokens back to the main site
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true) 

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            
            // Allow Popups for Auth
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            
            // Strip WebView tags to bypass Google's 403 Disallowed_UserAgent error
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

                // Allow Vivarium and Google Auth domains to load
                if (host.contains("vivarium.su")) return false
                if (host.contains("google.com") || host.contains("googleapis.com") || host.contains("gstatic.com")) return false
                
                if (AdBlocker.isAd(url.toString())) return true
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectCosmeticAdBlocker(view)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            
            // --- HANDLE GOOGLE AUTH POPUPS ---
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
                    
                    // Close the popup dialog when Google finishes the login
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

            // --- FULLSCREEN VIDEO LOGIC ---
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
                when {
                    popupDialog?.isShowing == true -> {
                        popupDialog?.dismiss()
                        popupDialog = null
                    }
                    customView != null -> webView.webChromeClient?.onHideCustomView()
                    webView.canGoBack() -> webView.goBack()
                    else -> {
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

    private fun injectCosmeticAdBlocker(view: WebView?) {
        val js = """
            (function() {
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
                `;
                document.head.appendChild(style);
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }
}
