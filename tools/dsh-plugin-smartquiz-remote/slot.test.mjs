// slot.test.mjs —— 插槽 rev + TTL + 补发自测（2026-11-15，§20 DOCK-REV/TTL）
//
// 【验证方式 · 报告里要照抄】
//   用假 ctx 把插件 `apply` 起来，LAN 服务器监听随机端口（不碰正在运行的 8218），
//   全部从**生产入口**注入：
//     · `POST /v2/action {action:'ui.slot'}` → 真实 `ui/slot` 帧（与 phone_ui 同一条 pushUiSlot）
//   断言：
//     1) 同一 id 连推两次：rev 严格递增（1 → 2），响应里带 rev；
//     2) 持续态 id（jobs）默认带 ttl_ms=30000，稳定态 id（goal）不带；
//     3) 显式 ttl_ms=3000 原样透传；
//     4) 空帧/remove:true 下发的移除帧 rev 比上一条大，且标记 remove；
//     5) 新订阅（新连一条 /ws/ui）能收到最近一帧，rev 沿用不回退；
//     6) 已过期（ttl 到点）的瞬时帧不再补发，避免生产者失联后复活残留。
//   2026-11-15（§22 DECK-POLISH）追加：
//     7) 命名档位：只给 level（p0..p5）也补出同名数值 priority，p0=0 不被默认值吞掉，
//        两者都没给 → p4(30)，旧 priority 数值原样保留；
//     8) `GET /v2/slots`：快照结构（id/rev/ttl 剩余/priority/level/title/detail/kind/count）、
//        按 priority 升序、按会话隔离、移除帧与过期帧不复活、最多 32 条。
//
// 【边界】
//   - DSH 宿主没跑：pushUiSlot 是**直接调用插件生产函数**，帧格式真实；App 侧 reb 丢弃/
//     TTL 清理由 `SlotLedgerTest` / `DockSlotBusTest` 覆盖。
//   - 进程内会留下插件自己的定时器，跑完 process.exit。
//
// 跑法：cd tools/dsh-plugin-smartquiz-remote && node slot.test.mjs
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { WebSocket } from 'ws';
import { apply } from './index.js';

const TOKEN = 'slot-test-token';
const SID = 'root-1';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const PORT = await new Promise((resolve, reject) => {
  const s = createServer();
  s.on('error', reject);
  s.listen(0, '127.0.0.1', () => {
    const p = s.address().port;
    s.close(() => resolve(p));
  });
});

// ---------- 假 ctx（与 ws_frames.test.mjs 同一套最小面） ----------
const routes = new Map();
const handlers = new Map();
const sessions = new Map([[SID, { id: SID, seq: 1, eventAt: () => undefined }]]);
let phoneUiDef = null;

const ctx = {
  effect(fn) {
    try {
      const dispose = fn();
      return () => { try { dispose?.(); } catch { /* ignore */ } };
    } catch {
      return () => {};
    }
  },
  get() {
    return undefined;
  },
  webServer: {
    port: 0,
    register({ path, handler }) {
      routes.set(path, handler);
      return () => routes.delete(path);
    },
    registerUpgrade() {
      return () => {};
    },
  },
  on(name, fn) {
    const arr = handlers.get(name) ?? [];
    arr.push(fn);
    handlers.set(name, arr);
    return () => {
      const i = arr.indexOf(fn);
      if (i >= 0) arr.splice(i, 1);
    };
  },
  agents: { get() { return undefined; }, list() { return []; } },
  sessions: { get(id) { return sessions.get(id); } },
  tools: {
    register(def) {
      if (def && def.name === 'phone_ui') phoneUiDef = def;
      return () => {};
    },
  },
  logger: { info() {}, warn() {}, debug() {} },
};

apply(ctx, {
  token: TOKEN,
  lanPort: PORT,
  lanEnabled: true,
  extraLanPorts: '',
  cliMode: false,
});

// ---------- 测试工具 ----------
let passed = 0;
const test = async (name, fn) => {
  try {
    await fn();
    passed += 1;
    console.log('  ok  ' + name);
  } catch (e) {
    console.error('FAIL  ' + name + '\n      ' + (e?.stack ?? e));
    process.exitCode = 1;
  }
};

