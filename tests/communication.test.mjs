import test from 'node:test';
import assert from 'node:assert/strict';
import { sendToAndroid } from '../.test-dist/bridge.js';
import { createPreviewBridge } from '../web/preview/preview-host.mjs';

function fixture() {
  const modern = {}, legacy = {}, calls = [];
  const host = createPreviewBridge(modern, {
    openChrome: url => calls.push(['chrome', url]),
    openLegacyWeb: context => { calls.push(['legacy', context]); return legacy; },
    showToast: message => calls.push(['toast', message]),
    closeLegacyWeb: () => calls.push(['back']),
    returnToModern: data => calls.push(['reply', data]),
  });
  return { modern, legacy, host, calls, send(source, event, data) { return JSON.parse(host.sendEvent(source, event, JSON.stringify(data))); } };
}
const openData = { path: '/assets/web/legacy/index.html', context: { message: '中文 & ? # + 換行' + String.fromCharCode(10) + '測試' } };

test('新版指令帶入舊版，舊版回覆原新版，資料完整保留', () => {
  const f = fixture();
  assert.equal(f.send(f.modern, 'OPEN_IN_PAGE_WEBVIEW', openData).ok, true);
  assert.deepEqual(f.calls[0], ['legacy', openData.context]);
  assert.equal(f.send(f.legacy, 'LEARNING_RETURN_RESULT', { message: '收到：& 中文' }).ok, true);
  assert.deepEqual(f.calls[1], ['reply', { message: '收到：& 中文' }]);
  assert.equal(f.send(f.modern, 'SHOW_TOAST', { message: '回到新版' }).ok, true);
});

test('一般返回只關閉舊版，不產生假的回覆', () => {
  const f = fixture();
  f.send(f.modern, 'OPEN_IN_PAGE_WEBVIEW', openData);
  assert.equal(f.host.back(), true);
  assert.equal(f.host.back(), false);
  assert.deepEqual(f.calls, [['legacy', openData.context], ['back']]);
});

test('最多兩層，非目前畫面不能發送指令', () => {
  const f = fixture();
  f.send(f.modern, 'OPEN_IN_PAGE_WEBVIEW', openData);
  assert.equal(f.send(f.legacy, 'OPEN_IN_PAGE_WEBVIEW', openData).ok, false);
  assert.equal(f.send(f.legacy, 'OPEN_URL', { url: 'https://example.com' }).ok, false);
  assert.equal(f.send(f.modern, 'SHOW_TOAST', { message: '隱藏的父頁面' }).ok, false);
  assert.equal(f.send({}, 'SHOW_TOAST', { message: '外部來源' }).ok, false);
  assert.equal(f.calls.length, 1);
});

test('錯誤事件、目的地、型別與網址被明確拒絕', () => {
  const f = fixture();
  assert.equal(f.send(f.modern, 'OPEN_URL', { url: 'javascript:alert(1)' }).ok, false);
  assert.equal(f.send(f.modern, 'OPEN_IN_PAGE_WEBVIEW', { ...openData, path: 'https://example.com' }).ok, false);
  assert.equal(f.send(f.modern, 'OPEN_IN_PAGE_WEBVIEW', { ...openData, context: { message: 123 } }).ok, false);
  assert.equal(f.send(f.modern, 'LEARNING_RETURN_RESULT', { message: '根頁不能回傳' }).ok, false);
  assert.equal(f.send(f.modern, 'UNKNOWN', {}).ok, false);
  assert.equal(f.calls.length, 0);
});

test('外開網址與原生提示使用各自的處理分支', () => {
  const f = fixture();
  f.send(f.modern, 'OPEN_URL', { url: 'https://example.com' });
  f.send(f.modern, 'SHOW_TOAST', { message: 'Hello Android' });
  assert.deepEqual(f.calls, [['chrome', 'https://example.com/'], ['toast', 'Hello Android']]);
});

test('共用 sendEvent 只送原始事件與 JSON，且將原生拒絕顯示為錯誤', () => {
  let captured;
  globalThis.window = { MliOSSApp: { sendEvent(event, json) { captured = { event, data: JSON.parse(json) }; return '{"ok":true}'; } } };
  try {
    sendToAndroid('OPEN_IN_PAGE_WEBVIEW', openData);
    assert.deepEqual(captured, { event: 'OPEN_IN_PAGE_WEBVIEW', data: openData });
    window.MliOSSApp.sendEvent = () => '{"ok":false,"error":"Chrome 未安裝"}';
    assert.throws(() => sendToAndroid('OPEN_URL', { url: 'https://example.com' }), /Chrome 未安裝/);
  } finally { delete globalThis.window; }
});

test('query 編碼能保留中文、特殊符號與換行', () => {
  const url = new URL('https://appassets.androidplatform.net/assets/web/legacy/index.html');
  url.searchParams.set('context', JSON.stringify(openData.context));
  const decoded = JSON.parse(new URLSearchParams(url.search).get('context'));
  assert.deepEqual(decoded, openData.context);
});
