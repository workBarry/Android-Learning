# 從按鈕追到 Android，再追到回覆

先看新版的 `app.component.html`，找到按鈕呼叫的方法，再照下面流程往下讀。新版與舊版各有自己的 component，兩邊只共用簡單的 `web/shared/bridge.ts` 送出函式。

Android 的程式位於 `android/app/src/main/kotlin/dev/learning/navigation/`，只有兩個主要檔案：

- `NativeBridge.kt`：建立 Web 入口、接收訊息、解析 JSON、`when (event)` 分派。
- `MainActivity.kt`：外殼標題與返回，以及四個實際動作方法。

## 共同入口：send → receive → dispatch

```text
Web 按鈕
  → component 的按鈕方法
  → bridge.ts：sendToAndroid(event, payload)         [WEB_SEND]
  → AndroidApp.sendEvent(event, JSON.stringify(payload))
  → document-start 注入的函式呼叫 window.prompt(...)
  → NativeBridge 的 onJsPrompt 接收、解析           [NATIVE_RECEIVE]
  → when (event)
  → MainActivity 對應方法
```

`event` 決定做什麼，`payload` 提供這次操作的參數。這裡的 `sendEvent` 是來源保留的 Web 呼叫形狀；底層通道是 document-start／prompt。`NativeBridge.onJsPrompt()` 先解析外層傳輸物件，再將內層 `data` 字串還原為 `JSONObject payload`，交給 `dispatch(event, payload)`。同步回覆 `{ok:true}` 只表示已接受命令；舊頁的回覆稍後才回來。

## 1. Chrome 外開網址

```text
modern 按「用 Chrome 開啟網頁」：openChrome()
  → sendToAndroid('OPEN_URL', {url})
  → NativeBridge："OPEN_URL"
  → MainActivity.openChrome(url)
  → Android Intent 指定 Chrome
```

例如資料是 `{url:'https://example.com'}`。Android 接手外開，不把網址載入目前 WebView。Chrome 未安裝或無法啟動時，由原生端給出提示；不能把瀏覽器模擬殼另開分頁視為這項 Android 驗收。

## 2. 開啟舊頁並傳資料

```ts
sendToAndroid('OPEN_IN_PAGE_WEBVIEW', {
  path: '/assets/web/legacy/index.html',
  navTitle: '舊網頁',
  context: { message: '這段文字由新版傳入' }
});
```

```text
modern 按「開啟舊網頁並傳送文字」：openLegacy()
  → sendToAndroid(...)                             [WEB_SEND]
  → NativeBridge："OPEN_IN_PAGE_WEBVIEW"       [NATIVE_RECEIVE]
  → MainActivity.openLegacyWeb(context)
  → context JSON 放入經網址編碼的 query
  → 顯示 legacy WebView，原 modern WebView 保留
  → legacy component 讀 query、解析 context         [LEGACY_RECEIVE]
  → 舊頁顯示 message
```

Android 的本機頁面入口：

```text
https://appassets.androidplatform.net/assets/web/modern/index.html
https://appassets.androidplatform.net/assets/web/legacy/index.html
```

`openLegacyWeb(context: JSONObject)` 使用 `Uri.Builder.appendQueryParameter("context", context.toString())` 建立網址。`context` 先轉成 JSON，再交給網址參數的編碼處理，所以中文、空白、`&` 與引號不會被當成另一個參數。舊頁用 `URLSearchParams(...).get('context')` 讀取；它會處理網址解碼，再 `JSON.parse()` 還原物件。不要把 message 直接接在未編碼的網址後方，也不要重複解碼。

## 3. 顯示原生 Toast

```text
modern 按「顯示 Android 提示」：showToast()
  → sendToAndroid('SHOW_TOAST', {message})          [WEB_SEND]
  → NativeBridge："SHOW_TOAST"                [NATIVE_RECEIVE]
  → MainActivity.showToast(message)
  → Android Toast
```

這項操作用來看清楚「Web 提出要求，Native 操作原生 UI」。它不需要公司 API 或額外的資源權限。`SHOW_TOAST` 是教學新增事件。

## 4. 舊頁回覆新版

```text
legacy 按「完成並返回」：returnResult()
  → sendToAndroid('LEARNING_RETURN_RESULT', {message}) [WEB_SEND]
  → NativeBridge："LEARNING_RETURN_RESULT"     [NATIVE_RECEIVE]
  → MainActivity.returnToModern(result)            [NATIVE_RETURN]
  → modern.evaluateJavascript(...)
  → window.onLegacyResult({message})               [MODERN_RECEIVE]
  → 更新新版畫面，關閉 legacy，顯示 modern
```

新版 component 建立時先註冊 `window.onLegacyResult`，讓 Android 可以呼叫。`NativeBridge.dispatch()` 從 payload 取出 message，建立 `result: JSONObject`，再呼叫 `MainActivity.returnToModern(result: JSONObject)`。Activity 以 JSON 物件序列化結果後，放入要執行的 JavaScript，例如：

```js
window.onLegacyResult({"message":"舊頁已收到資料"});
```

回呼接收的是物件，不需要再解析一層 JSON 字串。Native 應透過 `JSONObject` 產生這段物件文字，不把使用者輸入當作 JavaScript 程式直接串接；這樣才能保留引號、換行等內容。

`onJsPrompt()` 在 UI thread 接收事件，後續在同一執行緒操作 View、WebView 與 Toast。`returnToModern()` 透過 `sender.post(...)`，先讓同步 prompt 回覆，再對新版執行 JavaScript；等 `evaluateJavascript()` 的完成回呼後才呼叫 `closeLegacyWeb()`，避免正在送訊息的舊 WebView 提早被銷毀。

`LEARNING_RETURN_RESULT` 與 `window.onLegacyResult({message})` 都是教學新增的一種簡單回覆。原案的 child 關閉通知沒有這個資料參數，見 [來源對照](source-reference.md)。

## 返回怎麼看

在舊頁按上方返回或 Android 系統返回：關閉 legacy，回到原 modern。這個操作不送出新的文字回覆。新版根畫面的系統返回交給 Android 的一般離開行為。

讀完後應能回答：Web 在哪一行送出資料？Native 在哪裡接收和分派？誰建立舊 WebView？context 如何到舊頁？最後是哪個 Native 方法呼叫新版函式？
