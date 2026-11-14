// ws_frames.test.mjs —— `/ws/events` 帧对齐自测（2026-11-15，WS-FRAMES）
//
// 【验证方式 · 报告里要照抄】
//   真 WebSocket + 真 HTTP，不是假 ws 对象：用假 ctx 把插件 `apply` 起来，LAN 服务器
//   监听随机端口（不碰正在运行的 8218 / 54987），手机端同款路径 `/ws/events` 连上去，
//   再从**生产入口**注入帧：
//     · `session/event` 宿主回调            → 真实事件帧 `event`
//     · `POST /session {action:'resync'}`   → `__force_resync`
//     · `POST /v2/action {action:'debug.approval'}` → `approval` / `approval_gone`
//     · `ctx.on('user-questions/request')`  → `question`
//     · `POST /v2/card`                     → `ui/card`（合成帧回归）
//   另验两条边界：caps 过滤仍生效（只声明 events.stream 的连接收不到 approval）、
//   `/ws/ui` 收不到 event（端点白名单没被打穿）、`pull` 仍回 `batch`。
//
// 【边界】
//   - DSH 宿主没有真跑：`session/event` 是**直接调用插件注册的那个回调**（帧来源真实，
//     但不是真实模型回合）；真机样本见提交报告。
//   - 进程内会留下插件自己的定时器（子代理轮询 / WS 心跳），跑完 `process.exit`。
//
// 跑法：cd tools/dsh-plugin-smartquiz-remote && node ws_frames.test.mjs
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { WebSocket } from 'ws';
import { apply } from './index.js';

const TOKEN = 'ws-frames-test-token';
const SID = 'root-1';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 随机空闲端口：测试不碰真实插件的 8218 */
const PORT = await new Promise((resolve, reject) => {
  const s = createServer();
  s.on('error', reject);
  s.listen(0, '127.0.0.1', () => {
    const p = s.address().port;
    s.close(() => resolve(p));
  });
});

// ---------- 假 ctx（只实现 apply 真正用到的面） ----------
const routes = new Map();
const handlers = new Map();
const sessions = new Map([[SID, { id: SID, seq: 3, eventAt: () => undefined }]]);

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
    port: 19387,
    register({ kind, path, handler }) {
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
  agents: {
    get() { return undefined; },
    list() { return []; },
  },
  sessions: {
    get(id) { return sessions.get(id); },
  },
  tools: {
    register() { return () => {}; },
  },
  logger: { info() {}, warn() {}, debug() {} },
};

apply(ctx, {
  token: TOKEN,
  lanPort: PORT,
  lanEnabled: true,
  extraLanPorts: '',   // 别去抢 80
  cliMode: false,
});

const firstHandler = (name) => (handlers.get(name) ?? [])[0];
const lastHandler = (name) => (handlers.get(name) ?? []).at(-1);

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
    headers: {
      'content-type': 'application/json',
      authorization: 'Bearer ' + TOKEN,
    },
    body: JSON.stringify(body),
  });
  let json = null;
  try { json = await res.json(); } catch { /* 非 JSON */ }
  return { status: res.status, json };
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

/**
 * 连一条 WS：LAN 服务器 / `import('ws')` 都是异步起来的，失败就退避重试。
 * @param {string} channel '/ws/events' | '/ws/ui'
 * @param {string} caps    客户端能力（不传 = legacy 全放行）
 */
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
      await waitFor(() => frames.find((f) => f.type === 'hello'), channel + ' 的 hello', 3000);
      return { ws, frames };
    } catch (e) {
      lastErr = e;
      await sleep(100);
    }
  }
  throw lastErr ?? new Error('WS 连不上 ' + channel);
}

function findFrame(frames, pred) {
  return frames.find(pred);
}

// ---------- 自测 ----------
let events = null;
let strict = null;
let ui = null;

