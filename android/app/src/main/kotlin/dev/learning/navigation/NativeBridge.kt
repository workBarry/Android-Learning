package dev.learning.navigation

import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JsPromptResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import org.json.JSONException
import org.json.JSONObject

/** 建立網頁的 Bridge 入口，將事件交給 MainActivity 執行。 */
internal class NativeBridge(
    private val activity: MainActivity,
    private val webView: WebView,
    private val expectedPath: String
) {
    private val scriptHandler: ScriptHandler
    private var active = true

    init {
        webView.removeJavascriptInterface("AndroidApp")
        // 在網頁自己的 JavaScript 執行前，替指定的本機 origin 建立 AndroidApp。
        scriptHandler = WebViewCompat.addDocumentStartJavaScript(
            webView, SCRIPT, setOf(MainActivity.ORIGIN)
        )
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.d("Bridge", message.message())
                return true
            }

            override fun onJsPrompt(
                view: WebView,
                callerUrl: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult
            ): Boolean {
                if (message == null || !message.startsWith(PREFIX)) {
                    result.cancel()
                    return true
                }

                // 只接收目前顯示的本機網頁送出的指令。
                if (!active || !MainActivity.isDocumentUrl(callerUrl, expectedPath) ||
                    !MainActivity.isDocumentUrl(view.url, expectedPath) || !activity.isActive(view)
                ) {
                    result.confirm(reply(false, "只有目前顯示的本機網頁可以呼叫 Bridge").toString())
                    return true
                }
                if (message.length > PREFIX.length + 9000) {
                    result.confirm(reply(false, "訊息過長，請縮短後重試").toString())
                    return true
                }

                val response = try {
                    // 外層 JSON 是事件封包，data 是實際傳入的 JSON 參數。
                    val packet = JSONObject(message.removePrefix(PREFIX))
                    val event = requireString(packet, "event")
                    val data = packet.optString("data", "")
                    val payload = if (data.isEmpty()) JSONObject() else JSONObject(data)
                    Log.d("Bridge", "NATIVE_RECEIVE $event $payload")
                    dispatch(event, payload)
                } catch (_: JSONException) {
                    reply(false, "事件資料必須是有效的 JSON 物件")
                } catch (error: IllegalArgumentException) {
                    reply(false, error.message)
                } catch (error: IllegalStateException) {
                    reply(false, error.message)
                }
                result.confirm(response.toString())
                return true
            }
        }
    }

    private fun dispatch(event: String, payload: JSONObject): JSONObject {
        // event 決定 Android 要執行的操作，payload 提供這次的參數。
        when (event) {
            "OPEN_URL" -> {
                requireModern()
                activity.openChrome(requireString(payload, "url"))
            }

            "OPEN_IN_PAGE_WEBVIEW" -> {
                requireModern()
                if (requireString(payload, "path") != MainActivity.LEGACY_PATH) {
                    return reply(false, "此原型只允許開啟內建的舊版網頁")
                }
                val context = payload.getJSONObject("context")
                requireString(context, "message")
                activity.openLegacyWeb(context)
            }

            "SHOW_TOAST" -> activity.showToast(requireString(payload, "message"))

            "LEARNING_RETURN_RESULT" -> {
                if (!activity.isLegacy(webView)) return reply(false, "只有舊版網頁可以回傳結果")
                val result = JSONObject().put("message", requireString(payload, "message"))
                activity.returnToModern(result)
            }

            else -> return reply(false, "不支援的事件：$event")
        }
        return reply(true)
    }

    private fun requireModern() {
        check(activity.isModern(webView)) { "這個開啟操作只能從新版網頁呼叫" }
    }

    private fun requireString(data: JSONObject, key: String): String =
        data.get(key) as? String ?: throw IllegalArgumentException("$key 必須是文字")

    private fun reply(ok: Boolean, error: String? = null): JSONObject {
        val result = JSONObject().put("ok", ok)
        if (!ok) result.put("error", error ?: "操作未完成")
        return result
    }

    fun destroy() {
        active = false
        scriptHandler.remove()
    }

    companion object {
        private const val PREFIX = "android-learning-event:"

        // 這段 JavaScript 在 WebView 裡執行，prompt 由上面的 onJsPrompt() 接收。
        private val SCRIPT = """
            (function() {
                if (window.top !== window) return;
                if (location.pathname !== '${MainActivity.MODERN_PATH}' &&
                    location.pathname !== '${MainActivity.LEGACY_PATH}') return;
                var api = Object.freeze({
                    sendEvent: function(event, data) {
                        var packet = JSON.stringify({
                            event: String(event),
                            data: data == null ? '' :
                                (typeof data === 'string' ? data : JSON.stringify(data))
                        });
                        if (packet.length > 8500) {
                            return JSON.stringify({ok: false, error: '訊息過長，請縮短後重試'});
                        }
                        return window.prompt('$PREFIX' + packet, '');
                    }
                });
                Object.defineProperty(window, 'AndroidApp', {
                    value: api, writable: false, configurable: false
                });
            })();
        """.trimIndent()
    }
}
