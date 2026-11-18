export interface LegacyResult { message: string; }

declare global {
  interface Window {
    AndroidApp?: { sendEvent(event: string, json: string): string | null };
    onLegacyResult?: (data: LegacyResult) => void;
    learningPreview?: { sendEvent(source: Window, event: string, json: string): string | null };
  }
}

/** Web 的共用出口：只負責送指令，不管理畫面或等待佇列。 */
export function sendToAndroid(event: string, payload: Record<string, unknown>): void {
  // 步驟 1：把資料轉成字串，對應原專案的 sendEvent(event, json)。
  const json = JSON.stringify(payload);
  console.info('WEB_SEND', event, payload);

  let response: string | null;
  if (window.AndroidApp) {
    // 步驟 2：Android 已注入這個物件，接下來進入 NativeBridge.java。
    response = window.AndroidApp.sendEvent(event, json);
  } else {
    // 僅供電腦預覽：由同源外框模擬原生動作，不能驗證 Android API。
    let preview: Window['learningPreview'];
    try {
      if (window.parent !== window && window.parent.location.origin === window.location.origin) {
        preview = window.parent.learningPreview;
      }
    } catch { /* 不使用其他來源的外框。 */ }
    if (!preview) throw new Error('請從 Android App 或 npm run preview 的首頁開啟。');
    response = preview.sendEvent(window, event, json);
  }

  // 這只是「指令是否被接受」；舊版的回覆稍後由 onLegacyResult 接收。
  if (response) {
    const reply: unknown = JSON.parse(response);
    if (!reply || typeof reply !== 'object' || !('ok' in reply)) throw new Error('Android 回覆格式不正確。');
    if (reply.ok !== true) {
      throw new Error('error' in reply && typeof reply.error === 'string' ? reply.error : 'Android 無法執行這個動作。');
    }
  }
}