await test('握手：/ws/events 的 hello 声明了 event / __force_resync / approval / question', async () => {
  events = await connect('/ws/events', 'events.stream,parts.approval,parts.questions');
  const hello = events.frames.find((f) => f.type === 'hello');
  assert.equal(hello.channel, '/ws/events');
  assert.equal(hello.features.event, true);
  assert.equal(hello.features.__force_resync, true);
  assert.equal(hello.features.approval, true);
  assert.equal(hello.features.question, true);
  // §18（2026-11-15）：hello 带回客户端 caps 摘要，手机端直接打印/落日志
  assert.ok(hello.caps && typeof hello.caps === 'object', 'hello 应带 caps 摘要');
  assert.equal(hello.caps.version, 1);
  assert.equal(hello.caps.legacy, false);
  assert.equal(hello.caps.declared_count, 3);
  assert.deepEqual(
    [...hello.caps.declared].sort(),
    ['events.stream', 'parts.approval', 'parts.questions'],
  );
  assert.equal(hello.caps.missing.includes('parts.approval'), false);
  assert.equal(hello.caps.missing.includes('view.cards.timeline'), true);
  assert.deepEqual(hello.caps.unknown, []);
});

await test('合成帧保持现状：POST /v2/card → ui/card 仍到 /ws/events', async () => {
  const r = await post('/v2/card', {
    session_id: SID,
    type: 'alert_card',
    props: { title: '自测卡', content: '合成帧回归' },
  });
  assert.equal(r.status, 200);
  const frame = await waitFor(
    () => findFrame(events.frames, (f) => f.type === 'ui/card' && f.data?.key === r.json.key),
    'ui/card 帧',
  );
  assert.equal(frame.data.props.title, '自测卡');
});

await test('真实事件：session/event → {type:event, event} 到 /ws/events', async () => {
  const onSession = firstHandler('session/event');
  assert.ok(onSession, '插件应注册 session/event 监听');
  onSession({ id: SID }, {
    seq: 4242,
    id: 'ev-selftest-1',
    time: Date.now(),
    type: 'assistant/message',
    data: { message: { content: [{ type: 'text', text: '自测真实事件' }] } },
  });
  const frame = await waitFor(
    () => findFrame(events.frames, (f) => f.type === 'event' && f.event?.seq === 4242),
    'event 帧',
  );
  assert.equal(frame.event.type, 'assistant/message');
  assert.equal(frame.event.data.message.content[0].text, '自测真实事件');
});

await test('__force_resync：POST /v2/action action=resync → 帧到 /ws/events', async () => {
  const r = await post('/v2/action', { action: 'resync', server_seq: 77, caps: 'cmd.ui' });
  assert.equal(r.status, 200);
  const frame = await waitFor(
    () => findFrame(events.frames, (f) => f.type === '__force_resync'),
    '__force_resync 帧',
  );
  assert.equal(frame.data.server_seq, 77);
});

await test('approval / approval_gone：debug.approval 推帧，应答后收回', async () => {
  const pushed = await post('/v2/action', {
    action: 'debug.approval',
    session_id: SID,
    caps: 'cmd.ui',
    ttl_sec: 15,
    tool: '自测审批',
  });
  assert.equal(pushed.status, 200);
  const id = pushed.json.id;
  assert.ok(String(id).startsWith('test-'), '调试审批 id 应形如 test-');
  const frame = await waitFor(
    () => findFrame(events.frames, (f) => f.type === 'approval' && f.id === id),
    'approval 帧',
  );
  assert.equal(frame.request.toolName, '自测审批');
  const answered = await post('/session', { action: 'approval', approval_id: id, decision: 'allow' });
  assert.equal(answered.status, 200);
  await waitFor(
    () => findFrame(events.frames, (f) => f.type === 'approval_gone' && f.id === id),
    'approval_gone 帧',
  );
});

await test('question：user-questions/request 回调 → 帧到 /ws/events', async () => {
  const onQuestion = lastHandler('user-questions/request');
  assert.ok(onQuestion, '插件应注册 user-questions/request 监听');
  // 反问要等手机回答：这里只验"帧下发"，不 await 那个 Promise（跑完 process.exit）
  const pending = onQuestion({
    agent: { session: { id: SID } },
    questions: [{ id: 'q1', question: '自测反问：选哪个？' }],
  }, () => {});
  pending?.catch?.(() => {});
  const frame = await waitFor(
    () => findFrame(events.frames, (f) => f.type === 'question' && f.id?.startsWith('q-')),
    'question 帧',
  );
  assert.equal(frame.request.questions[0].question, '自测反问：选哪个？');
});

