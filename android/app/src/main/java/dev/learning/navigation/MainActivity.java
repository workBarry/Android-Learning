package dev.learning.navigation;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/** 新版保留在底下；舊版關閉後，直接回到原本的新版網頁。 */
public final class MainActivity extends ComponentActivity {
    static final String ORIGIN = "https://appassets.androidplatform.net";
    static final String MODERN_PATH = "/assets/web/modern/index.html";
    static final String LEGACY_PATH = "/assets/web/legacy/index.html";

    private FrameLayout pages;
    private TextView currentPage;
    private WebView modernWebView;
    private WebView legacyWebView;
    private NativeBridge modernBridge;
    private NativeBridge legacyBridge;
    private WebViewAssetLoader assets;
    private boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createLayout();
        // 文件開始時就要有 Bridge；版本不支援時明確停止。
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            currentPage.setText("目前：WebView 版本不支援");
            TextView message = new TextView(this);
            message.setText("請更新 Android System WebView 後重新開啟。\n此版本不支援 DOCUMENT_START_SCRIPT，Bridge 尚未啟用。");
            message.setPadding(dp(24), dp(32), dp(24), dp(24));
            pages.addView(message);
            return;
        }
        assets = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        // 先建立新版。開啟舊版時只隱藏它，不銷毀表單與 JavaScript 狀態。
        modernWebView = createWebView(MODERN_PATH);
        modernBridge = new NativeBridge(this, modernWebView, MODERN_PATH);
        pages.addView(modernWebView, new FrameLayout.LayoutParams(-1, -1));
        modernWebView.loadUrl(ORIGIN + MODERN_PATH);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { goBack(); }
        });
    }

    private void createLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(247, 249, 252));
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(8), dp(4), dp(12), dp(4));
        toolbar.setBackgroundColor(Color.WHITE);
        Button back = new Button(this);
        back.setText("‹ 返回");
        back.setContentDescription("Android 原生返回");
        back.setOnClickListener(ignored -> goBack());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(88), dp(48)));
        currentPage = new TextView(this);
        currentPage.setText("目前：新版網頁");
        currentPage.setTextSize(16);
        currentPage.setTextColor(Color.rgb(30, 41, 59));
        currentPage.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(currentPage, new LinearLayout.LayoutParams(0, dp(48), 1));
        pages = new FrameLayout(this);
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));
        root.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1));
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                android.graphics.Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
                return insets;
            });
        } else {
            root.setFitsSystemWindows(true);
        }
        setContentView(root);
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private WebView createWebView(String expectedPath) {
        WebView webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String path = uri.getPath();
                // 只讀取 APK 內 modern / legacy 的 HTML、JS、CSS。
                if (isLocalOrigin(uri) && path != null && !path.contains("..") && !path.contains("\\")
                        && (path.startsWith("/assets/web/modern/") || path.startsWith("/assets/web/legacy/"))) {
                    return assets.shouldInterceptRequest(uri);
                }
                return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", null,
                        new ByteArrayInputStream("Only packaged pages are allowed".getBytes(StandardCharsets.UTF_8)));
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                boolean blocked = !isDocumentUrl(request.getUrl().toString(), expectedPath);
                if (blocked) showToast("這個 WebView 只能開啟目前的本機網頁");
                return blocked;
            }
        });
        return webView;
    }

    boolean isActive(WebView view) {
        return !destroyed && view == (legacyWebView != null ? legacyWebView : modernWebView);
    }

    boolean isModern(WebView view) { return view == modernWebView; }
    boolean isLegacy(WebView view) { return legacyWebView != null && view == legacyWebView; }

    void openLegacyWeb(JSONObject context) {
        if (legacyWebView != null) throw new IllegalStateException("舊版網頁已經開啟");
        legacyWebView = createWebView(LEGACY_PATH);
        legacyBridge = new NativeBridge(this, legacyWebView, LEGACY_PATH);
        // Uri.Builder 編碼 JSON，訊息中的中文、空白與特殊字元不會破壞網址。
        Uri url = Uri.parse(ORIGIN + LEGACY_PATH).buildUpon()
                .appendQueryParameter("context", context.toString()).build();
        modernWebView.setVisibility(View.GONE);
        pages.addView(legacyWebView, new FrameLayout.LayoutParams(-1, -1));
        currentPage.setText("目前：舊版網頁");
        legacyWebView.loadUrl(url.toString());
    }

    void returnToModern(JSONObject result) {
        WebView sender = legacyWebView;
        if (sender == null) throw new IllegalStateException("目前沒有舊版網頁可回傳");
        // 先讓 prompt 回覆 {ok:true}，下一輪 UI 工作才回傳並關閉發訊息的 WebView。
        sender.post(() -> {
            if (destroyed || sender != legacyWebView) return;
            Log.d("Bridge", "NATIVE_RETURN " + result);
            String script = "(function(){if(typeof window.onLegacyResult!=='function')return 'missing-callback';"
                    + "window.onLegacyResult(" + result + ");return 'received';})();";
            modernWebView.evaluateJavascript(script, value -> {
                if (destroyed || sender != legacyWebView) return;
                if ("\"missing-callback\"".equals(value)) showToast("新版網頁尚未註冊 onLegacyResult");
                closeLegacyWeb();
            });
        });
    }

    void openChrome(String url) {
        Uri uri = Uri.parse(url);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Chrome 網址必須是有效的 http 或 https 網址");
        }
        // 指定 Chrome，未安裝時顯示提示，不默默換成其他瀏覽器。
        Intent intent = new Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.chrome");
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException missingChrome) {
            showToast("裝置尚未安裝 Chrome，請先安裝後再試");
            throw new IllegalStateException("裝置尚未安裝 Chrome");
        }
    }

    void showToast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    private void goBack() {
        if (legacyWebView != null) closeLegacyWeb(); // 返回只關閉，不回傳假結果。
        else finish();
    }

    private void closeLegacyWeb() {
        if (legacyWebView == null) return;
        WebView old = legacyWebView;
        legacyWebView = null;
        destroyWebView(old, legacyBridge);
        legacyBridge = null;
        modernWebView.setVisibility(View.VISIBLE);
        currentPage.setText("目前：新版網頁");
    }

    static boolean isDocumentUrl(String value, String expectedPath) {
        if (value == null) return false;
        Uri uri = Uri.parse(value);
        return isLocalOrigin(uri) && expectedPath.equals(uri.getPath());
    }

    private static boolean isLocalOrigin(Uri uri) {
        return "https".equals(uri.getScheme()) && "appassets.androidplatform.net".equals(uri.getHost())
                && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    private void destroyWebView(WebView view, NativeBridge bridge) {
        if (view == null) return;
        view.stopLoading();
        if (bridge != null) bridge.destroy();
        pages.removeView(view);
        view.setWebChromeClient(null);
        view.setWebViewClient(new WebViewClient());
        view.destroy();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        destroyed = true;
        destroyWebView(legacyWebView, legacyBridge);
        destroyWebView(modernWebView, modernBridge);
        legacyWebView = null;
        modernWebView = null;
        super.onDestroy();
    }
}