async function post(path, body) {
  const res = await fetch(`http://127.0.0.1:${PORT}${path}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: 'Bearer ' + TOKEN },
    body: JSON.stringify(body),
  });
  let json = null;
  try { json = await res.json(); } catch { /* 非 JSON */ }
  return { status: res.status, json };
}

async function pushSlot(id, title, extra = {}) {
  const r = await post('/v2/action', {
    action: 'ui.slot', id, title, detail: '', caps: 'cmd.ui', ...extra,
  });
  assert.equal(r.status, 200, 'ui.slot 应 200：' + JSON.stringify(r.json));
  return r.json.slot;
}

async function waitFor(predicate, label, timeout = 3000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const got = predicate();
    if (got) return got;
    await sleep(20);
  }
  throw new Error('等不到 ' + label);
}

async function connect(channel, caps) {
  let lastErr = null;
  for (let attempt = 0; attempt < 40; attempt += 1) {
    try {
      const ws = new WebSocket(
        `ws://127.0.0.1:${PORT}${channel}?session_id=${SID}`
        + `&caps=${encodeURIComponent(caps)}&token=${TOKEN}`,
      );
      const frames = [];
      ws.on('message', (raw) => {
        try { frames.push(JSON.parse(String(raw))); } catch { /* 坏帧忽略 */ }
      });
      await new Promise((resolve, reject) => {
        const t = setTimeout(() => reject(new Error('WS open timeout')), 1200);
        ws.once('open', () => { clearTimeout(t); resolve(); });
        ws.once('error', (e) => { clearTimeout(t); reject(e); });
      });
      await waitFor(() => frames.find((f) => f.type === 'hello'), channel + ' hello', 3000);
      return { ws, frames };
    } catch (e) {
      lastErr = e;
      await sleep(100);
    }
  }
  throw lastErr ?? new Error('WS 连不上 ' + channel);
}

const slotFrames = (conn, id) => conn.frames.filter((f) => f.type === 'ui/slot' && f.data?.id === id);

// ---------- 自测 ----------
let ui = null;

await test('同 id 连推：rev 严格递增，绝不复用', async () => {
  const a = await pushSlot('rev-slot', '第一条');
  const b = await pushSlot('rev-slot', '第二条');
  assert.ok(Number(a.rev) > 0, '帧应带 rev');
  assert.equal(Number(b.rev), Number(a.rev) + 1, 'rev 应按 id 单调 +1');
  assert.equal(a.title, '第一条');
  assert.equal(b.title, '第二条');
  assert.ok(Number(a.epoch) > 0, '帧应带插件世代 epoch');
  assert.equal(b.epoch, a.epoch, '同一插件进程内 epoch 稳定');

  ui = await connect('/ws/ui', 'cmd.ui');
  const got = await waitFor(() => slotFrames(ui, 'rev-slot').at(-1), 'rev-slot 帧');
  assert.equal(got.data.title, '第二条');
  assert.equal(got.data.rev, b.rev, '实时帧与 HTTP 响应同一 rev');
  assert.equal(got.data.epoch, b.epoch, '实时帧带同一 epoch');
});

await test('持续态默认 ttl_ms=30000，稳定态不带 ttl', async () => {
  const jobs = await pushSlot('jobs', '任务');
  assert.equal(jobs.ttl_ms, 30000, 'jobs 属于持续态，应默认 30s');
  const goal = await pushSlot('goal', '目标');
  assert.equal(goal.ttl_ms, undefined, 'goal 是稳定态，靠显式 remove');
});

await test('显式 ttl_ms 原样透传', async () => {
  const slot = await pushSlot('ttl-slot', '3 秒后消失', { ttl_ms: 3000 });
  assert.equal(slot.ttl_ms, 3000);
  const frame = await waitFor(() => slotFrames(ui, 'ttl-slot').at(-1), 'ttl-slot 帧');
  assert.equal(frame.data.ttl_ms, 3000);
});

await test('空帧 = remove，移除帧 rev 继续递增且带 remove', async () => {
  const add = await pushSlot('gone-slot', '在');
  const removed = await pushSlot('gone-slot', '', { detail: '' });
  assert.ok(Number(removed.rev) > Number(add.rev), '移除也要占一个新 rev');
  const frame = await waitFor(
    () => slotFrames(ui, 'gone-slot').find((f) => f.data.remove === true),
    'gone-slot 的 remove 帧',
  );
  assert.equal(frame.data.rev, removed.rev);
  assert.ok(Number(frame.data.epoch) > 0, '移除帧也要带 epoch');
});

