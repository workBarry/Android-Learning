# Android Learning：三個按鈕看懂 Web／Native 溝通

Android 是外殼，上方顯示返回與「目前：新版／舊版」。新版 Web 發出命令，Android 接收後執行；舊版 Web 只顯示傳入文字並回覆。使用本機頁面與展示資料。

已附 `artifacts/android-learning-debug.apk` 與建置好的 Web。直接看瀏覽器畫面可先執行 `npm run preview`；修改 Web 原始碼時再安裝依賴並重新建置。

| 新版按鈕 | 送出的事件 | Android 執行 |
| --- | --- | --- |
| 用 Chrome 開啟網頁 | `OPEN_URL`＋`{url}` | `MainActivity.openChrome()`，指定外部 Chrome |
| 開啟舊網頁並傳送文字 | `OPEN_IN_PAGE_WEBVIEW`＋`{path, navTitle, context:{message}}` | `openLegacyWeb()`，顯示舊 WebView |
| 顯示 Android 提示 | `SHOW_TOAST`＋`{message}` | `showToast()`，顯示 Android Toast |

舊頁按「完成並返回」送出 `LEARNING_RETURN_RESULT`；Android 呼叫新版 `window.onLegacyResult({message})`，再關閉舊頁。一般返回只回新版。

## 從這些位置順序讀

1. `web/apps/modern/app.component.ts`、`.html`：三個按鈕與回覆顯示。
2. `web/shared/bridge.ts` 的 `sendToAndroid(event, payload)`：`AndroidApp.sendEvent(event, JSON.stringify(payload))`。
3. `android/app/src/main/java/dev/learning/navigation/NativeBridge.java`：document-start 注入 → prompt 接收 → `switch(event)`。
4. 同目錄 `MainActivity.java`：`openChrome()`、`openLegacyWeb()`、`showToast()`、`returnToModern(JSONObject result)`。
5. `web/apps/legacy/app.component.ts`、`.html`：讀取 context、送出回覆。

Console／Logcat 的五個標記：`WEB_SEND` → `NATIVE_RECEIVE` → `LEGACY_RECEIVE` → `NATIVE_RETURN` → `MODERN_RECEIVE`。逐條解說見 [程式與資料流程](docs/code-flow.md)；原案依據見 [來源對照](docs/source-reference.md)。

## 建置與執行

在專案根目錄執行；電腦需符合 `package.json` 要求的 Node.js，Android 建置另需 JDK 與 Android SDK。

```powershell
npm install
npm test
npm run build
npm run android:build
```

`build` 產生兩個 Angular Web 並複製到 Android assets。Android Studio 開啟 `android` 資料夾，選擇模擬器後 Run；命令建置的 APK 位於 `android/app/build/outputs/apk/debug/app-debug.apk`。修改 Web 後，先重新 `npm run build` 再執行 Android。

模擬器啟動後，在專案根目錄執行 `npm run android:smoke`，會建置並安裝測試 App，檢查真實 WebView 的傳值、回呼與返回。測試會攔截 Chrome Intent 以確認指定套件；Chrome 真正開啟後的畫面仍需手動驗收。多個裝置時可設定 `ANDROID_SERIAL` 選擇。

瀏覽器輔助預覽：

```powershell
npm run preview
```

開啟 [本機預覽](http://127.0.0.1:4173)。這是瀏覽器模擬殼，可協助檢查 Web 畫面與資料；不能證明 Android 橋接、Chrome Intent、Toast 或系統返回已運作。這些操作須在 Android 模擬器／裝置驗收。

## 五分鐘展示

1. 按外開網址，確認離開 WebView 進入 Chrome；返回 App。
2. 修改傳入文字、開啟舊頁，確認標題與資料，再回覆到新版。
3. 再開舊頁，測試上方返回與 Android 系統返回。
4. 按原生提示，顯示 Toast；沿五個記錄標記指出發送、接收與執行位置。

兩個 Web 統一使用 Angular 21；`legacy` 代表舊系統角色。建置與操作結果以這一版的實際執行紀錄為準。

2026-11-17 驗證：7 項通訊測試、兩個 Angular 正式建置、Android APK 建置與 Android 模擬器整合測試通過。整合測試涵蓋真實按鈕、中文／特殊字元／換行傳值、結果回傳、原新版 WebView 保留、原生返回、Toast 指令與指定 Chrome 的 Intent。畫面見 [新版](docs/modern.png)、[舊版](docs/legacy.png)。
