import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { sendToAndroid } from '../../shared/bridge';

@Component({
  selector: 'learning-legacy',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './app.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AppComponent {
  readonly receivedMessage = signal('');
  readonly reply = signal('舊網頁已收到資料');
  readonly returning = signal(false);
  readonly error = signal('');

  constructor() {
    // 1. Android 把新版的 context 編碼在網址參數中。
    const encoded = new URLSearchParams(window.location.search).get('context');
    try {
      const context: unknown = JSON.parse(encoded ?? '{}');
      // 2. 先檢查資料格式，再將傳來的文字顯示在畫面上。
      if (!context || typeof context !== 'object' || !('message' in context) || typeof context.message !== 'string') {
        throw new Error('沒有收到有效的文字資料，請從新版網頁開啟。');
      }
      console.info('LEGACY_RECEIVE', context);
      this.receivedMessage.set(context.message);
    } catch (error) {
      this.error.set(error instanceof Error ? error.message : '傳入資料格式不正確。');
    }
  }

  returnResult(): void {
    if (this.returning()) return;
    this.returning.set(true);
    this.error.set('');
    try {
      // 3. 把回覆交給 Android，原生端關閉舊網頁並回呼新版。
      sendToAndroid('LEARNING_RETURN_RESULT', { message: this.reply() });
    } catch (error) {
      // Bridge 拒絕時允許再試一次；成功送出後保持鎖定，避免連點。
      this.returning.set(false);
      this.error.set(error instanceof Error ? error.message : String(error));
    }
  }
}
