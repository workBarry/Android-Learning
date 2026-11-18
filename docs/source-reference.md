# 原專案參考與教學新增

這份小原型只抽出三專案的訊息流；沒有搬入公司 API、帳號、token、私有 SDK 或業務程式。下列路徑以原始專案資料夾為起點；來源專案不包含於本儲存庫，原專案更新後行號可能改變。

| 原始位置 | 參考重點 |
| --- | --- |
| 舊 Web android-webview.service.ts:65（`mlioss/src/main/webapp/app/shared/services/android-webview.service.ts:65`） | `MliOSSApp.sendEvent` 與 `OPEN_IN_PAGE_WEBVIEW` 的 `path`、`navTitle`、`isNcsr`；同檔也有 `OPEN_URL` |
| 新 Web android-webview.service.ts:314（`MLIOSS-NEW-APP/src/app/shared/service/android-webview.service.ts:314`） | 將資料 `JSON.stringify()` 後送到相同入口；開啟內頁方法在同檔 `:109` |
| Native OriginBoundWebViewBridge.java:73（`oss-app/oss-android/app/src/main/java/com/mli/android/oss/webview/OriginBoundWebViewBridge.java:73`） | document-start 注入、`onJsPrompt` 接收；同檔 `:39` 建立 `window.MliOSSApp`，`:92` 驗證與分派 |
| Native WebViewActivity.java:831（`oss-app/oss-android/app/src/main/java/com/mli/android/oss/WebViewActivity.java:831`） | `changeToChildView()` 準備標題、URL，開啟 child WebView 容器 |
| Native WebviewFragment.java:82（`oss-app/oss-android/app/src/main/java/com/mli/android/oss/fragments/WebviewFragment.java:82`） | 以 `evaluateJavascript()` 呼叫父 Web 的 `platform.onChildWebviewClosedFromNative()`，沒有 payload |

## 本次保留的概念

- 新 Web／舊 Web 各自是獨立應用，由 Android 控制目前顯示哪個 WebView。
- 學習版 Web 使用 `AndroidApp.sendEvent(event, JSON.stringify(data))`，保留原案的 document-start／prompt 通道。上表記載的是原專案的名稱。
- `OPEN_URL`、`OPEN_IN_PAGE_WEBVIEW` 沿用原案事件名稱。
- Android 能執行原生動作，也能用 `evaluateJavascript()` 呼叫 Web 函式。

## 本次的縮減與新增

- Android 只分成 `MainActivity` 與 `NativeBridge`；新版、舊版各有自己的 Angular component，共用 `bridge.ts` 的 `sendToAndroid(event, payload)`。
- 目的地固定為本機 legacy 頁面；沒有原案 `isNcsr` 的登入導轉，只保留此展示需要的 `path`、`navTitle`，新增 `context:{message}`。
- Chrome 外開明確指定 Chrome；`SHOW_TOAST` 是教學新增原生操作。
- `LEARNING_RETURN_RESULT` → `window.onLegacyResult({message})` 是教學新增回覆，不等於原案無參數的 child 關閉通知。
- 兩個教學 Web 統一 Angular 21。來源舊 Web 是 Angular 20.3.2，新 Web 是 Angular 21.2.7；此原型不驗證兩版 Angular 的升級相容性。
- Console／Logcat 的五個短標記用來對照程式，不另建立追蹤面板。

照 [code-flow.md](code-flow.md) 可從每個按鈕一路讀到 Native 方法與回覆。
