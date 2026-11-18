package dev.learning.navigation

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** 新版保留在底下；舊版關閉後回到原本的新版網頁。 */
class MainActivity : ComponentActivity() {
    private lateinit var pages: FrameLayout
    private lateinit var currentPage: TextView
    private lateinit var assets: WebViewAssetLoader
    private var modernWebView: WebView? = null
    private var legacyWebView: WebView? = null
    private var modernBridge: NativeBridge? = null
    private var legacyBridge: NativeBridge? = null
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 用 XML 的 ID 取得工具列與網頁容器，並綁定返回按鈕。
        val root = findViewById<View>(R.id.main_root)
        findViewById<View>(R.id.back_button).setOnClickListener { goBack() }
        currentPage = findViewById(R.id.current_page)
        pages = findViewById(R.id.page_container)
        val modern = findViewById<WebView>(R.id.modern_web_view)
        modernWebView = modern
        applyWindowInsets(root)

        // 文件開始載入時就需要 Bridge。WebView 不支援時，在原生畫面顯示提示。
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            currentPage.setText(R.string.webview_unsupported_title)
            findViewById<View>(R.id.webview_unsupported_message).visibility = View.VISIBLE
            return
        }

        assets = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // 先設定 WebView、裝好 Bridge，才載入新版網頁。
        configureWebView(modern, MODERN_PATH)
        modernBridge = NativeBridge(this, modern, MODERN_PATH)
        modern.visibility = View.VISIBLE
        modern.loadUrl(ORIGIN + MODERN_PATH)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })
    }

    private fun applyWindowInsets(root: View) {
        // 依狀態列、導覽列和鍵盤調整畫面間距。
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val keyboard = insets.getInsets(WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
                insets
            }
        } else {
            root.fitsSystemWindows = true
        }
    }

    @Suppress("SetJavaScriptEnabled")
    private fun configureWebView(webView: WebView, expectedPath: String) {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val uri = request.url
                val path = uri.path
                // 只讀取 APK 內 modern / legacy 的 HTML、JS、CSS。
                if (isLocalOrigin(uri) && path != null && ".." !in path && '\\' !in path &&
                    (path.startsWith("/assets/web/modern/") || path.startsWith("/assets/web/legacy/"))
                ) {
                    return assets.shouldInterceptRequest(uri)
                }
                return WebResourceResponse(
                    "text/plain", "UTF-8", 403, "Blocked", null,
                    ByteArrayInputStream("Only packaged pages are allowed".toByteArray())
                )
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val blocked = !isDocumentUrl(request.url.toString(), expectedPath)
                if (blocked) showToast("這個 WebView 只能開啟目前的本機網頁")
                return blocked
            }
        }
    }

    internal fun isActive(view: WebView): Boolean =
        !destroyed && view === (legacyWebView ?: modernWebView)

    internal fun isModern(view: WebView): Boolean = view === modernWebView

    internal fun isLegacy(view: WebView): Boolean = view === legacyWebView

    internal fun openLegacyWeb(context: JSONObject) {
        check(legacyWebView == null) { "舊版網頁已經開啟" }
        val legacy = WebView(this)
        legacyWebView = legacy
        configureWebView(legacy, LEGACY_PATH)
        legacyBridge = NativeBridge(this, legacy, LEGACY_PATH)

        // Uri.Builder 編碼 context，舊頁從網址參數取出 JSON。
        val url = Uri.parse(ORIGIN + LEGACY_PATH).buildUpon()
            .appendQueryParameter("context", context.toString())
            .build()
        modernWebView?.visibility = View.GONE
        pages.addView(legacy, FrameLayout.LayoutParams(-1, -1))
        currentPage.setText(R.string.current_page_legacy)
        legacy.loadUrl(url.toString())
    }

    internal fun returnToModern(result: JSONObject) {
        val sender = checkNotNull(legacyWebView) { "目前沒有舊版網頁可回傳" }
        // 先讓 prompt 回覆舊頁，再把結果交給新版的 JavaScript。
        sender.post {
            if (destroyed || sender !== legacyWebView) return@post
            Log.d("Bridge", "NATIVE_RETURN $result")
            val script = """
                (function() {
                    if (typeof window.onLegacyResult !== 'function') return 'missing-callback';
                    window.onLegacyResult($result);
                    return 'received';
                })();
            """.trimIndent()
            modernWebView?.evaluateJavascript(script) { value ->
                if (destroyed || sender !== legacyWebView) return@evaluateJavascript
                if (value == "\"missing-callback\"") showToast("新版網頁尚未註冊 onLegacyResult")
                closeLegacyWeb()
            }
        }
    }

    internal fun openChrome(url: String) {
        val uri = Uri.parse(url)
        val supportedScheme = uri.scheme.equals("https", ignoreCase = true) ||
            uri.scheme.equals("http", ignoreCase = true)
        require(supportedScheme && uri.host != null && uri.userInfo == null) {
            "Chrome 網址必須是有效的 http 或 https 網址"
        }

        // 指定 Chrome，由 Android 開啟外部瀏覽器。
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.chrome")
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            showToast("裝置尚未安裝 Chrome，請先安裝後再試")
            throw IllegalStateException("裝置尚未安裝 Chrome")
        }
    }

    internal fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun goBack() {
        if (legacyWebView != null) closeLegacyWeb() // 一般返回只關閉舊頁。
        else finish()
    }

    private fun closeLegacyWeb() {
        val old = legacyWebView ?: return
        legacyWebView = null
        destroyWebView(old, legacyBridge)
        legacyBridge = null
        modernWebView?.visibility = View.VISIBLE
        currentPage.setText(R.string.current_page_modern)
    }

    private fun destroyWebView(view: WebView?, bridge: NativeBridge?) {
        if (view == null) return
        view.stopLoading()
        bridge?.destroy()
        pages.removeView(view)
        view.webChromeClient = null
        view.webViewClient = WebViewClient()
        view.destroy()
    }

    override fun onDestroy() {
        destroyed = true
        destroyWebView(legacyWebView, legacyBridge)
        destroyWebView(modernWebView, modernBridge)
        legacyWebView = null
        modernWebView = null
        legacyBridge = null
        modernBridge = null
        super.onDestroy()
    }

    companion object {
        internal const val ORIGIN = "https://appassets.androidplatform.net"
        internal const val MODERN_PATH = "/assets/web/modern/index.html"
        internal const val LEGACY_PATH = "/assets/web/legacy/index.html"

        internal fun isDocumentUrl(value: String?, expectedPath: String): Boolean {
            if (value == null) return false
            val uri = Uri.parse(value)
            return isLocalOrigin(uri) && expectedPath == uri.path
        }

        private fun isLocalOrigin(uri: Uri): Boolean =
            uri.scheme == "https" && uri.host == "appassets.androidplatform.net" &&
                uri.userInfo == null && (uri.port == -1 || uri.port == 443)
    }
}
