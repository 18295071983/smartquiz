// caps.test.mjs —— 能力协商纯逻辑自测（2026-11-15，§18 能力协商体检）
//
// 为什么单独一个文件：`ws_frames.test.mjs` 验的是"真 WebSocket 上帧有没有被白名单挡掉"，
// 需要起服务器、跑插件；本文件只测 `caps.js` 的纯函数，改一行跑一行，不碰端口。
//
// 覆盖：
//   · allows / eventAllowed 的 legacy 口径（不声明 caps = 全放行）；
//   · approval / approval_gone / question / question_gone 需要 parts.approval；
//   · __force_resync 不受 caps 门禁（通道白名单放行，本次不改语义，见文档 §18）；
//   · missing / unknown / clientCaps 的字段口径；
//   · 手机端真实 caps CSV（RemoteCaps.ALL 同名）能覆盖服务端全部能力。
//
// 跑法：cd tools/dsh-plugin-smartquiz-remote && node caps.test.mjs
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  SERVER_CAPS, CAP_VERSION, eventAllowed, allows, normalizeCaps,
  missingCaps, unknownCaps, clientCaps, summarize,
} from './caps.js';

let passed = 0;
const test = (name, fn) => {
  try {
    fn();
    passed += 1;
    console.log('  ok  ' + name);
  } catch (e) {
    console.error('FAIL  ' + name + '\n      ' + (e?.stack ?? e));
    process.exitCode = 1;
  }
};

/** 手机端 RemoteCaps.ALL 的镜像：从 Java 源码解析，防止两边漂移 */
function androidCaps() {
  try {
    const src = readFileSync(new URL('../../src/main/java/com/oilquiz/app/ai/tool/RemoteCaps.java', import.meta.url), 'utf8');
    const body = src.slice(src.indexOf('String[] ALL'), src.indexOf('};', src.indexOf('String[] ALL')));
    return [...body.matchAll(/"([^"]+)"/g)].map((m) => m[1]);
  } catch {
    return [];   // 脱离仓库时跳过（不误报）
  }
}

const FULL = SERVER_CAPS.join(',');
const APPROVAL_ONLY = 'events.stream,parts.approval,parts.questions';

// ---------- 解析口径 ----------

test('normalizeCaps：逗号分隔 / 数组 / 去空 / 去重 / 大小写敏感', () => {
  assert.deepEqual(normalizeCaps('a, b,,a, c '), ['a', 'b', 'c']);
  assert.deepEqual(normalizeCaps(['a', ' b ', 'a']), ['a', 'b']);
  assert.deepEqual(normalizeCaps(''), []);
  assert.deepEqual(normalizeCaps(null), []);
  // 点号命名是大小写敏感的能力名：写错大小写就当没声明（会被 missing 抓到）
  assert.deepEqual(normalizeCaps('Parts.Approval'), ['Parts.Approval']);
  assert.equal(allows('Parts.Approval', 'parts.approval'), false);
});

test('allows：legacy（空 caps）全放行；声明了就严格按名单', () => {
  assert.equal(allows('', 'parts.approval'), true);
  assert.equal(allows(null, 'parts.approval'), true);
  assert.equal(allows(APPROVAL_ONLY, 'parts.approval'), true);
  assert.equal(allows(APPROVAL_ONLY, 'view.cards.schedule'), false);
});

// ---------- 帧过滤：审批 / 反问 / 强制重同步 ----------

test('eventAllowed：approval / approval_gone / question / question_gone 必须 parts.approval', () => {
  for (const t of ['approval', 'approval_gone', 'question', 'question_gone']) {
    assert.equal(eventAllowed(t, FULL), true, t + ' 在完整 caps 下应放行');
    assert.equal(eventAllowed(t, APPROVAL_ONLY), true, t + ' 在声明 parts.approval 后应放行');
    assert.equal(eventAllowed(t, 'events.stream,parts.questions'), false, t + ' 缺 parts.approval 应拒绝');
  }
});

