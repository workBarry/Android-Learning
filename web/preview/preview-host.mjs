// 這個檔案只讓電腦瀏覽器能預覽；真正的 Android 分派在 NativeBridge.kt。
export function createPreviewBridge(modernSource, actions) {
  let legacySource = null;
  const fail = error => JSON.stringify({ ok: false, error });
  return {
    sendEvent(source, event, json) {
      if (source !== (legacySource ?? modernSource)) return fail('此網頁不是目前的畫面。');
      let data;
      try { data = JSON.parse(json); } catch { return fail('資料必須是 JSON。'); }
      if (!data || typeof data !== 'object' || Array.isArray(data)) return fail('資料必須是物件。');
      console.info('PREVIEW_RECEIVE', event, data);
      try {
        switch (event) {
          case 'OPEN_URL': {
            if (source !== modernSource) return fail('外開網址只能從新版網頁呼叫。');
            if (typeof data.url !== 'string') return fail('缺少網址。');
            const url = new URL(data.url);
            if (!['https:', 'http:'].includes(url.protocol)) return fail('只接受 HTTP 或 HTTPS 網址。');
            actions.openChrome(url.href);
            break;
          }
          case 'OPEN_IN_PAGE_WEBVIEW':
            if (source !== modernSource || legacySource) return fail('只有新版可以開啟一個舊版網頁。');
            if (data.path !== '/assets/web/legacy/index.html') return fail('只允許開啟本機舊版頁面。');
            if (!data.context || typeof data.context.message !== 'string') return fail('context.message 必須是文字。');
            legacySource = actions.openLegacyWeb({ message: data.context.message });
            break;
          case 'SHOW_TOAST':
            if (typeof data.message !== 'string') return fail('提示內容必須是文字。');
            actions.showToast(data.message);
            break;
          case 'LEARNING_RETURN_RESULT':
            if (!legacySource || source !== legacySource) return fail('只有舊版網頁可以回傳。');
            if (typeof data.message !== 'string') return fail('回覆必須是文字。');
            actions.returnToModern({ message: data.message });
            legacySource = null;
            break;
          default:
            return fail('不支援的指令：' + event);
        }
        return JSON.stringify({ ok: true });
      } catch (error) { return fail(error instanceof Error ? error.message : String(error)); }
    },
    back() {
      if (!legacySource) return false;
      actions.closeLegacyWeb();
      legacySource = null;
      return true;
    },
  };
}

// 單純的兩個 iframe，模擬 Android 保留新版、另外開啟舊版的行為。
if (typeof document !== 'undefined') {
  const container = document.querySelector('#pages');
  const modern = document.createElement('iframe');
  modern.src = '/assets/web/modern/index.html';
  modern.title = '新版網頁';
  container.append(modern);
  let legacy = null;
  let toastTimer;
  const setCurrent = isLegacy => {
    document.querySelector('#current-page').textContent = isLegacy ? '舊版網頁' : '新版網頁';
    document.querySelector('#back').disabled = !isLegacy;
    modern.hidden = isLegacy;
  };
  const closeLegacyWeb = () => { legacy?.remove(); legacy = null; setCurrent(false); };
  const host = createPreviewBridge(modern.contentWindow, {
    openChrome(url) {
      const popup = window.open(url, '_blank');
      if (!popup) throw new Error('瀏覽器封鎖了新分頁，請允許後重試。');
      popup.opener = null;
    },
    openLegacyWeb(context) {
      // 與 Android 相同：JSON 先交給 URLSearchParams 編碼，再由舊版讀回。
      const url = new URL('/assets/web/legacy/index.html', location.origin);
      url.searchParams.set('context', JSON.stringify(context));
      legacy = document.createElement('iframe');
      legacy.src = url.href;
      legacy.title = '舊版網頁';
      container.append(legacy);
      setCurrent(true);
      return legacy.contentWindow;
    },
    showToast(message) {
      const toast = document.querySelector('#toast');
      toast.textContent = message || '（空白訊息）';
      toast.hidden = false;
      clearTimeout(toastTimer);
      toastTimer = setTimeout(() => { toast.hidden = true; }, 2500);
    },
    closeLegacyWeb,
    returnToModern(data) {
      // 讓舊版的 sendEvent 先返回，再執行回呼與關閉。
      setTimeout(() => { modern.contentWindow.onLegacyResult?.(data); closeLegacyWeb(); }, 0);
    },
  });
  window.learningPreview = host;
  document.querySelector('#back').addEventListener('click', () => host.back());
  setCurrent(false);
}