await test('新订阅补发最近一帧：rev 沿用不回退', async () => {
  const pushed = await pushSlot('replay-slot', '补发这条');
  const second = await connect('/ws/ui', 'cmd.ui');
  try {
    const replayed = await waitFor(
      () => slotFrames(second, 'replay-slot')[0],
      '新连接上的 replay-slot 补发',
    );
    assert.equal(replayed.data.title, '补发这条');
    assert.equal(replayed.data.rev, pushed.rev, '补发沿用最近 rev');
    assert.equal(replayed.data.epoch, pushed.epoch, '补发沿用同一 epoch');
  } finally {
    try { second.ws.close(); } catch { /* ignore */ }
  }
});

await test('TTL 到点后补发跳过：生产者失联不复活残留', async () => {
  await pushSlot('short-ttl', '只活 60ms', { ttl_ms: 60 });
  await sleep(120);
  const third = await connect('/ws/ui', 'cmd.ui');
  try {
    await sleep(300);
    assert.equal(
      slotFrames(third, 'short-ttl').length,
      0,
      '过期瞬时帧不该补发给新订阅',
    );
  } finally {
    try { third.ws.close(); } catch { /* ignore */ }
  }
});

await test('phone_ui 工具：slot 推送带 rev/ttl', async () => {
  assert.ok(phoneUiDef, '插件应注册 phone_ui 工具');
  const text = await phoneUiDef.execute({ kind: 'slot', id: 'tool-slot', title: '工具推的', ttl_ms: 2000 });
  assert.match(text, /已推送到手机插槽 tool-slot/);
  const frame = await waitFor(() => slotFrames(ui, 'tool-slot').at(-1), 'tool-slot 帧');
  assert.ok(Number(frame.data.rev) > 0, '工具推送也必须带 rev');
  assert.equal(frame.data.title, '工具推的');
  assert.equal(frame.data.ttl_ms, 2000);
});

await test('phone_ui 工具：空 title/detail = 移除（rev 继续递增）', async () => {
  const add = await pushSlot('tool-rm', '待移除');
  const addFrame = await waitFor(() => slotFrames(ui, 'tool-rm').at(-1), 'tool-rm 添加帧');

  const text = await phoneUiDef.execute({ kind: 'slot', id: 'tool-rm' });
  assert.match(text, /已移除手机插槽 tool-rm/);

  const rmFrame = await waitFor(
    () => slotFrames(ui, 'tool-rm').find((f) => f.data.remove === true),
    'tool-rm 移除帧',
  );
  assert.ok(Number(rmFrame.data.rev) > Number(addFrame.data.rev), '移除要占一个新 rev');
  assert.ok(Number(rmFrame.data.rev) > Number(add.rev));
});

await test('phone_ui 工具：空帧不给 id 直接拒绝，避免误清默认槽', async () => {
  const text = await phoneUiDef.execute({ kind: 'slot' });
  assert.match(text, /移除插槽需要 id/);
});

// ===== §22（2026-11-15）：命名优先级档位 + /v2/slots 快照 =====

/** GET /v2/slots（快照路由） */
async function getSlots(sid) {
  const res = await fetch(
    `http://127.0.0.1:${PORT}/v2/slots?session_id=${encodeURIComponent(sid)}`,
    { headers: { authorization: 'Bearer ' + TOKEN } },
  );
  let json = null;
  try { json = await res.json(); } catch { /* 非 JSON */ }
  return { status: res.status, json };
}

await test('档位表：只给 level 也补出同名数值 priority', async () => {
  const p2 = await pushSlot('tier-p2', '权限档', { level: 'p2' });
  assert.equal(p2.priority, 10, 'level=p2 应换算成 priority=10');
  assert.equal(p2.level, 'p2');

  const p0 = await pushSlot('tier-p0', '打断式', { level: 'p0' });
  assert.equal(p0.priority, 0, 'p0=0 不能被默认值吞成 50');

  const dflt = await pushSlot('tier-default', '默认档');
  assert.equal(dflt.priority, 30, '两者都没给 → p4(30)');
  assert.equal(dflt.level, 'p4', '归一化后帧里带 level');

  const legacy = await pushSlot('tier-legacy', '旧数值', { priority: 55 });
  assert.equal(legacy.priority, 55, '旧帧的 priority 数值原样保留');
  assert.equal(legacy.level, undefined, '非档位数值不硬凑 level');

  const unknown = await pushSlot('tier-unknown', '写错档位', { level: 'p9' });
  assert.equal(unknown.priority, 30, '未知 level 回退 p4');
});

