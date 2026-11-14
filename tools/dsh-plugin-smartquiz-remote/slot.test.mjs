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

// 收尾：关 socket，按结果退出（插件定时器不能让 node 挂着）
try { ui?.ws.close(); } catch { /* ignore */ }
await sleep(50);
console.log(`\n${passed} passed${process.exitCode ? '（有失败）' : ''}`);
process.exit(process.exitCode ?? 0);
