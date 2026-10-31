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
import android.view.View
import android.view.ViewGroup
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

        // Initialize AdBlocker in the background to prevent startup lag
        Thread { AdBlocker.init(applicationContext) }.start()

        isTvDevice = checkIsTelevision()

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        val rootLayout = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0D0D12")) }
        
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

        rootLayout.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        rootLayout.addView(fullscreenContainer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        rootLayout.addView(offlineOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

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
            defaultTextEncodingName = "utf-8"

// 1. Get the REAL Android User-Agent and clean it so Cloudflare trusts the fingerprint
            val baseUa = userAgentString.replace("; wv", "").replace("Version/4.0 ", "")

            if (isTvDevice) {
                // 2. Append TV flags to the real Android UA. 
                // Vivarium sees "SMART-TV" and gives the TV layout; Cloudflare sees a valid Android device and auto-passes.
                userAgentString = "$baseUa SMART-TV Android TV"
            } else {
                userAgentString = baseUa
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                if (AdBlocker.isAd(url)) return AdBlocker.createEmptyResource()
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                val host = url.host?.lowercase() ?: return true
                if (host.contains("vivarium.su")) return false
                if (host.contains("google.com") || host.contains("googleapis.com") || host.contains("gstatic.com")) return false
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectArabicSubtitleFixer(view)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    val code = error?.errorCode ?: 0
                    if (!isNetworkConnected() || code == ERROR_HOST_LOOKUP || code == ERROR_CONNECT || code == ERROR_TIMEOUT) {
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
                            val host = request?.url?.host?.lowercase() ?: return true
                            if (host.contains("google.com") || host.contains("googleapis.com") || host.contains("vivarium.su")) {
                                view?.loadUrl(request.url.toString())
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
                runOnUiThread { if (offlineOverlay.visibility == View.VISIBLE) retryConnection() }
            }
            override fun onLost(network: Network) {
                runOnUiThread { if (!isNetworkConnected()) showOfflineMenu() }
            }
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (offlineOverlay.visibility == View.VISIBLE) {
                    handleExitToast()
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
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    handleExitToast()
                }
            }
        })

        if (isNetworkConnected()) webView.loadUrl(TARGET_URL) else showOfflineMenu()
    }

    private fun handleExitToast() {
        if (backPressedTime + 2000 > System.currentTimeMillis()) {
            finish()
        } else {
            Toast.makeText(this, "Press back again to exit", Toast.LENGTH_SHORT).show()
        }
        backPressedTime = System.currentTimeMillis()
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

        fun styleBtn(btn: Button, isPrimary: Boolean) {
            btn.textSize = 15f
            btn.setTextColor(Color.WHITE)
            btn.isAllCaps = false
            btn.isFocusable = true
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
            styleBtn(this, true)
            setOnClickListener {
                try { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } 
                catch (e: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }
        }

        retryButton = Button(this).apply {
            text = "Retry Connection"
            styleBtn(this, false)
            setOnClickListener { retryConnection() }
        }

        btnRow.addView(wifiButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = 28 })
        btnRow.addView(retryButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        offlineOverlay.addView(title)
        offlineOverlay.addView(subtitle)
        offlineOverlay.addView(btnRow)
    }

    private fun showOfflineMenu() {
        runOnUiThread {
            webView.visibility = View.GONE
            offlineOverlay.visibility = View.VISIBLE
            wifiButton.requestFocus()
        }
    }

    private fun retryConnection() {
        if (isNetworkConnected()) {
            offlineOverlay.visibility = View.GONE
            webView.visibility = View.VISIBLE
            val currentUrl = webView.url
            if (currentUrl.isNullOrEmpty() || currentUrl == "about:blank") webView.loadUrl(TARGET_URL) else webView.reload()
            webView.requestFocus()
        } else {
            Toast.makeText(this, "Still offline. Please check Wi-Fi.", Toast.LENGTH_SHORT).show()
            wifiButton.requestFocus()
        }
    }

    private fun injectArabicSubtitleFixer(view: WebView?) {
        val js = """
            (function() {
                if (window.__arabicSubFixInstalled) return;
                window.__arabicSubFixInstalled = true;
                
                try {
                    const origRespText = Response.prototype.text;
                    Response.prototype.text = async function() {
                        const url = (this.url || '').toLowerCase();
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
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }
}