await test('phone_ui 工具支持 level（p0..p5）', async () => {
  const text = await phoneUiDef.execute({ kind: 'slot', id: 'tier-tool', title: '工具推档位', level: 'p1' });
  assert.match(text, /已推送到手机插槽 tier-tool/);
  const frame = await waitFor(() => slotFrames(ui, 'tier-tool').at(-1), 'tier-tool 帧');
  assert.equal(frame.data.priority, 5, 'p1 = 5');
  assert.equal(frame.data.level, 'p1');
});

await test('/v2/slots 快照：结构 / 排序 / ttl 剩余', async () => {
  const pushed = await pushSlot('snap-a', '快照这条', { level: 'p3', ttl_ms: 8000 });
  const { status, json } = await getSlots(SID);
  assert.equal(status, 200);
  assert.equal(json.ok, true);
  assert.equal(json.session_id, SID);
  assert.ok(Array.isArray(json.slots), 'slots 必须是数组');
  assert.ok(Number(json.slot_epoch) > 0, '快照带插件世代');

  const got = json.slots.find((s) => s.id === 'snap-a');
  assert.ok(got, '快照里应有刚推的槽');
  for (const k of ['id', 'rev', 'ttl_ms', 'priority', 'level', 'title', 'detail',
    'kind', 'progress', 'actions', 'count']) {
    assert.ok(Object.prototype.hasOwnProperty.call(got, k), '快照槽必须含 ' + k);
  }
  assert.equal(got.rev, pushed.rev, '快照沿用最近 rev');
  assert.equal(got.priority, 20);
  assert.equal(got.level, 'p3');
  assert.equal(got.title, '快照这条');
  assert.equal(got.kind, 'state');
  assert.equal(got.progress, 0);
  assert.deepEqual(got.actions, []);
  assert.equal(got.count, 0);
  assert.ok(got.ttl_ms > 0 && got.ttl_ms <= 8000, 'ttl_ms 应是剩余存活时间：' + got.ttl_ms);
  assert.equal(got.epoch, pushed.epoch);

  for (let i = 1; i < json.slots.length; i += 1) {
    assert.ok(
      json.slots[i - 1].priority <= json.slots[i].priority,
      '快照必须按 priority 升序',
    );
  }
});

await test('/v2/slots 快照：按会话隔离（B18）', async () => {
  await pushSlot('snap-scoped', 'root 专用', { session_id: SID });
  await pushSlot('snap-other', '别的会话', { session_id: 'other-session' });

  const mine = await getSlots(SID);
  assert.equal(mine.json.slots.some((s) => s.id === 'snap-scoped'), true);
  assert.equal(mine.json.slots.some((s) => s.id === 'snap-other'), false);

  const other = await getSlots('other-session');
  assert.equal(other.json.slots.some((s) => s.id === 'snap-other'), true);
  assert.equal(other.json.slots.some((s) => s.id === 'snap-scoped'), false, '别的会话的槽不能串过来');
});

await test('/v2/slots 快照：移除帧与过期帧不复活', async () => {
  await pushSlot('snap-gone', '待移除');
  await pushSlot('snap-gone', '', { detail: '' });      // 空帧 = 移除
  await pushSlot('snap-short', '只活 60ms', { ttl_ms: 60 });
  await sleep(150);

  const { json } = await getSlots(SID);
  assert.equal(json.slots.some((s) => s.id === 'snap-gone'), false, '移除帧不该进快照');
  assert.equal(json.slots.some((s) => s.id === 'snap-short'), false, '过期瞬时帧不该进快照');
});

await test('/v2/slots 快照：最多 32 条', async () => {
  for (let i = 0; i < 40; i += 1) {
    await pushSlot('snap-many-' + i, '第 ' + i + ' 条');
  }
  const { json } = await getSlots(SID);
  assert.equal(json.slots.length, 32, '快照最多 32 条');
  assert.equal(json.count, 32);
  assert.ok(json.total >= 40, 'total 应报真实条数：' + json.total);
  assert.equal(json.truncated, true);
});

// ===== ④（2026-11-15）：kind 契约 + 内联 actions =====

