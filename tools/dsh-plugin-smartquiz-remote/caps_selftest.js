// caps_selftest.js —— 能力协商契约自检（node caps_selftest.js）
//
// 为什么用独立脚本：插件跑在 DSH 宿主里，起整套 ctx 太重；而 caps.js 是纯函数，
// 用 node:assert 就能把"兼容旧客户端 / 裁剪卡片 / 事件过滤 / 未知能力"钉死。
import assert from 'node:assert/strict';
import {
  CAP_VERSION,
  SERVER_CAPS,
  normalizeCaps,
  unknownCaps,
  missingCaps,
  allows,
  filterViewCards,
  eventAllowed,
  summarize,
} from './caps.js';

let passed = 0;
const test = (name, fn) => {
  try {
    fn();
    passed += 1;
    console.log('  ok  ' + name);
  } catch (e) {
    console.error('FAIL  ' + name + '\n      ' + (e?.message ?? e));
    process.exitCode = 1;
  }
};

test('normalize: 数组/字符串/去重/去空', () => {
  assert.deepEqual(normalizeCaps(['events.stream', ' events.stream ', '', null]), ['events.stream']);
  assert.deepEqual(normalizeCaps('a, b,,a'), ['a', 'b']);
  assert.deepEqual(normalizeCaps(undefined), []);
  assert.deepEqual(normalizeCaps({ nope: true }), []);
});

test('legacy：不声明 caps = 全支持（旧 APK 不被裁）', () => {
  assert.equal(allows([], 'parts.approval'), true);
  assert.equal(allows(undefined, 'view.cards.timeline'), true);
  assert.deepEqual(missingCaps([]), []);
  assert.equal(summarize([]).legacy, true);
});

test('declared：只放行声明的能力', () => {
  const caps = ['view.cards.status', 'events.stream'];
  assert.equal(allows(caps, 'view.cards.status'), true);
  assert.equal(allows(caps, 'parts.approval'), false);
  assert.deepEqual(missingCaps(caps).includes('parts.approval'), true);
  assert.equal(summarize(caps).legacy, false);
});

test('filterViewCards：按 kind 裁剪，未知 kind 保留', () => {
  const cards = [
    { kind: 'status' }, { kind: 'timeline' }, { kind: 'approval' }, { kind: 'future_kind' },
  ];
  const kept = filterViewCards(cards, ['view.cards.status']).map((c) => c.kind);
  assert.deepEqual(kept, ['status', 'future_kind']);
  assert.equal(filterViewCards(cards, []).length, 4, 'legacy 不裁');
});

test('eventAllowed：审批事件要 parts.approval', () => {
  assert.equal(eventAllowed('assistant/message', ['events.stream']), true);
  assert.equal(eventAllowed('approval', ['events.stream']), false);
  assert.equal(eventAllowed('approval', ['parts.approval']), true);
  assert.equal(eventAllowed('question', []), true, 'legacy 照发');
});

test('unknownCaps：客户端比服务端新时不静默', () => {
  assert.deepEqual(unknownCaps(['events.stream', 'future.thing']), ['future.thing']);
});

test('summarize：结构完整且不重复', () => {
  const s = summarize(['events.stream', 'future.thing']);
  assert.equal(s.version, CAP_VERSION);
  assert.deepEqual(s.client, ['events.stream', 'future.thing']);
  assert.deepEqual(s.unknown, ['future.thing']);
  assert.equal(new Set(s.server).size, SERVER_CAPS.length);
});

console.log(`\n${passed}/${passed + (process.exitCode ? 1 : 0)} passed`);