await test('caps 过滤仍生效：只声明 events.stream 的连接收 event、不收 approval', async () => {
  strict = await connect('/ws/events', 'events.stream');
  firstHandler('session/event')({ id: SID }, {
    seq: 4243, time: Date.now(), type: 'assistant/message',
    data: { message: { content: [{ type: 'text', text: 'caps 自测' }] } },
  });
  await waitFor(
    () => findFrame(strict.frames, (f) => f.type === 'event' && f.event?.seq === 4243),
    '受限连接的 event 帧',
  );
  const pushed = await post('/v2/action', {
    action: 'debug.approval', session_id: SID, caps: 'cmd.ui', ttl_sec: 10,
  });
  assert.equal(pushed.status, 200);
  await sleep(300);
  assert.equal(
    findFrame(strict.frames, (f) => f.type === 'approval'),
    undefined,
    '缺 parts.approval 的连接不应收到审批帧',
  );
});

await test('端点白名单没被打穿：/ws/ui 收不到 event', async () => {
  ui = await connect('/ws/ui', 'cmd.ui');
  firstHandler('session/event')({ id: SID }, {
    seq: 4244, time: Date.now(), type: 'assistant/message',
    data: { message: { content: [{ type: 'text', text: '不该到 UI 端点' }] } },
  });
  await sleep(300);
  assert.equal(
    findFrame(ui.frames, (f) => f.type === 'event'),
    undefined,
    '/ws/ui 不该收到 event 帧',
  );
});

await test('pull 语义未动：/ws/events 发 pull 仍回 batch', async () => {
  events.ws.send(JSON.stringify({ type: 'pull', since: 0, max: 10 }));
  const batch = await waitFor(
    () => findFrame(events.frames, (f) => f.type === 'batch'),
    'batch 帧',
  );
  assert.ok(Array.isArray(batch.events), 'batch.events 应为数组');
  assert.equal(typeof batch.last_seq, 'number');
});

await test('/status 按连接列出 caps 摘要（declared/missing/unknown/version）', async () => {
  const res = await fetch(`http://127.0.0.1:${PORT}/status`, {
    headers: { authorization: 'Bearer ' + TOKEN },
  });
  const body = await res.json();
  assert.equal(body.caps.version, 1);
  assert.ok(Array.isArray(body.caps.clients), '/status.caps.clients 应为数组');
  // 当前有 3 条连接：events（3 cap）、strict（events.stream）、ui（cmd.ui）
  assert.ok(body.caps.clients.length >= 3, '应列出 3 条已连接客户端');
  const full = body.caps.clients.find(
    (c) => c.channel === '/ws/events' && c.declared_count === 3,
  );
  assert.ok(full, '应找到声明 3 条能力的 /ws/events 连接');
  assert.equal(full.transport, 'ws');
  assert.equal(full.legacy, false);
  assert.deepEqual(
    [...full.declared].sort(),
    ['events.stream', 'parts.approval', 'parts.questions'],
  );
  assert.equal(full.missing.includes('parts.approval'), false);
  assert.deepEqual(full.unknown, []);
  const restricted = body.caps.clients.find(
    (c) => c.channel === '/ws/events' && c.declared_count === 1,
  );
  assert.ok(restricted, '应找到只声明 events.stream 的受限连接');
  assert.equal(restricted.missing.includes('parts.approval'), true);
  const uiClient = body.caps.clients.find((c) => c.channel === '/ws/ui');
  assert.ok(uiClient, '应列出 /ws/ui 连接');
  assert.deepEqual(uiClient.declared, ['cmd.ui']);
});

// 收尾：关 socket，按结果退出（插件定时器不能让 node 挂着）
for (const s of [events, strict, ui]) {
  try { s?.ws.close(); } catch { /* ignore */ }
}
await sleep(50);
console.log(`\n${passed} passed${process.exitCode ? '（有失败）' : ''}`);
process.exit(process.exitCode ?? 0);
