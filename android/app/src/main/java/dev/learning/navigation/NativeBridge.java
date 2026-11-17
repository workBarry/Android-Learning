package dev.learning.navigation;

import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.JsPromptResult;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;

/** 一個入口、一個 switch；把 Web 事件對應到 Android 方法。 */
final class NativeBridge {
    private static final String PREFIX = "android-learning-event:";
    private static final String SCRIPT = "(function(){"
            + "if(window.top!==window)return;"
            + "if(location.pathname!=='" + MainActivity.MODERN_PATH + "'&&location.pathname!=='" + MainActivity.LEGACY_PATH + "')return;"
            + "var api=Object.freeze({sendEvent:function(event,data){"
            + "var packet=JSON.stringify({event:String(event),data:data==null?'':"
            + "(typeof data==='string'?data:JSON.stringify(data))});"
            + "if(packet.length>8500)return JSON.stringify({ok:false,error:'訊息過長，請縮短後重試'});"
            + "return window.prompt('" + PREFIX + "'+packet,'');"
            + "}});"
            + "Object.defineProperty(window,'MliOSSApp',{value:api,writable:false,configurable:false});"
            + "})();";

    private final MainActivity activity;
    private final WebView webView;
    private final String expectedPath;
    private final ScriptHandler scriptHandler;
    private boolean active = true;

    NativeBridge(MainActivity activity, WebView webView, String expectedPath) {
        this.activity = activity;
        this.webView = webView;
        this.expectedPath = expectedPath;
        // 1. 在網頁程式執行前注入 sendEvent，只允許固定的本機 origin。
        webView.removeJavascriptInterface("MliOSSApp");
        scriptHandler = WebViewCompat.addDocumentStartJavaScript(
                webView, SCRIPT, Collections.singleton(MainActivity.ORIGIN));
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                Log.d("Bridge", message.message());
                return true;
            }

            @Override public boolean onJsPrompt(WebView view, String callerUrl, String message,
                                                String defaultValue, JsPromptResult result) {
                if (message == null || !message.startsWith(PREFIX)) {
                    result.cancel();
                    return true;
                }
                // 2. onJsPrompt 接住訊息，確認來源就是目前顯示的本機網頁。
                if (!active || !MainActivity.isDocumentUrl(callerUrl, NativeBridge.this.expectedPath)
                        || !MainActivity.isDocumentUrl(view.getUrl(), NativeBridge.this.expectedPath)
                        || !activity.isActive(view)) {
                    result.confirm(reply(false, "只有目前顯示的本機網頁可以呼叫 Bridge").toString());
                    return true;
                }
                if (message.length() > PREFIX.length() + 9000) {
                    result.confirm(reply(false, "訊息過長，請縮短後重試").toString());
                    return true;
                }
                try {
                    // 3. 外層 JSON 是傳輸格式；data 內的 JSON 是實際事件參數。
                    JSONObject packet = new JSONObject(message.substring(PREFIX.length()));
                    String event = requireString(packet, "event");
                    String data = packet.optString("data", "");
                    JSONObject payload = data.isEmpty() ? new JSONObject() : new JSONObject(data);
                    Log.d("Bridge", "NATIVE_RECEIVE " + event + " " + payload);
                    result.confirm(dispatch(event, payload).toString());
                } catch (JSONException invalidJson) {
                    result.confirm(reply(false, "事件資料必須是有效的 JSON 物件").toString());
                } catch (IllegalArgumentException | IllegalStateException error) {
                    result.confirm(reply(false, error.getMessage()).toString());
                }
                return true;
            }
        });
    }

    private JSONObject dispatch(String event, JSONObject payload) throws JSONException {
        // 4. switch 選擇 Android 操作；同步回覆 {ok:true} 或中文錯誤。
        switch (event) {
            case "OPEN_URL":
                requireModern();
                activity.openChrome(requireString(payload, "url"));
                break;
            case "OPEN_IN_PAGE_WEBVIEW":
                requireModern();
                if (!MainActivity.LEGACY_PATH.equals(requireString(payload, "path"))) {
                    return reply(false, "此原型只允許開啟內建的舊版網頁");
                }
                JSONObject context = payload.getJSONObject("context");
                requireString(context, "message");
                activity.openLegacyWeb(context);
                break;
            case "SHOW_TOAST":
                activity.showToast(requireString(payload, "message"));
                break;
            case "LEARNING_RETURN_RESULT":
                if (!activity.isLegacy(webView)) return reply(false, "只有舊版網頁可以回傳結果");
                JSONObject result = new JSONObject();
                result.put("message", requireString(payload, "message"));
                activity.returnToModern(result);
                break;
            default:
                return reply(false, "不支援的事件：" + event);
        }
        return reply(true, null);
    }

    private void requireModern() {
        if (!activity.isModern(webView)) throw new IllegalStateException("這個開啟操作只能從新版網頁呼叫");
    }

    private static String requireString(JSONObject object, String key) throws JSONException {
        Object value = object.get(key);
        if (!(value instanceof String)) throw new IllegalArgumentException(key + " 必須是文字");
        return (String) value;
    }

    private static JSONObject reply(boolean ok, String error) {
        JSONObject result = new JSONObject();
        try {
            result.put("ok", ok);
            if (!ok) result.put("error", error == null ? "操作未完成" : error);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return result;
    }

    void destroy() {
        active = false;
        scriptHandler.remove();
    }
}