await test('kind=progress：progress 夹到 0-100，帧与快照都带', async () => {
  const p = await pushSlot('kind-progress', '压缩中', { kind: 'progress', progress: 42.6 });
  assert.equal(p.kind, 'progress');
  assert.equal(p.progress, 43);
  assert.equal(p.count, 0);
  assert.deepEqual(p.actions, []);
  assert.deepEqual(p.warnings, []);
  const frame = await waitFor(() => slotFrames(ui, 'kind-progress').at(-1), 'kind-progress 帧');
  assert.equal(frame.data.kind, 'progress');
  assert.equal(frame.data.progress, 43);
  const { json } = await getSlots(SID);
  const got = json.slots.find((s) => s.id === 'kind-progress');
  assert.equal(got.kind, 'progress');
  assert.equal(got.progress, 43);

  const over = await pushSlot('kind-progress-over', '超界', { kind: 'progress', progress: 180 });
  assert.equal(over.progress, 100, '越界应夹到 100');
});

await test('未知 kind → state + warn，不 422', async () => {
  const s = await pushSlot('kind-unknown', '未知形态', { kind: 'sparkline' });
  assert.equal(s.kind, 'state');
  assert.ok(
    s.warnings.some((w) => /未知 kind=sparkline/.test(w)),
    '应回 warn：' + JSON.stringify(s.warnings),
  );
});

await test('必填不匹配 → warn + 降级 state', async () => {
  const noProgress = await pushSlot('kind-progress-missing', '缺进度', { kind: 'progress' });
  assert.equal(noProgress.kind, 'state');
  assert.ok(noProgress.warnings.length > 0, '应回 warn');

  const noCount = await pushSlot('kind-list-nocount', '清单', { kind: 'list' });
  assert.equal(noCount.kind, 'state');

  const noActions = await pushSlot('kind-action-empty', '动作', { kind: 'action' });
  assert.equal(noActions.kind, 'state');

  const noTitle = await pushSlot('kind-progress-notitle', '', {
    kind: 'progress', progress: 20, detail: '只有副标题',
  });
  assert.equal(noTitle.kind, 'state', '非 state 缺 title 也要降级');
});

await test('kind=list：count 保留并出快照', async () => {
  const s = await pushSlot('kind-list', '插话排队', { kind: 'list', count: 3, detail: '3 条等待' });
  assert.equal(s.kind, 'list');
  assert.equal(s.count, 3);
  const { json } = await getSlots(SID);
  const got = json.slots.find((x) => x.id === 'kind-list');
  assert.equal(got.kind, 'list');
  assert.equal(got.count, 3);
});

await test('kind=action：actions 透传、最多 2 个、style 归一化', async () => {
  const s = await pushSlot('kind-action', '权限', {
    kind: 'action',
    actions: [
      { id: 'permission', label: '调整', style: 'primary' },
      { id: 'jobs', label: '任务' },
      { id: 'extra', label: '多余' },
    ],
  });
  assert.equal(s.kind, 'action');
  assert.equal(s.actions.length, 2, 'actions 最多 2 个');
  assert.deepEqual(s.actions[0], { id: 'permission', label: '调整', style: 'primary' });
  assert.deepEqual(s.actions[1], { id: 'jobs', label: '任务', style: 'plain' });
  assert.ok(s.warnings.some((w) => /超过 2 个/.test(w)), '截断要回 warn');

  const frame = await waitFor(() => slotFrames(ui, 'kind-action').at(-1), 'kind-action 帧');
  assert.equal(frame.data.kind, 'action');
  assert.equal(frame.data.actions.length, 2);
  const { json } = await getSlots(SID);
  const got = json.slots.find((x) => x.id === 'kind-action');
  assert.equal(got.kind, 'action');
  assert.equal(got.actions[0].id, 'permission');
  assert.equal(got.actions[0].style, 'primary');
});

await test('旧 kind=display → state；无 kind 默认 state', async () => {
  const legacy = await pushSlot('kind-display', '旧帧', { kind: 'display' });
  assert.equal(legacy.kind, 'state');
  assert.equal(legacy.warnings.length, 0, '旧别名不该回 warn');
  const plain = await pushSlot('kind-plain', '普通帧');
  assert.equal(plain.kind, 'state');
});

// 收尾：关 socket，按结果退出（插件定时器不能让 node 挂着）
try { ui?.ws.close(); } catch { /* ignore */ }
await sleep(50);
console.log(`\n${passed} passed${process.exitCode ? '（有失败）' : ''}`);
process.exit(process.exitCode ?? 0);
