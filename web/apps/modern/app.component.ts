import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { LegacyResult, sendToAndroid } from '../../shared/bridge';

@Component({
  selector: 'learning-modern',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './app.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppComponent {
  readonly message = signal('你好，這是新版傳來的資料');
  readonly legacyReply = signal('尚未收到回覆');
  readonly error = signal('');

  constructor() {
    // 1. 先註冊回呼，Android 返回結果時才找得到接收函式。
    const receiveResult = (data: LegacyResult): void => {
      if (!data || typeof data.message !== 'string') {
        this.error.set('舊網頁回傳的資料格式不正確。');
        return;
      }
      console.info('MODERN_RECEIVE', data);
      this.legacyReply.set(data.message);
      this.error.set('');
    };
    window.onLegacyResult = receiveResult;
    // 2. 畫面銷毀時移除自己的回呼，避免留住舊元件。
    inject(DestroyRef).onDestroy(() => {
      if (window.onLegacyResult === receiveResult) delete window.onLegacyResult;
    });
  }

  openChrome(): void {
    this.error.set('');
    try {
      // 將網址交給 Android，由原生端開啟外部瀏覽器。
      sendToAndroid('OPEN_URL', { url: 'https://example.com' });
    } catch (error) { this.showError(error); }
  }

  openLegacy(): void {
    this.error.set('');
    try {
      // 將輸入文字放進 context，讓 Android 開啟舊網頁時帶過去。
      sendToAndroid('OPEN_IN_PAGE_WEBVIEW', {
        path: '/assets/web/legacy/index.html',
        navTitle: '舊網頁',
        context: { message: this.message() },
      });
    } catch (error) { this.showError(error); }
  }

  showToast(): void {
    this.error.set('');
    try {
      // 提示由 Android Toast 顯示，內容來自 Web 的輸入欄。
      sendToAndroid('SHOW_TOAST', { message: this.message() });
    } catch (error) { this.showError(error); }
  }

  private showError(error: unknown): void {
    this.error.set(error instanceof Error ? error.message : String(error));
  }
}