test('eventAllowed：__force_resync 不受 caps 门禁（与改动前一致）', () => {
  // 它没有 EVENT_CAP 条目：通道白名单放行，任何客户端都收得到。这是**设计口径**，
  // 不是漏配：旧客户端也靠它重建时间线，加门禁会把它们卡死在旧状态。
  assert.equal(eventAllowed('__force_resync', ''), true);
  assert.equal(eventAllowed('__force_resync', 'events.stream'), true);
  assert.equal(eventAllowed('__force_resync', 'view.cards.status'), true);
});

test('eventAllowed：stream 增量 / 子代理 / 计划 / 日程按各自 cap 拒绝', () => {
  assert.equal(eventAllowed('stream/delta', 'events.stream'), false);
  assert.equal(eventAllowed('stream/delta', FULL), true);
  assert.equal(eventAllowed('subagent/activity', 'events.stream'), false);
  assert.equal(eventAllowed('plan/mode', 'events.stream'), false);
  assert.equal(eventAllowed('schedule/changed', 'events.stream'), false);
  // 未登记类型不需要额外能力（前向兼容）
  assert.equal(eventAllowed('some/future-event', 'events.stream'), true);
});

test('eventAllowed：legacy 客户端不裁任何帧', () => {
  for (const t of ['approval', 'question', 'stream/delta', 'subagent/activity']) {
    assert.equal(eventAllowed(t, ''), true);
  }
});

// ---------- 摘要字段 ----------

test('missingCaps：声明了才比较；legacy 不算"缺"', () => {
  assert.deepEqual(missingCaps(''), []);
  assert.deepEqual(missingCaps(APPROVAL_ONLY).includes('parts.approval'), false);
  assert.deepEqual(missingCaps(APPROVAL_ONLY).includes('view.cards.timeline'), true);
});

test('unknownCaps：服务端不认识的才进 unknown', () => {
  assert.deepEqual(unknownCaps('events.stream,future.cap'), ['future.cap']);
  assert.deepEqual(unknownCaps(''), []);
});

test('clientCaps：declared 是数组，字段与 /status、hello 口径一致', () => {
  const c = clientCaps(APPROVAL_ONLY);
  assert.equal(c.version, CAP_VERSION);
  assert.equal(c.legacy, false);
  assert.deepEqual(c.declared, ['events.stream', 'parts.approval', 'parts.questions']);
  assert.equal(c.declared_count, 3);
  assert.deepEqual(c.missing, missingCaps(APPROVAL_ONLY));
  assert.deepEqual(c.unknown, []);

  const legacy = clientCaps('');
  assert.equal(legacy.legacy, true);
  assert.deepEqual(legacy.declared, []);
  assert.equal(legacy.declared_count, 0);
  assert.deepEqual(legacy.missing, []);   // legacy 不裁剪
});

test('summarize：既有形状不变（declared 仍是布尔 + client 数组）', () => {
  const s = summarize(APPROVAL_ONLY);
  assert.equal(s.declared, true);
  assert.deepEqual(s.client, ['events.stream', 'parts.approval', 'parts.questions']);
  assert.deepEqual(s.server, [...SERVER_CAPS]);
  assert.equal(summarize('').declared, false);
});

// ---------- 两端契约 ----------

test('手机端 RemoteCaps.ALL 与服务端 SERVER_CAPS 同名同量（防漂移）', () => {
  const android = androidCaps();
  if (android.length === 0) {
    console.log('      （跳过：找不到 RemoteCaps.java）');
    return;
  }
  assert.deepEqual([...android].sort(), [...SERVER_CAPS].sort());
  assert.equal(clientCaps(android.join(',')).missing.length, 0);
  assert.deepEqual(clientCaps(android.join(',')).unknown, []);
});

console.log(`\n${passed} passed${process.exitCode ? '（有失败）' : ''}`);
process.exit(process.exitCode ?? 0);
