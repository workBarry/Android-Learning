package dev.learning.navigation

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 單支裝置整合測試：使用真實 DOM 與真正的 prompt Bridge，不需要外部測試依賴。 */
class BridgeSmokeInstrumentation : Instrumentation() {
    private val steps = arrayListOf<String>()
    private val results = Bundle()
    private lateinit var activity: MainActivity

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        try {
            runSmoke()
            results.putString("stream", "OK (1 bridge smoke test)\n" + steps.joinToString("\n"))
            results.putStringArrayList("steps", steps)
            finish(Activity.RESULT_OK, results)
        } catch (failure: Throwable) {
            val stack = StringWriter()
            failure.printStackTrace(PrintWriter(stack))
            results.putString("error", failure.toString())
            results.putString("stack", stack.toString())
            results.putString("stream", "FAIL\n$failure\n$stack")
            results.putStringArrayList("steps", steps)
            Log.e("BridgeSmoke", "整合測試失敗", failure)
            finish(Activity.RESULT_CANCELED, results)
        }
    }

    private fun runSmoke() {
        // 1. 啟動真正 App，等 Angular 與原生注入的 Bridge 都準備好。
        val launch = Intent(targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity = startActivitySync(launch) as MainActivity
        val modern = waitForPage(MainActivity.MODERN_PATH)
        await("新版 Angular 與 Bridge 啟動") {
            truth(evaluate(modern,
                """!!document.querySelector('[data-testid="open-legacy"]')
                    && typeof window.onLegacyResult==='function'
                    && typeof window.AndroidApp?.sendEvent==='function'""".trimIndent()))
        }
        step("新版頁面、原生 Bridge 與原版 onLegacyResult 已準備完成")

        // 包一層計數器觀察結果；仍然呼叫 App 原本的 callback 更新真正畫面。
        evaluate(modern, """(function(){
            window.__smokeOriginalResult=window.onLegacyResult;
            window.__smokeCount=0;
            window.onLegacyResult=function(data){
                window.__smokeCount++;
                window.__smokeLastResult=data;
                return window.__smokeOriginalResult.call(window,data);
            };
            return true;
        })()""".trimIndent())

        // 2. input 元件會移除換行；這一輪走真實的輸入事件與按鈕。
        val outbound = "新版傳入：中文 & ? # + 空白"
        val reply = "舊版回覆：收到中文 & ? # +"
        input(modern, "message-input", outbound)
        click(modern, "open-legacy")
        var legacy = waitForPage(MainActivity.LEGACY_PATH)
        awaitText(legacy, "received-message", outbound)
        check(contextMessage(legacy) == outbound, "Native 的 query context 必須完整保留特殊字元")
        step("真實 DOM 開啟舊版，中文與 & ? # + 傳值正確")
        screenshot("legacy-smoke.png")

        input(legacy, "reply-input", reply)
        click(legacy, "return-result")
        await("舊版完成後關閉") { pageViews().size == 1 }
        check(waitForPage(MainActivity.MODERN_PATH) === modern, "返回後必須保留原本的新版 WebView 物件")
        await("原版 callback 更新新版 DOM") {
            truth(evaluate(modern, selector("legacy-reply") + "?.textContent.endsWith(" + JSONObject.quote(reply) + ")"))
        }
        check(number(evaluate(modern, "window.__smokeCount")) == 1, "結果 callback 應只執行一次")
        step("舊版 DOM 回覆成功、原 callback 更新畫面、原新版 WebView 保留")

        // 3. 再開一次舊版，使用 Android 返回；回覆內容與 callback 次數都不變。
        click(modern, "open-legacy")
        waitForPage(MainActivity.LEGACY_PATH)
        runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
        await("原生返回關閉舊版") { pageViews().size == 1 }
        check(number(evaluate(modern, "window.__smokeCount")) == 1, "普通返回不應送出結果 callback")
        check(evaluate(modern, "window.__smokeLastResult.message").toString() == reply, "普通返回不應改變上次回覆")
        step("Android 返回只關閉舊版，上次回覆保持不變")

        // 使用 XML 工具列綁定的返回按鈕再走一次。
        click(modern, "open-legacy")
        waitForPage(MainActivity.LEGACY_PATH)
        runOnMainSync { activity.findViewById<View>(R.id.back_button).performClick() }
        await("工具列返回關閉舊版") { pageViews().size == 1 }
        check(waitForPage(MainActivity.MODERN_PATH) === modern, "工具列返回應顯示原本的新版 WebView")
        step("XML 工具列的返回按鈕正常")

        // 4. 換行走 Bridge 邊界補測，避免 HTML input 本身的換行清理影響判斷。
        val multiline = "多行中文 & ? # +\n第二行"
        val multilineReply = "多行回覆 & ? # +\n已收到"
        val open = JSONObject()
            .put("path", MainActivity.LEGACY_PATH)
            .put("navTitle", "舊網頁")
            .put("context", JSONObject().put("message", multiline))
        check(bridge(modern, "OPEN_IN_PAGE_WEBVIEW", open).getBoolean("ok"), "多行開啟指令應被接受")
        legacy = waitForPage(MainActivity.LEGACY_PATH)
        awaitText(legacy, "received-message", multiline)
        check(contextMessage(legacy) == multiline, "query context 必須保留換行")
        sendWithoutWaiting(legacy, "LEARNING_RETURN_RESULT", JSONObject().put("message", multilineReply))
        await("多行回覆後關閉") { pageViews().size == 1 }
        await("多行回覆 callback") {
            truth(evaluate(modern, "window.__smokeLastResult?.message===" + JSONObject.quote(multilineReply)))
        }
        check(waitForPage(MainActivity.MODERN_PATH) === modern, "多行測試也必須保留原新版 WebView")
        step("Bridge 與 query 編碼完整保留換行及特殊字元")
        screenshot("modern-smoke.png")

        // 5. 非法 Chrome URL 直接驗證原生拒絕；不會真的啟動外部 App。
        val invalidUrl = bridge(modern, "OPEN_URL", JSONObject().put("url", "file:///sdcard/private.html"))
        check(!invalidUrl.getBoolean("ok"), "非 HTTP(S) Chrome URL 應被原生拒絕")
        check(invalidUrl.has("error"), "拒絕時應回傳中文錯誤")
        val toast = bridge(modern, "SHOW_TOAST", JSONObject().put("message", "Bridge smoke：原生提示成功"))
        check(toast.getBoolean("ok"), "原生 Toast 指令應被接受")
        step("原生拒絕非法 Chrome URL，SHOW_TOAST 回覆成功")

        // 6. ActivityMonitor 攔截真實 Chrome Intent，驗證包名但不真正開啟 Chrome。
        val chromeIntent = AtomicReference<Intent>()
        val monitor = object : ActivityMonitor() {
            override fun onStartActivity(intent: Intent): ActivityResult? {
                if (intent.action == Intent.ACTION_VIEW) {
                    chromeIntent.set(Intent(intent))
                    return ActivityResult(Activity.RESULT_CANCELED, null)
                }
                return null
            }
        }
        addMonitor(monitor)
        try {
            click(modern, "open-chrome")
            await("攔截 Chrome Intent") { chromeIntent.get() != null }
            val captured = chromeIntent.get()
            check(captured.getPackage() == "com.android.chrome", "外開必須明確指定 Chrome 套件")
            val scheme = captured.data?.scheme
            check(scheme == "https" || scheme == "http", "Chrome Intent 應使用 HTTP(S)")
            step("DOM Chrome 按鈕送出指定 com.android.chrome 的 Intent，已由測試攔截")
        } finally {
            removeMonitor(monitor)
        }

        evaluate(modern, "(function(){window.onLegacyResult=window.__smokeOriginalResult;return true;})()")
        step("已還原 App 原始 callback；整合測試完成")
    }

    private fun bridge(webView: WebView, event: String, payload: JSONObject): JSONObject {
        return JSONObject(evaluate(webView, bridgeScript(event, payload)).toString())
    }

    private fun sendWithoutWaiting(webView: WebView, event: String, payload: JSONObject) {
        val script = bridgeScript(event, payload)
        // 發送後頁面會關閉，因此等待新版的真正結果，不等待已銷毀頁面的 JS callback。
        runOnMainSync { webView.evaluateJavascript(script, null) }
    }

    private fun bridgeScript(event: String, payload: JSONObject): String =
        "window.AndroidApp.sendEvent(" + JSONObject.quote(event) + "," + JSONObject.quote(payload.toString()) + ")"

    private fun selector(testId: String): String = """document.querySelector('[data-testid="$testId"]')"""

    private fun input(webView: WebView, testId: String, value: String) {
        evaluate(webView, """(function(){
            var input=${selector(testId)};
            if(!input)throw new Error('找不到輸入欄位');
            input.value=${JSONObject.quote(value)};
            input.dispatchEvent(new Event('input',{bubbles:true}));
            return true;
        })()""".trimIndent())
    }

    private fun click(webView: WebView, testId: String) {
        val selector = selector(testId)
        check(truth(evaluate(webView, "!!$selector && !$selector.disabled")), "按鈕應可操作：$testId")
        // 使用 App 自己的 DOM click handler；結果由後續畫面與回覆斷言確認。
        runOnMainSync { webView.evaluateJavascript("$selector.click()", null) }
    }

    private fun contextMessage(webView: WebView): String =
        evaluate(webView, "JSON.parse(new URL(location.href).searchParams.get('context')).message").toString()

    private fun awaitText(webView: WebView, testId: String, value: String) {
        await("等待 $testId 顯示傳值") {
            truth(evaluate(webView, selector(testId) + "?.textContent===" + JSONObject.quote(value)))
        }
    }

    private fun waitForPage(path: String): WebView {
        val found = AtomicReference<WebView>()
        await("等待網頁 $path") {
            runOnMainSync {
                for (view in pageViewsOnUiThread()) {
                    if (view.visibility == View.VISIBLE && MainActivity.isDocumentUrl(view.url, path)) found.set(view)
                }
            }
            found.get() != null
        }
        return found.get()
    }

    private fun pageViews(): List<WebView> {
        val views = AtomicReference<List<WebView>>()
        runOnMainSync { views.set(pageViewsOnUiThread()) }
        return views.get()
    }

    private fun pageViewsOnUiThread(): List<WebView> {
        val views = mutableListOf<WebView>()
        collectWebViews(activity.window.decorView, views)
        return views
    }

    private fun collectWebViews(view: View, views: MutableList<WebView>) {
        when (view) {
            is WebView -> views.add(view)
            is ViewGroup -> for (index in 0 until view.childCount) collectWebViews(view.getChildAt(index), views)
        }
    }

    private fun evaluate(webView: WebView, script: String): Any? {
        val callback = CountDownLatch(1)
        val raw = AtomicReference<String>()
        runOnMainSync {
            webView.evaluateJavascript(script) { value ->
                raw.set(value)
                callback.countDown()
            }
        }
        check(callback.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), "JavaScript 回覆逾時")
        return JSONTokener(raw.get() ?: "null").nextValue()
    }

    private fun await(label: String, ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (ready()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("$label：逾時")
    }

    private fun screenshot(name: String) {
        try {
            waitForIdleSync()
            // DOM 更新完成不代表畫面已繪製；先等待目前可見 WebView 的視覺狀態。
            val visible = AtomicReference<WebView>()
            runOnMainSync {
                for (view in pageViewsOnUiThread()) {
                    if (view.visibility == View.VISIBLE) visible.set(view)
                }
            }
            val webView = visible.get() ?: throw AssertionError("找不到目前可見的 WebView")
            val visual = CountDownLatch(1)
            runOnMainSync {
                webView.postVisualStateCallback(SystemClock.uptimeMillis(), object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) { visual.countDown() }
                })
            }
            check(visual.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), "等待 WebView 畫面繪製逾時")

            // 再等兩個螢幕 frame，讓顯示切換與 WebView compositor 完成提交。
            val frames = CountDownLatch(1)
            runOnMainSync {
                val decor = activity.window.decorView
                decor.postOnAnimation { decor.postOnAnimation(frames::countDown) }
            }
            check(frames.await(TIMEOUT_MS, TimeUnit.MILLISECONDS), "等待畫面 frame 逾時")
            SystemClock.sleep(200)
            val image = uiAutomation.takeScreenshot() ?: throw AssertionError("無法擷取畫面")
            val folder = targetContext.getExternalFilesDir(null) ?: throw AssertionError("無法取得測試輸出目錄")
            val file = File(folder, name)
            try {
                FileOutputStream(file).use { output ->
                    check(image.compress(Bitmap.CompressFormat.PNG, 100, output), "無法寫入畫面")
                }
            } finally {
                image.recycle()
            }
            results.putString(name, file.absolutePath)
        } catch (failure: Throwable) {
            step("畫面擷取略過：" + failure.message)
        }
    }

    private fun step(message: String) {
        steps.add(message)
        Log.i("BridgeSmoke", message)
    }

    private fun truth(value: Any?): Boolean = value == true
    private fun number(value: Any?): Int = (value as Number).toInt()

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}
