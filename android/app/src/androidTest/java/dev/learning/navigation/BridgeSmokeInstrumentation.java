package dev.learning.navigation;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 單支裝置整合測試：使用真實 DOM 與真正的 prompt Bridge，不需要外部測試依賴。 */
public final class BridgeSmokeInstrumentation extends Instrumentation {
    private static final long TIMEOUT_MS = 15_000;
    private final ArrayList<String> steps = new ArrayList<>();
    private final Bundle results = new Bundle();
    private MainActivity activity;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        try {
            runSmoke();
            results.putString("stream", "OK (1 bridge smoke test)\n" + String.join("\n", steps));
            results.putStringArrayList("steps", steps);
            finish(Activity.RESULT_OK, results);
        } catch (Throwable failure) {
            StringWriter stack = new StringWriter();
            failure.printStackTrace(new PrintWriter(stack));
            results.putString("error", failure.toString());
            results.putString("stack", stack.toString());
            results.putString("stream", "FAIL\n" + failure + "\n" + stack);
            results.putStringArrayList("steps", steps);
            Log.e("BridgeSmoke", "整合測試失敗", failure);
            finish(Activity.RESULT_CANCELED, results);
        }
    }

    private void runSmoke() throws Exception {
        // 1. 啟動真正 App，等 Angular 與原生注入的 Bridge 都準備好。
        Intent launch = new Intent(getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity = (MainActivity) startActivitySync(launch);
        WebView modern = waitForPage(MainActivity.MODERN_PATH);
        await("新版 Angular 與 Bridge 啟動", () -> truth(evaluate(modern,
                "!!document.querySelector('[data-testid=\"open-legacy\"]')"
                        + " && typeof window.onLegacyResult==='function'"
                        + " && typeof window.MliOSSApp?.sendEvent==='function'")));
        step("新版頁面、原生 Bridge 與原版 onLegacyResult 已準備完成");

        // 包一層計數器觀察結果；仍然呼叫 App 原本的 callback 更新真正畫面。
        evaluate(modern, "(function(){window.__smokeOriginalResult=window.onLegacyResult;"
                + "window.__smokeCount=0;window.onLegacyResult=function(data){"
                + "window.__smokeCount++;window.__smokeLastResult=data;"
                + "return window.__smokeOriginalResult.call(window,data);};return true;})()");

        // 2. input 元件會移除換行；這一輪走真實的輸入事件與按鈕。
        String outbound = "新版傳入：中文 & ? # + 空白";
        String reply = "舊版回覆：收到中文 & ? # +";
        input(modern, "message-input", outbound);
        click(modern, "open-legacy");
        WebView legacy = waitForPage(MainActivity.LEGACY_PATH);
        awaitText(legacy, "received-message", outbound);
        check(contextMessage(legacy).equals(outbound), "Native 的 query context 必須完整保留特殊字元");
        step("真實 DOM 開啟舊版，中文與 & ? # + 傳值正確");
        screenshot("legacy-smoke.png");

        input(legacy, "reply-input", reply);
        click(legacy, "return-result");
        await("舊版完成後關閉", () -> pageViews().size() == 1);
        check(waitForPage(MainActivity.MODERN_PATH) == modern, "返回後必須保留原本的新版 WebView 物件");
        await("原版 callback 更新新版 DOM", () -> truth(evaluate(modern,
                "document.querySelector('[data-testid=\"legacy-reply\"]')?.textContent.endsWith("
                        + JSONObject.quote(reply) + ")")));
        check(number(evaluate(modern, "window.__smokeCount")) == 1, "結果 callback 應只執行一次");
        step("舊版 DOM 回覆成功、原 callback 更新畫面、原新版 WebView 保留");

        // 3. 再開一次舊版，使用 Android 返回；回覆內容與 callback 次數都不變。
        click(modern, "open-legacy");
        waitForPage(MainActivity.LEGACY_PATH);
        runOnMainSync(() -> activity.getOnBackPressedDispatcher().onBackPressed());
        await("原生返回關閉舊版", () -> pageViews().size() == 1);
        check(number(evaluate(modern, "window.__smokeCount")) == 1, "普通返回不應送出結果 callback");
        check(String.valueOf(evaluate(modern, "window.__smokeLastResult.message")).equals(reply), "普通返回不應改變上次回覆");
        step("Android 返回只關閉舊版，上次回覆保持不變");

        // 4. 換行走 Bridge 邊界補測，避免 HTML input 本身的換行清理影響判斷。
        String multiline = "多行中文 & ? # +\n第二行";
        String multilineReply = "多行回覆 & ? # +\n已收到";
        JSONObject open = new JSONObject().put("path", MainActivity.LEGACY_PATH).put("navTitle", "舊網頁")
                .put("context", new JSONObject().put("message", multiline));
        check(bridge(modern, "OPEN_IN_PAGE_WEBVIEW", open).getBoolean("ok"), "多行開啟指令應被接受");
        legacy = waitForPage(MainActivity.LEGACY_PATH);
        awaitText(legacy, "received-message", multiline);
        check(contextMessage(legacy).equals(multiline), "query context 必須保留換行");
        sendWithoutWaiting(legacy, "LEARNING_RETURN_RESULT", new JSONObject().put("message", multilineReply));
        await("多行回覆後關閉", () -> pageViews().size() == 1);
        await("多行回覆 callback", () -> truth(evaluate(modern,
                "window.__smokeLastResult?.message===" + JSONObject.quote(multilineReply))));
        check(waitForPage(MainActivity.MODERN_PATH) == modern, "多行測試也必須保留原新版 WebView");
        step("Bridge 與 query 編碼完整保留換行及特殊字元");
        screenshot("modern-smoke.png");

        // 5. 非法 Chrome URL 直接驗證原生拒絕；不會真的啟動外部 App。
        JSONObject invalidUrl = bridge(modern, "OPEN_URL", new JSONObject().put("url", "file:///sdcard/private.html"));
        check(!invalidUrl.getBoolean("ok"), "非 HTTP(S) Chrome URL 應被原生拒絕");
        check(invalidUrl.has("error"), "拒絕時應回傳中文錯誤");
        check(bridge(modern, "SHOW_TOAST", new JSONObject().put("message", "Bridge smoke：原生提示成功")).getBoolean("ok"), "原生 Toast 指令應被接受");
        step("原生拒絕非法 Chrome URL，SHOW_TOAST 回覆成功");

        // 6. ActivityMonitor 攔截真實 Chrome Intent，驗證包名但不真正開啟 Chrome。
        AtomicReference<Intent> chromeIntent = new AtomicReference<>();
        ActivityMonitor monitor = new ActivityMonitor() {
            @Override public ActivityResult onStartActivity(Intent intent) {
                if (Intent.ACTION_VIEW.equals(intent.getAction())) {
                    chromeIntent.set(new Intent(intent));
                    return new ActivityResult(Activity.RESULT_CANCELED, null);
                }
                return null;
            }
        };
        addMonitor(monitor);
        try {
            click(modern, "open-chrome");
            await("攔截 Chrome Intent", () -> chromeIntent.get() != null);
            check("com.android.chrome".equals(chromeIntent.get().getPackage()), "外開必須明確指定 Chrome 套件");
            String scheme = chromeIntent.get().getData() == null ? null : chromeIntent.get().getData().getScheme();
            check("https".equals(scheme) || "http".equals(scheme), "Chrome Intent 應使用 HTTP(S)");
            step("DOM Chrome 按鈕送出指定 com.android.chrome 的 Intent，已由測試攔截");
        } finally {
            removeMonitor(monitor);
        }

        evaluate(modern, "(function(){window.onLegacyResult=window.__smokeOriginalResult;return true;})()");
        step("已還原 App 原始 callback；整合測試完成");
    }

    private JSONObject bridge(WebView webView, String event, JSONObject payload) throws Exception {
        Object raw = evaluate(webView, "window.MliOSSApp.sendEvent(" + JSONObject.quote(event) + ","
                + JSONObject.quote(payload.toString()) + ")");
        return new JSONObject(String.valueOf(raw));
    }

    private void sendWithoutWaiting(WebView webView, String event, JSONObject payload) {
        String script = "window.MliOSSApp.sendEvent(" + JSONObject.quote(event) + ","
                + JSONObject.quote(payload.toString()) + ")";
        // 發送後頁面會關閉，因此等待新版的真正結果，不等待已銷毀頁面的 JS callback。
        runOnMainSync(() -> webView.evaluateJavascript(script, null));
    }

    private void input(WebView webView, String testId, String value) throws Exception {
        evaluate(webView, "(function(){var input=document.querySelector('[data-testid=\"" + testId + "\"]');"
                + "if(!input)throw new Error('找不到輸入欄位');input.value=" + JSONObject.quote(value) + ";"
                + "input.dispatchEvent(new Event('input',{bubbles:true}));return true;})()");
    }

    private void click(WebView webView, String testId) throws Exception {
        String selector = "document.querySelector('[data-testid=\"" + testId + "\"]')";
        check(truth(evaluate(webView, "!!" + selector + " && !" + selector + ".disabled")), "按鈕應可操作：" + testId);
        // 使用 App 自己的 DOM click handler；結果由後續畫面與回覆斷言確認。
        runOnMainSync(() -> webView.evaluateJavascript(selector + ".click()", null));
    }

    private String contextMessage(WebView webView) throws Exception {
        return String.valueOf(evaluate(webView, "JSON.parse(new URL(location.href).searchParams.get('context')).message"));
    }

    private void awaitText(WebView webView, String testId, String value) throws Exception {
        await("等待 " + testId + " 顯示傳值", () -> truth(evaluate(webView,
                "document.querySelector('[data-testid=\"" + testId + "\"]')?.textContent===" + JSONObject.quote(value))));
    }

    private WebView waitForPage(String path) throws Exception {
        AtomicReference<WebView> found = new AtomicReference<>();
        await("等待網頁 " + path, () -> {
            runOnMainSync(() -> {
                for (WebView view : pageViewsOnUiThread()) {
                    if (view.getVisibility() == View.VISIBLE && MainActivity.isDocumentUrl(view.getUrl(), path)) found.set(view);
                }
            });
            return found.get() != null;
        });
        return found.get();
    }

    private List<WebView> pageViews() {
        AtomicReference<List<WebView>> views = new AtomicReference<>();
        runOnMainSync(() -> views.set(pageViewsOnUiThread()));
        return views.get();
    }

    private List<WebView> pageViewsOnUiThread() {
        List<WebView> views = new ArrayList<>();
        collectWebViews(activity.getWindow().getDecorView(), views);
        return views;
    }

    private void collectWebViews(View view, List<WebView> views) {
        if (view instanceof WebView) views.add((WebView) view);
        else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectWebViews(group.getChildAt(i), views);
        }
    }

    private Object evaluate(WebView webView, String script) throws Exception {
        CountDownLatch callback = new CountDownLatch(1);
        AtomicReference<String> raw = new AtomicReference<>();
        runOnMainSync(() -> webView.evaluateJavascript(script, value -> {
            raw.set(value);
            callback.countDown();
        }));
        check(callback.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), "JavaScript 回覆逾時");
        return new JSONTokener(raw.get() == null ? "null" : raw.get()).nextValue();
    }

    private void await(String label, Condition condition) throws Exception {
        long deadline = SystemClock.uptimeMillis() + TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition.ready()) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError(label + "：逾時");
    }

    private void screenshot(String name) {
        try {
            waitForIdleSync();
            // DOM 更新完成不代表畫面已繪製；先等待目前可見 WebView 的視覺狀態。
            AtomicReference<WebView> visible = new AtomicReference<>();
            runOnMainSync(() -> {
                for (WebView view : pageViewsOnUiThread()) {
                    if (view.getVisibility() == View.VISIBLE) visible.set(view);
                }
            });
            check(visible.get() != null, "找不到目前可見的 WebView");
            CountDownLatch visual = new CountDownLatch(1);
            runOnMainSync(() -> visible.get().postVisualStateCallback(SystemClock.uptimeMillis(),
                    new WebView.VisualStateCallback() {
                        @Override public void onComplete(long requestId) { visual.countDown(); }
                    }));
            check(visual.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), "等待 WebView 畫面繪製逾時");

            // 再等兩個螢幕 frame，讓顯示切換與 WebView compositor 完成提交。
            CountDownLatch frames = new CountDownLatch(1);
            runOnMainSync(() -> {
                View decor = activity.getWindow().getDecorView();
                decor.postOnAnimation(() -> decor.postOnAnimation(frames::countDown));
            });
            check(frames.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), "等待畫面 frame 逾時");
            SystemClock.sleep(200);
            Bitmap image = getUiAutomation().takeScreenshot();
            check(image != null, "無法擷取畫面");
            File folder = getTargetContext().getExternalFilesDir(null);
            check(folder != null, "無法取得測試輸出目錄");
            File file = new File(folder, name);
            try (FileOutputStream output = new FileOutputStream(file)) {
                check(image.compress(Bitmap.CompressFormat.PNG, 100, output), "無法寫入畫面");
            } finally {
                image.recycle();
            }
            results.putString(name, file.getAbsolutePath());
        } catch (Throwable screenshotFailure) {
            step("畫面擷取略過：" + screenshotFailure.getMessage());
        }
    }

    private void step(String message) {
        steps.add(message);
        Log.i("BridgeSmoke", message);
    }

    private static boolean truth(Object value) { return Boolean.TRUE.equals(value); }
    private static int number(Object value) { return ((Number) value).intValue(); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private interface Condition { boolean ready() throws Exception; }
}
